package com.tongji.recommendation.feed;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowedAuthorRow {
    private Long toUserId;
    private Timestamp createdAt;
}
