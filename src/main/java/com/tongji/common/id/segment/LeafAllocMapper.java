package com.tongji.common.id.segment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LeafAllocMapper {
    int updateMaxId(@Param("bizTag") String bizTag);

    LeafAlloc selectByBizTag(@Param("bizTag") String bizTag);
}
