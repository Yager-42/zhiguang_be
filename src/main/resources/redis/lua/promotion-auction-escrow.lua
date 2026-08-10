local stateKey = KEYS[1]
local escrowKey = KEYS[2]

local function redis_type(key)
    local value = redis.call('TYPE', key)
    if type(value) == 'table' then return value.ok end
    return value
end

if redis_type(stateKey) ~= 'hash' or redis_type(escrowKey) ~= 'hash' then
    return 'REDIS_STATE_NOT_INITIALIZED'
end

local campaignId = ARGV[1]
local authorizedAmount = tonumber(ARGV[2])
local hotStateTtlSeconds = tonumber(ARGV[3])
local authorizedField = campaignId .. ':authorizedAmount'
local existing = tonumber(redis.call('HGET', escrowKey, authorizedField) or '0')
if existing > authorizedAmount then
    return 'REDIS_AUTHORIZATION_REGRESSION'
end
redis.call('HSET', escrowKey, authorizedField, tostring(authorizedAmount))
redis.call('HSETNX', escrowKey, campaignId .. ':currentHold', '0')
redis.call('EXPIRE', stateKey, hotStateTtlSeconds)
redis.call('EXPIRE', escrowKey, hotStateTtlSeconds)
return 'OK'
