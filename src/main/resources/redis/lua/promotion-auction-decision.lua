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

-- 2^53-1: above this a float64 (Lua number / Redis ZSET score) loses integer
-- precision (Go place_bid.lua MAX_MONEY 同构).
local MAX_MONEY = 9007199254740991

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

-- 英式升价参数以 state 为权威（Go place_bid.lua HMGET state 同构；initialize.lua
-- HSETNX 回填 + 此处完整性检查双保险，防旧热状态缺字段导致 required=0 全收）。
local stateValues = redis.call('HMGET', stateKey,
        'decisionVersion', 'windowEndAtEpochMs', 'reservePrice', 'status', 'resourceType',
        'currentPriceCents', 'winnerCampaignId', 'incrementCents', 'capPriceCents',
        'extendWindowSec', 'extendSec', 'maxExtensions', 'extendCount', 'bidCount')
local stateVersionValue = stateValues[1]
local windowEndAtValue = stateValues[2]
local reservePriceValue = stateValues[3]
local windowStatus = stateValues[4]
local stateResourceType = stateValues[5]
local currentPriceValue = stateValues[6]
local winnerCampaignIdValue = stateValues[7]
local incrementValue = stateValues[8]
local capPriceValue = stateValues[9]
local extendWindowValue = stateValues[10]
local extendValue = stateValues[11]
local maxExtensionsValue = stateValues[12]
local extendCountValue = stateValues[13]
local bidCountValue = stateValues[14]
if not stateVersionValue or not windowEndAtValue or not reservePriceValue or not windowStatus
        or not stateResourceType or not currentPriceValue or not winnerCampaignIdValue
        or not incrementValue or not capPriceValue or not extendWindowValue or not extendValue
        or not maxExtensionsValue or not extendCountValue or not bidCountValue then
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
                        decidedAtEpochMs, originalSubmittedAt, authorizedAmount, decisionId, extraPayload)
    local payload = {submittedAt = originalSubmittedAt}
    if authorizedAmount then
        payload.authorizedAmount = authorizedAmount
    end
    if extraPayload then
        for extraKey, extraValue in pairs(extraPayload) do
            payload[extraKey] = extraValue
        end
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

-- Go place_bid.lua ERR_TOO_LOW(amount, required) 同构：requiredAmount=0 表示金额本身非法
-- （<=0 或 > MAX_MONEY），否则为 min(current+increment, cap)。
local function store_rejection(reason, requiredAmount, currentPriceCents)
    local extraPayload = {}
    if requiredAmount then
        extraPayload.requiredAmount = requiredAmount
    end
    if currentPriceCents then
        extraPayload.currentPriceCents = currentPriceCents
    end
    local result = decision('BID_REJECTED', false, reason, stateVersion, stateVersion,
            nowEpochMs, submittedAt, nil, nil, extraPayload)
    store_record(false, reason, stateVersion, stateVersion, nil)
    return result
end

if windowStatus ~= 'OPEN' or nowEpochMs >= tonumber(windowEndAtValue) then
    return store_rejection('WINDOW_CLOSED')
end

local currentPriceCents = tonumber(currentPriceValue)
local incrementCents = tonumber(incrementValue)
local capPriceCents = tonumber(capPriceValue)
local extendWindowSec = tonumber(extendWindowValue)
local extendSec = tonumber(extendValue)
local maxExtensions = tonumber(maxExtensionsValue)
local extendCount = tonumber(extendCountValue)

-- 金额校验链（Go place_bid.lua L80-86）：金额范围 -> required 台阶 -> cap 上限。
if bidAmount <= 0 or bidAmount > MAX_MONEY then
    return store_rejection('BID_NOT_HIGHER', 0, currentPriceCents)
end
local required = currentPriceCents + incrementCents
if capPriceCents > 0 and required > capPriceCents then
    required = capPriceCents
end
if bidAmount < required then
    return store_rejection('BID_NOT_HIGHER', required, currentPriceCents)
end
if capPriceCents > 0 and bidAmount > capPriceCents then
    return store_rejection('BID_NOT_HIGHER', required, currentPriceCents)
end

local authorizedAmount = tonumber(redis.call('HGET', escrowKey, campaignId .. ':authorizedAmount') or '0')
if authorizedAmount < bidAmount then
    return store_rejection('ESCROW_INSUFFICIENT')
end

local campaignValues = redis.call('HMGET', campaignKey, 'bidAmount')
local campaignWasMissing = campaignValues[1] == nil

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
        'currentPriceCents', tostring(bidAmount),
        'winnerCampaignId', tostring(campaignId),
        'updatedAt', tostring(nowEpochMs))
redis.call('HINCRBY', stateKey, 'bidCount', 1)
redis.call('XADD', eventsKey, tostring(decisionVersion) .. '-0', 'decision', result)
store_record(true, nil, decisionVersion, stateVersion, authorizedAmount)

-- 反狙击 / cap-hit（Go place_bid.lua L104-110/L147-162）：cap-hit 优先；第二事件
-- 消耗独立版本，Stream ID 连续无洞（AUCTION_EXTENDED / AUCTION_SOLD）。
local capHit = capPriceCents > 0 and bidAmount >= capPriceCents
local extend = (not capHit) and extendWindowSec > 0 and extendSec > 0
        and (tonumber(windowEndAtValue) - nowEpochMs) <= extendWindowSec * 1000
        and (maxExtensions <= 0 or extendCount < maxExtensions)
if extend then
    local extendedEndAtMs = tonumber(windowEndAtValue) + extendSec * 1000
    local extendVersion = decisionVersion + 1
    redis.call('HSET', stateKey,
            'windowEndAtEpochMs', tostring(extendedEndAtMs),
            'decisionVersion', tostring(extendVersion),
            'extendCount', tostring(extendCount + 1))
    local extResult = decision('AUCTION_EXTENDED', true, nil, extendVersion, decisionVersion,
            nowEpochMs, submittedAt, nil, nil,
            {endAtEpochMs = extendedEndAtMs, extendCount = extendCount + 1})
    redis.call('XADD', eventsKey, tostring(extendVersion) .. '-0', 'decision', extResult)
end
if capHit then
    local soldVersion = decisionVersion + 1
    redis.call('HSET', stateKey,
            'status', 'SOLD',
            'decisionVersion', tostring(soldVersion))
    local soldResult = decision('AUCTION_SOLD', true, nil, soldVersion, decisionVersion,
            nowEpochMs, submittedAt, nil, nil,
            {winnerCampaignId = tostring(campaignId), winningAmount = bidAmount,
             actualEndAtEpochMs = nowEpochMs})
    redis.call('XADD', eventsKey, tostring(soldVersion) .. '-0', 'decision', soldResult)
end

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
