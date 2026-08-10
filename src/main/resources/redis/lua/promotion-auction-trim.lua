local eventsKey = KEYS[1]
local minimumId = ARGV[1]
local value = redis.call('TYPE', eventsKey)
local keyType = type(value) == 'table' and value.ok or value
if keyType == 'none' then return 0 end
if keyType ~= 'stream' then return redis.error_reply('REDIS_KEY_TYPE_MISMATCH') end
return redis.call('XTRIM', eventsKey, 'MINID', minimumId)
