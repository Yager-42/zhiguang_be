local stateKey = KEYS[1]
local rankingKey = KEYS[2]
local escrowKey = KEYS[3]
local eventsKey = KEYS[4]

local function redis_type(key)
    local value = redis.call('TYPE', key)
    if type(value) == 'table' then return value.ok end
    return value
end

local expectedTypes = {{stateKey, 'hash'}, {rankingKey, 'zset'}, {escrowKey, 'hash'}, {eventsKey, 'stream'}}
for _, entry in ipairs(expectedTypes) do
    local actual = redis_type(entry[1])
    if actual ~= 'none' and actual ~= entry[2] then
        return 'REDIS_KEY_TYPE_MISMATCH'
    end
end

local windowEndAtEpochMs = ARGV[1]
local reservePrice = ARGV[2]
local slotCount = ARGV[3]
local resourceType = ARGV[4]
local initialDecisionVersion = ARGV[5]
local hotStateTtlSeconds = tonumber(ARGV[6])

if redis_type(stateKey) == 'hash' then
    if redis.call('HGET', stateKey, 'windowEndAtEpochMs') ~= windowEndAtEpochMs
            or redis.call('HGET', stateKey, 'reservePrice') ~= reservePrice
            or redis.call('HGET', stateKey, 'slotCount') ~= slotCount
            or redis.call('HGET', stateKey, 'resourceType') ~= resourceType then
        return 'REDIS_STATE_MISMATCH'
    end
else
    redis.call('HSET', stateKey,
            'decisionVersion', initialDecisionVersion,
            'status', 'OPEN',
            'windowEndAtEpochMs', windowEndAtEpochMs,
            'reservePrice', reservePrice,
            'slotCount', slotCount,
            'resourceType', resourceType)
end

-- Materialize empty hashes so type checks can distinguish missing initialization.
redis.call('HSETNX', escrowKey, '_initialized', '1')
redis.call('EXPIRE', stateKey, hotStateTtlSeconds)
redis.call('EXPIRE', escrowKey, hotStateTtlSeconds)
return 'OK'
