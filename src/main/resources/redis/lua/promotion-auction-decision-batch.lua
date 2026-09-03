-- Batched promotion decision authority. All KEYS must share the same {windowId} hash tag.
-- KEYS: state, ranking, escrow, events, publication channel, wakeup, then deduplicated campaign hashes.
-- ARGV: batchCount, hotStateTtlSeconds, wakeupTtlSeconds, followed by fixed-width command items.
local stateKey = KEYS[1]
local rankingKey = KEYS[2]
local escrowKey = KEYS[3]
local eventsKey = KEYS[4]
local publicationChannel = KEYS[5]
local publicationWakeupKey = KEYS[6]

local batchCount = tonumber(ARGV[1]) or 0
local hotStateTtlSeconds = tonumber(ARGV[2]) or 0
local publicationWakeupTtlSeconds = tonumber(ARGV[3]) or 0
local ITEM_WIDTH = 10
local MAX_MONEY = 9007199254740991

local function redis_type(key)
    local value = redis.call('TYPE', key)
    if type(value) == 'table' then
        return value.ok
    end
    return value
end

local function unavailable(reason)
    return {'UNAVAILABLE', reason}
end

if batchCount <= 0 or hotStateTtlSeconds <= 0 or publicationWakeupTtlSeconds <= 0
        or #ARGV ~= 3 + batchCount * ITEM_WIDTH or #KEYS < 7 then
    return unavailable('REDIS_BATCH_INPUT_INVALID')
end

