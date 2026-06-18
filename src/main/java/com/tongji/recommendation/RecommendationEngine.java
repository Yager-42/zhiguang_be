package com.tongji.recommendation;

import java.util.List;

public interface RecommendationEngine {

    List<RecommendationCandidate> recommend(long userId, int count);
}
