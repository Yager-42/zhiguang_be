local campaignKey = KEYS[1]
local rankingKey = KEYS[2]
local stateKey = KEYS[3]

local campaignId = ARGV[1]
local bidAmount = ARGV[2]
local bidderUserId = ARGV[3]
local postId = ARGV[4]
local updatedAt = ARGV[5]
local score = ARGV[6]
local ttlSeconds = tonumber(ARGV[7])

redis.call('HSET', campaignKey,
        'bidAmount', bidAmount,
        'bidderUserId', bidderUserId,
        'postId', postId,
        'updatedAt', updatedAt)
redis.call('ZADD', rankingKey, score, campaignId)
redis.call('HSET', stateKey, 'status', 'OPEN', 'updatedAt', updatedAt)
redis.call('EXPIRE', campaignKey, ttlSeconds)
redis.call('EXPIRE', rankingKey, ttlSeconds)
redis.call('EXPIRE', stateKey, ttlSeconds)
return 1