local expectedTypes = {
    {stateKey, 'hash'},
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
if keyTypes[1] ~= 'hash' or keyTypes[3] ~= 'hash' then
    return unavailable('REDIS_STATE_NOT_INITIALIZED')
end
for keyIndex = 7, #KEYS do
    local actual = redis_type(KEYS[keyIndex])
    if actual ~= 'none' and actual ~= 'hash' then
        return unavailable('REDIS_KEY_TYPE_MISMATCH')
    end
end

local stateValues = redis.call('HMGET', stateKey,
        'decisionVersion', 'windowEndAtEpochMs', 'reservePrice', 'status', 'resourceType',
        'currentPriceCents', 'winnerCampaignId', 'incrementCents', 'capPriceCents', 'bidCount',
        'winnerCommandId', 'winnerRequestHash', 'winnerAck')
for stateIndex = 1, 10 do
    if stateValues[stateIndex] == false then
        return unavailable('REDIS_STATE_INCOMPLETE')
    end
end

local stateVersion = tonumber(stateValues[1])
local windowEndAtEpochMs = tonumber(stateValues[2])
local windowStatus = stateValues[4]
local stateResourceType = stateValues[5]
local currentPriceCents = tonumber(stateValues[6])
local winnerCampaignId = stateValues[7]
local incrementCents = tonumber(stateValues[8])
local capPriceCents = tonumber(stateValues[9])
local winnerCommandId = stateValues[11] or ''
local winnerRequestHash = stateValues[12] or ''
local winnerAck = stateValues[13] or ''
if not stateVersion or not windowEndAtEpochMs or not currentPriceCents or not incrementCents
        or not capPriceCents then
    return unavailable('REDIS_STATE_INCOMPLETE')
end

local streamVersion = 0
if keyTypes[4] ~= 'none' then
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
local nowEpochMs = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

local items = {}
for itemIndex = 1, batchCount do
    local offset = 4 + (itemIndex - 1) * ITEM_WIDTH
    local campaignKeyIndex = tonumber(ARGV[offset + 9])
    if not campaignKeyIndex or campaignKeyIndex < 7 or campaignKeyIndex > #KEYS then
        return unavailable('REDIS_BATCH_INPUT_INVALID')
    end
    local item = {
        inputIndex = tonumber(ARGV[offset]),
        commandId = ARGV[offset + 1],
        requestHash = ARGV[offset + 2],
        bidderUserId = ARGV[offset + 3],
        bidAmount = tonumber(ARGV[offset + 4]) or 0,
        campaignId = ARGV[offset + 5],
        postId = ARGV[offset + 6],
        resourceType = ARGV[offset + 7],
        submittedAt = ARGV[offset + 8],
        campaignKey = KEYS[campaignKeyIndex]
    }
    if item.inputIndex == nil or item.commandId == '' or item.requestHash == '' then
        return unavailable('REDIS_BATCH_INPUT_INVALID')
    end
    items[itemIndex] = item
end

local function required_amount(price)
    local required = price + incrementCents
    if required > MAX_MONEY then
        required = MAX_MONEY
    end
    if capPriceCents > 0 and required > capPriceCents then
        required = capPriceCents
    end
    return required
end

local initialRequired = required_amount(currentPriceCents)
local selected = nil
if windowStatus == 'OPEN' and nowEpochMs < windowEndAtEpochMs then
    for _, item in ipairs(items) do
        if item.resourceType ~= stateResourceType then
            return unavailable('REDIS_STATE_MISMATCH')
        end
        local isReplay = item.commandId == winnerCommandId and item.requestHash == winnerRequestHash
        local amountValid = item.bidAmount > 0 and item.bidAmount <= MAX_MONEY
                and item.bidAmount >= initialRequired
                and (capPriceCents <= 0 or item.bidAmount <= capPriceCents)
        if not isReplay and amountValid then
            local authorizedAmount = tonumber(redis.call(
                    'HGET', escrowKey, item.campaignId .. ':authorizedAmount') or '0')
            if authorizedAmount >= item.bidAmount then
                item.authorizedAmount = authorizedAmount
                selected = item
                break
            end
        end
    end
end

local acceptedVersion = nil
local acceptedPreviousVersion = nil
local acceptedDecisionId = nil
local acceptedAtEpochMs = nil
local acceptedSubmittedAt = nil
local acceptedAuthorizedAmount = nil
local acceptedBidAmount = nil
local acceptedCampaignId = nil
local acceptedBidderUserId = nil
local acceptedPostId = nil
local acceptedRequestHash = nil
local acceptedCommandId = nil
local rankingWasMissing = keyTypes[2] == 'none'
local eventsWasMissing = keyTypes[4] == 'none'

local function encode_winner_ack()
    return table.concat({
        acceptedDecisionId,
        tostring(acceptedVersion),
        tostring(acceptedPreviousVersion),
        tostring(acceptedAtEpochMs),
        acceptedSubmittedAt,
        tostring(acceptedAuthorizedAmount),
        tostring(acceptedBidAmount),
        acceptedCampaignId,
        acceptedBidderUserId,
        acceptedPostId
    }, '\n')
end

local function decision_json(item, decisionType, accepted, reason, version, previousVersion,
                             decidedAtEpochMs, submittedAt, authorizedAmount, decisionId,
                             requiredAmount, price, winningCampaignId, nextRequiredAmount)
    local payload = {submittedAt = submittedAt}
    payload.endAtEpochMs = windowEndAtEpochMs
    if authorizedAmount then payload.authorizedAmount = authorizedAmount end
    if requiredAmount then payload.requiredAmount = requiredAmount end
    if price then payload.currentPriceCents = price end
    if winningCampaignId and winningCampaignId ~= '' then payload.winnerCampaignId = winningCampaignId end
    if nextRequiredAmount then payload.nextRequiredAmount = nextRequiredAmount end
    return cjson.encode({
        decisionId = decisionId,
        commandId = item.commandId,
        requestHash = item.requestHash,
        auctionWindowId = ARGV[4] and tostring(string.match(stateKey, '{(%d+)}')) or '',
        decisionVersion = version,
        previousVersion = previousVersion,
        campaignId = item.campaignId,
        bidderUserId = item.bidderUserId,
        postId = item.postId,
        resourceType = item.resourceType,
        type = decisionType,
        accepted = accepted,
        rejectionReason = reason,
        bidAmount = item.bidAmount,
        ranking = cjson.decode('[]'),
        walletEffects = cjson.decode('[]'),
        payload = payload,
        decidedAtEpochMs = decidedAtEpochMs
    })
end

if selected then
    acceptedVersion = stateVersion + 1
    acceptedPreviousVersion = stateVersion
    acceptedDecisionId = selected.commandId .. ':v' .. tostring(acceptedVersion)
    acceptedAtEpochMs = nowEpochMs
    acceptedSubmittedAt = selected.submittedAt
    acceptedAuthorizedAmount = selected.authorizedAmount
    acceptedBidAmount = selected.bidAmount
    acceptedCampaignId = selected.campaignId
    acceptedBidderUserId = selected.bidderUserId
    acceptedPostId = selected.postId
    acceptedRequestHash = selected.requestHash
    acceptedCommandId = selected.commandId

    currentPriceCents = selected.bidAmount
    winnerCampaignId = selected.campaignId
    winnerCommandId = selected.commandId
    winnerRequestHash = selected.requestHash
    local nextRequiredAmount = required_amount(currentPriceCents)
    local acceptedJson = decision_json(selected, 'BID_ACCEPTED', true, nil,
            acceptedVersion, acceptedPreviousVersion, nowEpochMs, selected.submittedAt,
            selected.authorizedAmount, acceptedDecisionId, nil, currentPriceCents,
            winnerCampaignId, nextRequiredAmount)
    winnerAck = encode_winner_ack()

    local campaignWasMissing = redis_type(selected.campaignKey) == 'none'
    redis.call('HSET', selected.campaignKey,
            'bidAmount', tostring(selected.bidAmount),
            'bidderUserId', selected.bidderUserId,
            'postId', selected.postId,
            'updatedAt', tostring(nowEpochMs))
    redis.call('ZADD', rankingKey, 'LT', -selected.bidAmount, selected.campaignId)
    redis.call('HSET', escrowKey, selected.campaignId .. ':currentHold', tostring(selected.bidAmount))
    redis.call('HSET', stateKey,
            'decisionVersion', tostring(acceptedVersion),
            'currentPriceCents', tostring(currentPriceCents),
            'winnerCampaignId', winnerCampaignId,
            'winnerCommandId', winnerCommandId,
            'winnerRequestHash', winnerRequestHash,
            'winnerAck', winnerAck,
            'updatedAt', tostring(nowEpochMs))
    redis.call('HINCRBY', stateKey, 'bidCount', 1)
    redis.call('XADD', eventsKey, tostring(acceptedVersion) .. '-0', 'decision', acceptedJson)
    if campaignWasMissing then redis.call('EXPIRE', selected.campaignKey, hotStateTtlSeconds) end

    local capHit = capPriceCents > 0 and selected.bidAmount >= capPriceCents
    if capHit then
        local soldVersion = acceptedVersion + 1
        local soldJson = decision_json(selected, 'AUCTION_SOLD', true, nil,
                soldVersion, acceptedVersion, nowEpochMs, selected.submittedAt, nil,
                selected.commandId .. ':v' .. tostring(soldVersion), nil, currentPriceCents,
                winnerCampaignId, nextRequiredAmount)
        local sold = cjson.decode(soldJson)
        sold.payload.winningAmount = selected.bidAmount
        sold.payload.actualEndAtEpochMs = nowEpochMs
        redis.call('HSET', stateKey, 'status', 'SOLD', 'decisionVersion', tostring(soldVersion))
        redis.call('XADD', eventsKey, tostring(soldVersion) .. '-0', 'decision', cjson.encode(sold))
        stateVersion = soldVersion
        windowStatus = 'SOLD'
    else
        stateVersion = acceptedVersion
    end

    if rankingWasMissing then redis.call('EXPIRE', rankingKey, hotStateTtlSeconds) end
    if eventsWasMissing then redis.call('EXPIRE', eventsKey, hotStateTtlSeconds) end
    local wakeupCreated = redis.call('SET', publicationWakeupKey, '1',
            'EX', publicationWakeupTtlSeconds, 'NX')
    if wakeupCreated then
        redis.call('PUBLISH', publicationChannel, tostring(stateVersion) .. '-0')
    end
end

local replay = nil
if winnerAck ~= '' then
    replay = {}
    local start = 1
    for fieldIndex = 1, 9 do
        local separator = string.find(winnerAck, '\n', start, true)
        if not separator then return unavailable('REDIS_WINNER_ACK_INVALID') end
        replay[fieldIndex] = string.sub(winnerAck, start, separator - 1)
        start = separator + 1
    end
    replay[10] = string.sub(winnerAck, start)
end

local finalRequired = required_amount(currentPriceCents)
local results = {}
for resultIndex, item in ipairs(items) do
    local outcome
    local reason = ''
    local version = stateVersion
    local previousVersion = stateVersion
    local decidedAtEpochMs = nowEpochMs
    local originalSubmittedAt = item.submittedAt
    local authorizedAmount = ''
    local decisionId = item.commandId .. ':rejected'
    local resultBidAmount = item.bidAmount

    if selected and item.commandId == acceptedCommandId and item.requestHash == acceptedRequestHash then
        outcome = item == selected and 'ACCEPTED' or 'REPLAYED_ACCEPTED'
        version = acceptedVersion
        previousVersion = acceptedPreviousVersion
        decidedAtEpochMs = acceptedAtEpochMs
        originalSubmittedAt = acceptedSubmittedAt
        authorizedAmount = tostring(acceptedAuthorizedAmount)
        decisionId = acceptedDecisionId
        resultBidAmount = acceptedBidAmount
    elseif item.commandId == winnerCommandId and item.requestHash ~= winnerRequestHash then
        outcome = 'IDEMPOTENCY_CONFLICT'
        reason = outcome
    elseif item.commandId == winnerCommandId and item.requestHash == winnerRequestHash and replay then
        outcome = 'REPLAYED_ACCEPTED'
        decisionId = replay[1]
        version = tonumber(replay[2])
        previousVersion = tonumber(replay[3])
        decidedAtEpochMs = tonumber(replay[4])
        originalSubmittedAt = replay[5]
        authorizedAmount = replay[6]
        resultBidAmount = tonumber(replay[7])
    elseif windowStatus ~= 'OPEN' or nowEpochMs >= windowEndAtEpochMs then
        outcome = 'WINDOW_CLOSED'
        reason = outcome
    elseif item.bidAmount <= 0 or item.bidAmount > MAX_MONEY
            or item.bidAmount < finalRequired
            or (capPriceCents > 0 and item.bidAmount > capPriceCents) then
        outcome = 'BID_NOT_HIGHER'
        reason = outcome
    else
        local itemAuthorized = tonumber(redis.call(
                'HGET', escrowKey, item.campaignId .. ':authorizedAmount') or '0')
        if itemAuthorized < item.bidAmount then
            outcome = 'ESCROW_INSUFFICIENT'
            reason = outcome
        else
            outcome = 'BID_NOT_HIGHER'
            reason = outcome
        end
    end
    results[resultIndex] = {
        tostring(item.inputIndex), outcome, decisionId, tostring(version), tostring(previousVersion),
        tostring(decidedAtEpochMs), originalSubmittedAt, authorizedAmount, tostring(resultBidAmount),
        reason, tostring(finalRequired), tostring(currentPriceCents)
    }
end

return {'OK', results, tostring(currentPriceCents), winnerCommandId, winnerCampaignId,
        windowStatus, tostring(windowEndAtEpochMs), tostring(stateVersion)}
