-- Redis is the live decision authority. Every key contains the same {windowId} hash tag.
local stateKey = KEYS[1]
local commandKey = KEYS[2]
local rankingKey = KEYS[3]
local campaignKey = KEYS[4]
local escrowKey = KEYS[5]
local eventsKey = KEYS[6]
local publicationChannel = KEYS[7]
local publicationWakeupKey = KEYS[8]

local commandId = ARGV[1]
local requestHash = ARGV[2]
local bidderUserId = ARGV[3]
local bidAmount = tonumber(ARGV[4]) or 0
local auctionWindowId = ARGV[5]
local campaignId = ARGV[6]
local postId = ARGV[7]
local resourceType = ARGV[8]
local commandTtlSeconds = tonumber(ARGV[9])
local hotStateTtlSeconds = tonumber(ARGV[10])
local submittedAt = ARGV[11]
local publicationWakeupTtlSeconds = tonumber(ARGV[12])

local function redis_type(key)
    local value = redis.call('TYPE', key)
    if type(value) == 'table' then
        return value.ok
    end
    return value
end

local function unavailable(reason)
    return cjson.encode({status = 'UNAVAILABLE', rejectionReason = reason})
end

local expectedTypes = {
    {stateKey, 'hash'},
    {commandKey, 'hash'},
    {rankingKey, 'zset'},
    {escrowKey, 'hash'},
    {eventsKey, 'stream'}
}
local keyTypes = {}
for index, entry in ipairs(expectedTypes) do
    local actual = redis_type(entry[1])
    keyTypes[index] = actual
    if actual ~= 'none' and actual ~= entry[2] then
        return unavailable('REDIS_KEY_TYPE_MISMATCH')
    end
end
if keyTypes[1] ~= 'hash' or keyTypes[4] ~= 'hash' then
    return unavailable('REDIS_STATE_NOT_INITIALIZED')
end
local rankingWasMissing = keyTypes[3] == 'none'
local eventsWasMissing = keyTypes[5] == 'none'

local stateValues = redis.call('HMGET', stateKey,
        'decisionVersion', 'windowEndAtEpochMs', 'reservePrice', 'status', 'resourceType')
local stateVersionValue = stateValues[1]
local windowEndAtValue = stateValues[2]
local reservePriceValue = stateValues[3]
local windowStatus = stateValues[4]
local stateResourceType = stateValues[5]
if not stateVersionValue or not windowEndAtValue or not reservePriceValue or not windowStatus
        or not stateResourceType then
    return unavailable('REDIS_STATE_INCOMPLETE')
end
if stateResourceType ~= resourceType then
    return unavailable('REDIS_STATE_MISMATCH')
end

local stateVersion = tonumber(stateVersionValue)
local streamVersion = 0
if not eventsWasMissing then
    local lastEntry = redis.call('XREVRANGE', eventsKey, '+', '-', 'COUNT', 1)
    if lastEntry[1] then
        local separator = string.find(lastEntry[1][1], '-', 1, true)
        if separator then
            streamVersion = tonumber(string.sub(lastEntry[1][1], 1, separator - 1))
        end
    end
end
if not streamVersion or stateVersion ~= streamVersion then
    return unavailable('REDIS_STREAM_VERSION_MISMATCH')
end

local redisTime = redis.call('TIME')
local nowEpochMs = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)

local function decision(decisionType, accepted, reason, version, previousVersion,
                        decidedAtEpochMs, originalSubmittedAt, authorizedAmount, decisionId)
    local payload = {submittedAt = originalSubmittedAt}
    if authorizedAmount then
        payload.authorizedAmount = authorizedAmount
    end
    return cjson.encode({
        decisionId = decisionId or (commandId .. ':v' .. tostring(version)),
        commandId = commandId,
        requestHash = requestHash,
        auctionWindowId = auctionWindowId,
        decisionVersion = version,
        previousVersion = previousVersion,
        campaignId = campaignId,
        bidderUserId = bidderUserId,
        postId = postId,
        resourceType = resourceType,
        type = decisionType,
        accepted = accepted,
        rejectionReason = reason,
        bidAmount = bidAmount,
        ranking = cjson.decode('[]'),
        walletEffects = cjson.decode('[]'),
        payload = payload,
        decidedAtEpochMs = decidedAtEpochMs
    })
end

local function encode_record(accepted, reason, version, previousVersion, decidedAtEpochMs, authorizedAmount)
    return table.concat({
        requestHash,
        accepted and '1' or '0',
        reason or '',
        tostring(version),
        tostring(previousVersion),
        tostring(decidedAtEpochMs),
        submittedAt,
        authorizedAmount and tostring(authorizedAmount) or ''
    }, '\n')
