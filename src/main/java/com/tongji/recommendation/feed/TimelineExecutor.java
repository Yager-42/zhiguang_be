package com.tongji.recommendation.feed;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
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
    private final PreparedStatement inboxInsert;
    private final PreparedStatement authorFeedInsert;

    public TimelineExecutor(CqlSession cqlSession, RelationMapper relationMapper) {
        this.cqlSession = cqlSession;
        this.relationMapper = relationMapper;
        this.inboxInsert = cqlSession.prepare("INSERT INTO zhiguang.feed_inbox (user_id, publish_ts, content_id, author_id) VALUES (?, ?, ?, ?)");
        this.authorFeedInsert = cqlSession.prepare("INSERT INTO zhiguang.feed_author_feed (author_id, publish_ts, content_id) VALUES (?, ?, ?)");
    }

    public void fanout(TimelineDispatch dispatch) {
        if (dispatch.largeAuthor()) {
            waitFor(List.of(cqlSession.executeAsync(authorFeedInsert.bind(
                    dispatch.authorId(),
                    dispatch.publishTs(),
                    dispatch.contentId()
            )).toCompletableFuture()));
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

    private void waitFor(List<CompletableFuture<?>> futures) {
        for (CompletableFuture<?> future : futures) {
            future.orTimeout(5, TimeUnit.SECONDS).join();
        }
    }
}
