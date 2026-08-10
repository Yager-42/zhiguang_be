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

local version = redis.call('HGET', stateKey, 'decisionVersion') or '0'
return '{"decisionVersion":' .. version .. ',"ranking":[' .. table.concat(items, ',') .. ']}'
