package com.tongji.recommendation;

/**
 * 推荐候选项：内容 id、来源（gorse / hot）与 organic 基线分。
 * <p>{@code organicScore} 是本地排序的 organic 基线，boost 加权在混排层叠加为 {@code organicScore + boostEffect}；
 * 不依赖外部推荐引擎给出商业分，保持推荐协议不被商业信号污染。</p>
 */
public record RecommendationCandidate(long contentId, String source, double organicScore) {
}
