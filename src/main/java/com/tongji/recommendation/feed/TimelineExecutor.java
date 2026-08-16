package com.tongji.recommendation.feed;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class TimelineExecutor {

    private static final int FANOUT_PAGE_SIZE = 256;

    private final CqlSession cqlSession;
    private final RelationMapper relationMapper;
    private final KnowPostMapper knowPostMapper;
    private final PreparedStatement inboxInsert;
    private final PreparedStatement authorFeedInsert;

    public TimelineExecutor(CqlSession cqlSession, RelationMapper relationMapper, KnowPostMapper knowPostMapper) {
        this.cqlSession = cqlSession;
        this.relationMapper = relationMapper;
        this.knowPostMapper = knowPostMapper;
        this.inboxInsert = cqlSession.prepare("INSERT INTO zhiguang.feed_inbox (user_id, publish_ts, content_id, author_id) VALUES (?, ?, ?, ?)");
        this.authorFeedInsert = cqlSession.prepare("INSERT INTO zhiguang.feed_author_feed (author_id, publish_ts, content_id) VALUES (?, ?, ?)");
    }

    public void fanout(TimelineDispatch dispatch) {
        // 作者时间线（feed_author_feed）对所有作者统一写入：关注流头部读取的 pull 半边
        // 依赖它给“发布后才关注”的用户提供历史内容，普通作者同样必须维护。
        waitFor(List.of(cqlSession.executeAsync(authorFeedInsert.bind(
                dispatch.authorId(),
                dispatch.publishTs(),
                dispatch.contentId()
        )).toCompletableFuture()));

        if (dispatch.largeAuthor()) {
            // 大V 只维护作者时间线，粉丝通过 pull 读取，避免全量推送
            return;
        }

        Timestamp cursorCreatedAt = null;
        Long cursorFromUserId = null;
        while (true) {
            List<FanoutFollowerRow> followers = relationMapper.listFollowersForFanout(
                    dispatch.authorId(),
                    cursorCreatedAt,
                    cursorFromUserId,
                    FANOUT_PAGE_SIZE
            );
            if (followers == null || followers.isEmpty()) {
                return;
            }

            List<CompletableFuture<?>> futures = new ArrayList<>(followers.size());
            for (FanoutFollowerRow follower : followers) {
                BoundStatement statement = inboxInsert.bind(
                        follower.getFromUserId(),
                        dispatch.publishTs(),
                        dispatch.contentId(),
                        dispatch.authorId()
                );
                futures.add(cqlSession.executeAsync(statement).toCompletableFuture());
            }
            waitFor(futures);

            FanoutFollowerRow last = followers.getLast();
            cursorCreatedAt = last.getCreatedAt();
            cursorFromUserId = last.getFromUserId();
        }
    }

    /**
     * 关注补偿：把作者近期已发布的知文回填到作者时间线（幂等 upsert，重复执行安全）。
     *
     * <p>新关注者首次拉取关注流时，通过作者时间线头部即可看到该作者的历史内容，
     * 不依赖“关注时恰好有新的发布事件”这一窗口。</p>
     *
     * @param authorId 被关注作者
     * @param limit    最多回填的最近知文数
     */
    public void backfillAuthorTimeline(long authorId, int limit) {
        List<KnowPost> posts = knowPostMapper.listRecentPublishedByCreator(authorId, Math.max(1, limit));
        if (posts == null || posts.isEmpty()) {
            return;
        }
        List<CompletableFuture<?>> futures = new ArrayList<>(posts.size());
        for (KnowPost post : posts) {
            if (post.getId() == null || post.getPublishTime() == null) {
                continue;
            }
            futures.add(cqlSession.executeAsync(authorFeedInsert.bind(
                    authorId,
                    post.getPublishTime(),
                    post.getId()
            )).toCompletableFuture());
        }
        waitFor(futures);
    }

    private void waitFor(List<CompletableFuture<?>> futures) {
        for (CompletableFuture<?> future : futures) {
            future.orTimeout(5, TimeUnit.SECONDS).join();
        }
    }
}
