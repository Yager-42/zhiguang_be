// 生成 Cassandra 压测种子 CQL，输出到 stdout（run.sh 管道进 cqlsh）。
// 覆盖三张表：
//   1) post_text_by_post_id —— 正文文本（详情读依赖；缺失会回源 content_url 打外网，必须灌）
//   2) feed_inbox —— 关注 Feed 推流（用户 i 关注作者 j → inbox 一条作者 j 的最新帖）
//   3) feed_author_feed —— 大V 拉流（可选，LARGE_FOLLOWER_N > 0 时灌大V的帖子）
// 帖子-作者轮转公式与 seed_posts.sql 一致：第 k 篇帖子的作者 = base + ((k-1) % USER_N) + 1。
//
// 用法: node gen_seed_cassandra.mjs [USER_N] [USER_ID_BASE] [POST_N] [POST_ID_BASE] [FOLLOW_PER_USER] [LARGE_AUTHOR_ID] [LARGE_FOLLOWER_N]

const [
  userN = 1000,
  userIdBase = 1000000,
  postN = 500,
  postIdBase = 2000000,
  followPer = 20,
  largeAuthorId = 0,
  largeFollowerN = 0,
] = process.argv.slice(2).map(Number);

const ZERO64 = '0'.repeat(64);
const out = [];
out.push('USE zhiguang;');

// 1) 正文文本
for (let i = 1; i <= postN; i++) {
  out.push(
    `INSERT INTO zhiguang.post_text_by_post_id (post_id, body, version, sha256, updated_at) ` +
    `VALUES (${postIdBase + i}, 'loadtest body ${i} ${'x'.repeat(200)}', 1, '${ZERO64}', toTimestamp(now()));`
  );
}

// 2) feed_inbox：用户 i 关注的作者 j = ((i + k - 1) % USER_N) + 1（与 seed_follow_graph.sql 同公式）
//    作者 j 的最新帖序号 = j（轮转下第 j 篇帖子的作者就是 j，且 j <= POST_N 时存在）
for (let i = 1; i <= userN; i++) {
  for (let k = 1; k <= followPer; k++) {
    const j = ((i + k - 1) % userN) + 1;
    const pid = postIdBase + j;
    if (pid > postIdBase + postN) {
      continue; // 该作者帖子超出种子范围则跳过
    }
    const ageMin = postN - j; // 与 seed_posts.sql 的 publish_time 倒序对齐
    out.push(
      `INSERT INTO zhiguang.feed_inbox (user_id, publish_ts, content_id, author_id) ` +
      `VALUES (${userIdBase + i}, toTimestamp(now()) - ${ageMin}m, ${pid}, ${userIdBase + j});`
    );
  }
}

// 3) 大V author_feed（可选）：大V = userIdBase + 1，其帖子序号 k ≡ 1 (mod USER_N)
if (largeFollowerN > 0) {
  for (let k = 1; k <= postN; k += userN) {
    const pid = postIdBase + k;
    const ageMin = postN - k;
    out.push(
      `INSERT INTO zhiguang.feed_author_feed (author_id, publish_ts, content_id) ` +
      `VALUES (${largeAuthorId}, toTimestamp(now()) - ${ageMin}m, ${pid});`
    );
  }
}

process.stdout.write(out.join('\n') + '\n');
