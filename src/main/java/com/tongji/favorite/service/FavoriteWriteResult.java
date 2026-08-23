package com.tongji.favorite.service;

/**
 * 收藏写入结果。
 *
 * @param changed 本次请求是否改变 MySQL 收藏关系
 * @param faved 请求完成后的收藏状态
 */
public record FavoriteWriteResult(boolean changed, boolean faved) {
}
