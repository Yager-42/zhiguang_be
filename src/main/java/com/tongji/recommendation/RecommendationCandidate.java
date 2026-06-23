package com.tongji.recommendation;

/**
 * 推荐候选项：内容 id、来源（gorse / hot）与 organic 基线分。
 * <p>{@code organicScore} 只用于保持自然召回顺序；商业推广通过固定广告位 allocation 插入，
 * 不参与自然候选排序打分。</p>
 */
public record RecommendationCandidate(long contentId, String source, double organicScore) {
}
