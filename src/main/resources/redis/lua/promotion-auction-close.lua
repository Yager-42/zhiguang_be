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
if stateVersion < 0 or windowEndAtEpochMs < 0 or not status or not resourceType then
    return unavailable('REDIS_STATE_INCOMPLETE')
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
if status ~= 'OPEN' then
    return unavailable('REDIS_WINDOW_STATE_MISMATCH')
end

local auctionWindowId = ARGV[1]
local hotStateTtlSeconds = tonumber(ARGV[2])
local decisionVersion = stateVersion + 1
local commandId = 'close:' .. auctionWindowId
local result = cjson.encode({
    decisionId = commandId .. ':v' .. tostring(decisionVersion),
    commandId = commandId,
    requestHash = commandId,
    auctionWindowId = auctionWindowId,
    decisionVersion = decisionVersion,
    previousVersion = stateVersion,
    campaignId = '0',
    bidderUserId = '0',
    postId = '0',
    resourceType = resourceType,
    type = 'WINDOW_CLOSED',
    accepted = true,
    bidAmount = 0,
    ranking = cjson.decode('[]'),
    walletEffects = cjson.decode('[]'),
    payload = {},
    decidedAtEpochMs = nowEpochMs
})
redis.call('HSET', stateKey,
        'status', 'CLOSED',
        'decisionVersion', tostring(decisionVersion),
        'updatedAt', tostring(nowEpochMs),
        'closeResult', result)
redis.call('XADD', eventsKey, tostring(decisionVersion) .. '-0', 'decision', result)
redis.call('EXPIRE', stateKey, hotStateTtlSeconds)
redis.call('EXPIRE', eventsKey, hotStateTtlSeconds)
redis.call('PUBLISH', publicationChannel, tostring(decisionVersion) .. '-0')
return result
