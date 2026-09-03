local rankingKey = KEYS[1]
local stateKey = KEYS[2]

local campaignKeyPrefix = ARGV[1]
local limit = tonumber(ARGV[2])
local members = redis.call('ZRANGE', rankingKey, 0, limit - 1)
local items = {}

for rank, rankingMember in ipairs(members) do
    local campaignId = string.match(rankingMember, '^[^:]+:(.+)$') or rankingMember
    local values = redis.call('HMGET', campaignKeyPrefix .. campaignId, 'bidAmount', 'bidderUserId', 'postId')
    if values[1] and values[2] and values[3] then
        items[#items + 1] = '{"campaignId":"' .. campaignId
                .. '","bidderUserId":"' .. values[2]
                .. '","postId":"' .. values[3]
                .. '","bidAmount":' .. values[1]
                .. ',"rank":' .. rank .. '}'
    end
end

-- 英式升价快照：共享当前价、当前赢家、固定截止时间与价格规则。
local version = redis.call('HGET', stateKey, 'decisionVersion') or '0'
local status = redis.call('HGET', stateKey, 'status') or ''
local currentPriceCents = redis.call('HGET', stateKey, 'currentPriceCents') or '0'
local winnerCampaignId = redis.call('HGET', stateKey, 'winnerCampaignId') or ''
local windowEndAtEpochMs = redis.call('HGET', stateKey, 'windowEndAtEpochMs') or '0'
local bidCount = redis.call('HGET', stateKey, 'bidCount') or '0'
local incrementCents = redis.call('HGET', stateKey, 'incrementCents') or '0'
local capPriceCents = redis.call('HGET', stateKey, 'capPriceCents') or '0'
local reservePrice = redis.call('HGET', stateKey, 'reservePrice') or '0'
return '{"decisionVersion":' .. version
        .. ',"status":"' .. status .. '"'
        .. ',"currentPriceCents":' .. currentPriceCents
        .. ',"winnerCampaignId":"' .. winnerCampaignId .. '"'
        .. ',"windowEndAtEpochMs":' .. windowEndAtEpochMs
        .. ',"bidCount":' .. bidCount
        .. ',"rules":{"stepCents":' .. incrementCents
        .. ',"capCents":' .. capPriceCents
        .. ',"reserveCents":' .. reservePrice .. '}'
        .. ',"ranking":[' .. table.concat(items, ',') .. ']}'
