# Feed Cache Guidelines

> Executable contracts for KnowPost feed cache read/write paths.

---

## Scenario: Public Feed Fragment Cache Invalidation

### 1. Scope / Trigger

- Trigger: public KnowPost feed cache maintenance, especially code under `KnowPostFeedServiceImpl` and `FeedCacheInvalidationListener`.
- The public feed read path uses local Caffeine page snapshots plus Redis fragment caches.
- Counter-change invalidation must update local page snapshots without reintroducing a Redis full-page `FeedPageResponse` model.

### 2. Signatures

- Public feed local page key: `feed:public:{size}:{page}:v{LAYOUT_VER}`
- Redis public feed id list: `feed:public:ids:{size}:{hourSlot}:{page}`
- Redis public feed has-more marker: `feed:public:ids:{size}:{hourSlot}:{page}:hasMore`
- Redis public feed item fragment: `feed:item:{postId}`
- Redis reverse index: `feed:public:index:{postId}:{hourSlot}` contains local page keys.
- Listener entry point: `FeedCacheInvalidationListener.onCounterChanged(CounterEvent event)`

### 3. Contracts

- `KnowPostFeedServiceImpl.writeCaches(...)` writes Redis id lists, has-more markers, item fragments, and reverse index entries.
- Public feed Redis storage must not write full-page `FeedPageResponse` JSON under the local page key.
- `FeedCacheInvalidationListener` may read the reverse index for the current and previous hour slots.
- `FeedCacheInvalidationListener` must update only the in-process `feedPublicCache` page snapshot when a referenced local page is present.
- `FeedCacheInvalidationListener` must preserve item-level `liked` and `faved` flags while adjusting `likeCount` or `favoriteCount`.
- The reverse index remains useful even when no Redis full-page JSON exists; do not delete an index member merely because a full-page Redis value is absent.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|-----------|-------------------|
| `CounterEvent.entityType != "knowpost"` | Listener returns without cache changes |
| `CounterEvent.metric` is neither `like` nor `fav` | Listener returns without cache changes |
| Reverse index is empty or missing | Listener returns after author counter update attempt |
| Reverse index points to no local Caffeine page | Listener leaves the index untouched |
| Local page contains the changed post | Listener updates the matching count and writes the page back to Caffeine |
| Count delta would make a count negative | Listener clamps the updated count to `0` |

### 5. Good / Base / Bad Cases

- Good: counter event finds a reverse-indexed local page, updates the matching item count, and preserves `liked`/`faved`.
- Base: reverse index exists but the local page expired; listener does not remove the reverse-index member.
- Bad: listener calls `redis.opsForValue().get(localPageKey)` or writes full-page `FeedPageResponse` JSON for public feed invalidation.

### 6. Tests Required

- Unit test proving a counter event updates a local `feedPublicCache` page without calling Redis value operations for the page.
- Unit test proving a missing Redis page JSON does not trigger reverse-index member removal.
- Unit test proving negative deltas clamp counts to `0`.
- Assertion points must include preserved `liked` and `faved` values.

### 7. Wrong vs Correct

#### Wrong

```java
String pageJson = redis.opsForValue().get(localPageKey);
if (pageJson == null) {
    redis.opsForSet().remove(indexKey, localPageKey);
    return;
}
```

#### Correct

```java
FeedPageResponse local = feedPublicCache.getIfPresent(localPageKey);
if (local != null) {
    feedPublicCache.put(localPageKey, adjustPageCounts(local, postId, metric, delta));
}
```
