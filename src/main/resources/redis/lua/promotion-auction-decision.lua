-- BD B' promotion decision authority. All KEYS share the {windowId} Redis Cluster slot.
local stateKey = KEYS[1]
local commandsKey = KEYS[2]
local rankingKey = KEYS[3]
local campaignKey = KEYS[4]
local escrowKey = KEYS[5]

local commandId = ARGV[1]
local requestHash = ARGV[2]
local bidderUserId = ARGV[3]
local bidAmount = tonumber(ARGV[4]) or 0
local reservePrice = tonumber(ARGV[5]) or 0
local nowEpochMs = tonumber(ARGV[6]) or 0
local windowStatus = ARGV[7]
local auctionWindowId = ARGV[8]
local campaignId = ARGV[9]
local postId = ARGV[10]
local resourceType = ARGV[11]
local hotStateTtlSeconds = tonumber(ARGV[12])
local submittedAt = ARGV[13]
local commandType = ARGV[14]
local commandIdempotencyTtlSeconds = tonumber(ARGV[15])
local prefix = 'promotion:auction:{' .. auctionWindowId .. '}'

local function empty_array()
    return cjson.decode('[]')
end

redis.call('HSETNX', stateKey, 'decisionVersion', '0')
redis.call('HSETNX', stateKey, 'status', windowStatus)
redis.call('HSETNX', stateKey, 'reservePrice', tostring(reservePrice))
redis.call('HSETNX', stateKey, 'resourceType', resourceType)

local function refresh_ttl()
    for index, key in ipairs(KEYS) do
        if index ~= 2 then
            redis.call('EXPIRE', key, hotStateTtlSeconds)
        end
    end
end

local function expire_command_fields(fields)
    redis.call('HEXPIRE', commandsKey, commandIdempotencyTtlSeconds,
            'FIELDS', #fields, unpack(fields))
end

local function ranking_items()
    local members = redis.call('ZREVRANGE', rankingKey, 0, 29)
    local items = {}
    for rank, member in ipairs(members) do
        local values = redis.call('HMGET', prefix .. ':campaign:' .. member,
                'bidAmount', 'bidderUserId', 'postId')
        if values[1] and values[2] and values[3] then
            items[#items + 1] = {
                campaignId = member,
                bidderUserId = values[2],
                postId = values[3],
                bidAmount = tonumber(values[1]),
                rank = rank
            }
        end
    end
    return items
end

local function create_decision(decisionType, accepted, reason, ranking, payload)
    local previousVersion = tonumber(redis.call('HGET', stateKey, 'decisionVersion') or '0')
    local decisionVersion = redis.call('HINCRBY', stateKey, 'decisionVersion', 1)
    local decisionRanking = ranking
    if not decisionRanking or next(decisionRanking) == nil then
        decisionRanking = empty_array()
    end
    return cjson.encode({
        decisionId = commandId .. ':v' .. decisionVersion,
        commandId = commandId,
        requestHash = requestHash,
        auctionWindowId = auctionWindowId,
        decisionVersion = decisionVersion,
        previousVersion = previousVersion,
        campaignId = campaignId,
        bidderUserId = bidderUserId,
        postId = postId,
        resourceType = resourceType,
        type = decisionType,
        accepted = accepted,
        rejectionReason = reason,
        bidAmount = bidAmount,
        ranking = decisionRanking,
        walletEffects = empty_array(),
        payload = payload or {submittedAt = submittedAt},
        decidedAtEpochMs = nowEpochMs
    })
end

local replayHash = redis.call('HGET', commandsKey, commandId .. ':hash')
if replayHash then
    if replayHash == requestHash then
        local replay = redis.call('HGET', commandsKey, commandId .. ':decision')
        expire_command_fields({commandId .. ':hash', commandId .. ':decision'})
        refresh_ttl()
        return replay
    end
    local conflictField = commandId .. ':conflict:' .. requestHash
    local conflictReplay = redis.call('HGET', commandsKey, conflictField)
    if conflictReplay then
        expire_command_fields({conflictField})
        refresh_ttl()
        return conflictReplay
    end
    local conflict = create_decision('BID_REJECTED', false, 'IDEMPOTENCY_CONFLICT')
    redis.call('HSET', commandsKey, conflictField, conflict)
    expire_command_fields({conflictField})
    refresh_ttl()
    return conflict
end

local function store(decision)
    redis.call('HSET', commandsKey,
            commandId .. ':hash', requestHash,
            commandId .. ':decision', decision)
    expire_command_fields({commandId .. ':hash', commandId .. ':decision'})
    refresh_ttl()
    return decision
end

if commandType == 'ESCROW_NOTIFY' then
    local authorizedField = campaignId .. ':authorizedAmount'
    local currentHoldField = campaignId .. ':currentHold'
    local existingAuthorized = tonumber(redis.call('HGET', escrowKey, authorizedField) or '0')
    local authorizedAmount = math.max(existingAuthorized, bidAmount)
    local currentHold = tonumber(redis.call('HGET', escrowKey, currentHoldField) or '0')
    redis.call('HSET', escrowKey,
            authorizedField, tostring(authorizedAmount),
            currentHoldField, tostring(currentHold))
    return store(create_decision('ESCROW_APPLIED', false, nil, {}, {
        submittedAt = submittedAt,
        authorizedAmount = authorizedAmount,
        currentHold = currentHold
    }))
end

if commandType ~= 'BID' then
    return store(create_decision('BID_REJECTED', false, 'UNSUPPORTED_COMMAND'))
end

if redis.call('HGET', stateKey, 'status') ~= 'OPEN' then
    return store(create_decision('BID_REJECTED', false, 'WINDOW_CLOSED'))
end

local effectiveReservePrice = tonumber(redis.call('HGET', stateKey, 'reservePrice') or tostring(reservePrice))
if bidAmount < effectiveReservePrice then
    return store(create_decision('BID_REJECTED', false, 'BELOW_RESERVE'))
end

local existingBidAmount = tonumber(redis.call('HGET', campaignKey, 'bidAmount') or '0')
if existingBidAmount >= bidAmount then
    return store(create_decision('BID_REJECTED', false, 'BID_NOT_HIGHER'))
end

local authorizedAmount = tonumber(redis.call('HGET', escrowKey, campaignId .. ':authorizedAmount') or '0')
if authorizedAmount < bidAmount then
    return store(create_decision('BID_REJECTED', false, 'ESCROW_INSUFFICIENT'))
end

local score = (bidAmount * 1000000000000) - nowEpochMs
redis.call('HSET', campaignKey,
        'bidAmount', tostring(bidAmount),
        'bidderUserId', bidderUserId,
        'postId', postId,
        'updatedAt', tostring(nowEpochMs))
redis.call('ZADD', rankingKey, score, campaignId)
redis.call('HSET', escrowKey, campaignId .. ':currentHold', tostring(bidAmount))
redis.call('HSET', stateKey, 'updatedAt', tostring(nowEpochMs))

return store(create_decision('BID_ACCEPTED', true, nil, ranking_items(), {
    submittedAt = submittedAt,
    authorizedAmount = authorizedAmount,
    currentHold = bidAmount
}))
