local stateKey = KEYS[1]
local commandsKey = KEYS[2]
local rankingKey = KEYS[3]
local campaignKey = KEYS[4]
local versionKey = KEYS[5]

local commandId = ARGV[1]
local requestHash = ARGV[2]
local bidderUserId = ARGV[3]
local bidAmount = tonumber(ARGV[4])
local reservePrice = tonumber(ARGV[5])
local nowEpochMs = tonumber(ARGV[6])
local windowStatus = ARGV[7]
local auctionWindowId = ARGV[8]
local campaignId = ARGV[9]
local postId = ARGV[10]
local resourceType = ARGV[11]

local function ranking_json(pendingCampaignId, pendingBidderUserId, pendingPostId, pendingBidAmount, pendingScore)
    local members = redis.call('ZREVRANGE', rankingKey, 0, 29)
    local parts = {}
    local insertedPending = false
    for _, member in ipairs(members) do
        local key = 'promotion:auction:' .. auctionWindowId .. ':campaign:' .. member
        local amount = redis.call('HGET', key, 'bidAmount') or '0'
        local bidder = redis.call('HGET', key, 'bidderUserId') or '0'
        local p = redis.call('HGET', key, 'postId') or '0'
        local score = tonumber(redis.call('ZSCORE', rankingKey, member) or '0')
        if pendingCampaignId and not insertedPending and score < pendingScore then
            parts[#parts + 1] = '{"campaignId":' .. pendingCampaignId .. ',"bidderUserId":' .. pendingBidderUserId
                    .. ',"postId":' .. pendingPostId .. ',"bidAmount":' .. pendingBidAmount .. ',"rank":' .. (#parts + 1) .. '}'
            insertedPending = true
        end
        if member ~= pendingCampaignId then
            parts[#parts + 1] = '{"campaignId":' .. member .. ',"bidderUserId":' .. bidder .. ',"postId":' .. p
                    .. ',"bidAmount":' .. amount .. ',"rank":' .. (#parts + 1) .. '}'
        end
    end
    if pendingCampaignId and not insertedPending then
        parts[#parts + 1] = '{"campaignId":' .. pendingCampaignId .. ',"bidderUserId":' .. pendingBidderUserId
                .. ',"postId":' .. pendingPostId .. ',"bidAmount":' .. pendingBidAmount .. ',"rank":' .. (#parts + 1) .. '}'
    end
    local limited = {}
    for i = 1, math.min(#parts, 30) do
        limited[#limited + 1] = parts[i]
    end
    return '[' .. table.concat(limited, ',') .. ']'
end

local function decision_json(accepted, decisionType, reason, effects, ranking, decisionVersion)
    local version = decisionVersion or tonumber(redis.call('INCR', versionKey))
    local previousVersion = version - 1
    local rejection = reason and ('"' .. reason .. '"') or 'null'
    local walletEffects = effects or '[]'
    local rankingPayload = ranking or ranking_json()
    return '{"decisionId":"' .. commandId .. ':decision","commandId":"' .. commandId .. '","requestHash":"' .. requestHash
            .. '","auctionWindowId":' .. auctionWindowId .. ',"decisionVersion":' .. version
            .. ',"previousVersion":' .. previousVersion .. ',"campaignId":' .. campaignId
            .. ',"bidderUserId":' .. bidderUserId .. ',"postId":' .. postId
            .. ',"resourceType":"' .. resourceType .. '","type":"' .. decisionType
            .. '","accepted":' .. tostring(accepted) .. ',"rejectionReason":' .. rejection
            .. ',"bidAmount":' .. bidAmount .. ',"ranking":' .. rankingPayload
            .. ',"walletEffects":' .. walletEffects .. ',"decidedAtEpochMs":' .. nowEpochMs .. '}'
end

local replayHash = redis.call('HGET', commandsKey, commandId .. ':hash')
if replayHash then
    if replayHash ~= requestHash then
        return decision_json(false, 'BID_REJECTED', 'IDEMPOTENCY_CONFLICT')
    end
    return redis.call('HGET', commandsKey, commandId .. ':decision')
end

if windowStatus ~= 'OPEN' then
    local decision = decision_json(false, 'BID_REJECTED', 'WINDOW_CLOSED')
    redis.call('HSET', commandsKey, commandId .. ':hash', requestHash, commandId .. ':decision', decision)
    return decision
end

if bidAmount < reservePrice then
    local decision = decision_json(false, 'BID_REJECTED', 'BELOW_RESERVE')
    redis.call('HSET', commandsKey, commandId .. ':hash', requestHash, commandId .. ':decision', decision)
    return decision
end

local existingBidAmount = tonumber(redis.call('HGET', campaignKey, 'bidAmount') or '0')
if existingBidAmount >= bidAmount then
    local decision = decision_json(false, 'BID_REJECTED', 'BID_NOT_HIGHER')
    redis.call('HSET', commandsKey, commandId .. ':hash', requestHash, commandId .. ':decision', decision)
    return decision
end

local score = (bidAmount * 1000000000000) - nowEpochMs
local holdDelta = bidAmount - existingBidAmount
local effects = '[{"ownerUserId":' .. bidderUserId .. ',"amount":' .. holdDelta .. ',"effectType":"HOLD","businessRef":"promotion-bprime:' .. commandId .. ':hold"}]'
local decision = decision_json(true, 'BID_ACCEPTED', nil, effects,
        ranking_json(campaignId, bidderUserId, postId, bidAmount, score))
redis.call('HSET', commandsKey, commandId .. ':hash', requestHash, commandId .. ':decision', decision)
return decision
