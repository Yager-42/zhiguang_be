local stateKey = KEYS[1]
local eventsKey = KEYS[2]
local publicationChannel = KEYS[3]

local function redis_type(key)
    local value = redis.call('TYPE', key)
    if type(value) == 'table' then return value.ok end
    return value
end

local function unavailable(reason)
    return cjson.encode({status = 'UNAVAILABLE', rejectionReason = reason})
end

if redis_type(stateKey) ~= 'hash' then
    return unavailable('REDIS_STATE_NOT_INITIALIZED')
end
if redis_type(eventsKey) ~= 'none' and redis_type(eventsKey) ~= 'stream' then
    return unavailable('REDIS_KEY_TYPE_MISMATCH')
end

local existingResult = redis.call('HGET', stateKey, 'closeResult')
if existingResult then return existingResult end

local stateVersion = tonumber(redis.call('HGET', stateKey, 'decisionVersion') or '-1')
local windowEndAtEpochMs = tonumber(redis.call('HGET', stateKey, 'windowEndAtEpochMs') or '-1')
local status = redis.call('HGET', stateKey, 'status')
local resourceType = redis.call('HGET', stateKey, 'resourceType')
local currentPriceCents = tonumber(redis.call('HGET', stateKey, 'currentPriceCents') or '0')
local winnerCampaignId = redis.call('HGET', stateKey, 'winnerCampaignId') or ''
if stateVersion < 0 or windowEndAtEpochMs < 0 or not status or not resourceType then
    return unavailable('REDIS_STATE_INCOMPLETE')
end

-- Go close_auction.lua L30 同构：非 OPEN（cap-hit SOLD / 已 NO_BID / 已 CLOSED）
-- 直接幂等 no-op，早于 NOT_DUE 判定；Java 侧成功后 ZREM closingIndex，不再重试。
if status ~= 'OPEN' then
    return cjson.encode({status = 'ALREADY_TERMINAL'})
end

local lastEntries = redis.call('XREVRANGE', eventsKey, '+', '-', 'COUNT', 1)
if stateVersion == 0 then
    if #lastEntries ~= 0 then return unavailable('REDIS_STREAM_VERSION_MISMATCH') end
elseif #lastEntries == 0 or lastEntries[1][1] ~= tostring(stateVersion) .. '-0' then
    return unavailable('REDIS_STREAM_VERSION_MISMATCH')
end

local redisTime = redis.call('TIME')
local nowEpochMs = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)
if nowEpochMs < windowEndAtEpochMs then
    return cjson.encode({status = 'NOT_DUE'})
end

local auctionWindowId = ARGV[1]
local hotStateTtlSeconds = tonumber(ARGV[2])
local decisionVersion = stateVersion + 1
local commandId = 'close:' .. auctionWindowId
local terminalType
local terminalStatus
local terminalPayload
if winnerCampaignId ~= '' then
    terminalType = 'AUCTION_SOLD'
    terminalStatus = 'SOLD'
    terminalPayload = {
        winnerCampaignId = winnerCampaignId,
        winningAmount = currentPriceCents,
        actualEndAtEpochMs = nowEpochMs
    }
else
    terminalType = 'AUCTION_NO_BID'
    terminalStatus = 'NO_BID'
    terminalPayload = {actualEndAtEpochMs = nowEpochMs}
end
local result = cjson.encode({
    decisionId = commandId .. ':v' .. tostring(decisionVersion),
    commandId = commandId,
    requestHash = commandId,
    auctionWindowId = auctionWindowId,
    decisionVersion = decisionVersion,
    previousVersion = stateVersion,
    campaignId = winnerCampaignId,
    bidderUserId = '0',
    postId = '0',
    resourceType = resourceType,
    type = terminalType,
    accepted = true,
    bidAmount = 0,
    ranking = cjson.decode('[]'),
    walletEffects = cjson.decode('[]'),
    payload = terminalPayload,
    decidedAtEpochMs = nowEpochMs
})
redis.call('HSET', stateKey,
        'status', terminalStatus,
        'decisionVersion', tostring(decisionVersion),
        'updatedAt', tostring(nowEpochMs),
        'closeResult', result)
redis.call('XADD', eventsKey, tostring(decisionVersion) .. '-0', 'decision', result)
redis.call('EXPIRE', stateKey, hotStateTtlSeconds)
redis.call('EXPIRE', eventsKey, hotStateTtlSeconds)
redis.call('PUBLISH', publicationChannel, tostring(decisionVersion) .. '-0')
return result