end

local function decode_record(record)
    local fields = {}
    local fieldStart = 1
    for fieldIndex = 1, 7 do
        local separator = string.find(record, '\n', fieldStart, true)
        if not separator then
            return nil
        end
        fields[fieldIndex] = string.sub(record, fieldStart, separator - 1)
        fieldStart = separator + 1
    end
    fields[8] = string.sub(record, fieldStart)
    return fields
end

local replayRecord = redis.call('HGET', commandKey, commandId)
if replayRecord then
    local replay = decode_record(replayRecord)
    if not replay then
        return unavailable('REDIS_COMMAND_VALUE_INVALID')
    end
    if replay[1] ~= requestHash then
        return decision('BID_REJECTED', false, 'IDEMPOTENCY_CONFLICT',
                stateVersion, stateVersion, nowEpochMs, submittedAt, nil, commandId .. ':rejected')
    end
    local replayVersion = tonumber(replay[4])
    local replayPreviousVersion = tonumber(replay[5])
    local replayDecidedAtEpochMs = tonumber(replay[6])
    if not replayVersion or not replayPreviousVersion or not replayDecidedAtEpochMs then
        return unavailable('REDIS_COMMAND_VALUE_INVALID')
    end
    local replayAccepted = replay[2] == '1'
    local replayReason = replay[3] ~= '' and replay[3] or nil
    local replayAuthorizedAmount = replay[8] ~= '' and tonumber(replay[8]) or nil
    return decision(replayAccepted and 'BID_ACCEPTED' or 'BID_REJECTED', replayAccepted,
            replayReason, replayVersion, replayPreviousVersion, replayDecidedAtEpochMs,
            replay[7], replayAuthorizedAmount, nil)
end

local function store_record(accepted, reason, version, previousVersion, authorizedAmount)
    redis.call('HSET', commandKey, commandId,
            encode_record(accepted, reason, version, previousVersion, nowEpochMs, authorizedAmount))
    redis.call('HEXPIRE', commandKey, commandTtlSeconds, 'FIELDS', 1, commandId)
end

local function store_rejection(reason)
    local result = decision('BID_REJECTED', false, reason, stateVersion, stateVersion,
            nowEpochMs, submittedAt, nil, nil)
    store_record(false, reason, stateVersion, stateVersion, nil)
    return result
end

if windowStatus ~= 'OPEN' or nowEpochMs >= tonumber(windowEndAtValue) then
    return store_rejection('WINDOW_CLOSED')
end
if bidAmount < tonumber(reservePriceValue) then
    return store_rejection('BELOW_RESERVE')
end

local campaignValues = redis.call('HMGET', campaignKey, 'bidAmount')
local existingBidAmount = tonumber(campaignValues[1] or '0')
local campaignWasMissing = campaignValues[1] == nil
if existingBidAmount >= bidAmount then
    return store_rejection('BID_NOT_HIGHER')
end

local authorizedAmount = tonumber(redis.call('HGET', escrowKey, campaignId .. ':authorizedAmount') or '0')
if authorizedAmount < bidAmount then
    return store_rejection('ESCROW_INSUFFICIENT')
end

local decisionVersion = stateVersion + 1
local result = decision('BID_ACCEPTED', true, nil, decisionVersion, stateVersion,
        nowEpochMs, submittedAt, authorizedAmount, nil)
redis.call('HSET', campaignKey,
        'bidAmount', tostring(bidAmount),
        'bidderUserId', bidderUserId,
        'postId', postId,
        'updatedAt', tostring(nowEpochMs))
redis.call('ZADD', rankingKey, 'LT', -bidAmount, tostring(campaignId))
redis.call('HSET', escrowKey, campaignId .. ':currentHold', tostring(bidAmount))
redis.call('HSET', stateKey,
        'decisionVersion', tostring(decisionVersion),
        'updatedAt', tostring(nowEpochMs))
redis.call('XADD', eventsKey, tostring(decisionVersion) .. '-0', 'decision', result)
store_record(true, nil, decisionVersion, stateVersion, authorizedAmount)
if rankingWasMissing then
    redis.call('EXPIRE', rankingKey, hotStateTtlSeconds)
end
if campaignWasMissing then
    redis.call('EXPIRE', campaignKey, hotStateTtlSeconds)
end
if eventsWasMissing then
    redis.call('EXPIRE', eventsKey, hotStateTtlSeconds)
end
local wakeupCreated = redis.call('SET', publicationWakeupKey, '1',
        'EX', publicationWakeupTtlSeconds, 'NX')
if wakeupCreated then
    redis.call('PUBLISH', publicationChannel, tostring(decisionVersion) .. '-0')
end
return result
