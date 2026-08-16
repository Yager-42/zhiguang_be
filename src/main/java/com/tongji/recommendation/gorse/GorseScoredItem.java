package com.tongji.recommendation.gorse;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GorseScoredItem(
        @JsonProperty("Id") String id,
        @JsonProperty("Score") double score
) {
}
