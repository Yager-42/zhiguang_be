package com.tongji.common.id;

import com.tongji.common.id.segment.LeafAllocMapper;
import com.tongji.common.id.segment.SegmentAllocator;
import com.tongji.common.id.segment.SegmentIdGenerator;
import com.tongji.common.id.segment.SegmentIdProperties;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = IdServiceMysqlIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/zhiguang?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=zhiguang",
        "spring.datasource.password=zhiguang123456",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.configuration.map-underscore-to-camel-case=true",
        "id.snowflake.worker-id=1",
        "id.snowflake.datacenter-id=1",
        "id.segment.wait-timeout-ms=500",
        "id.segment.preload-threads=1"
})
class IdServiceMysqlIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private IdService idService;

    @org.springframework.beans.factory.annotation.Autowired
    private LeafAllocMapper leafAllocMapper;

    @Test
    void snowflakeAndSegmentPathsWorkAgainstRealMysql() {
        Set<Long> postIds = new HashSet<>();
        for (int index = 0; index < 200; index++) {
            long id = idService.nextId(IdNamespace.POST);
            assertThat(id).isPositive();
            postIds.add(id);
        }
        assertThat(postIds).hasSize(200);

        long beforeMaxId = leafAllocMapper.selectByBizTag("reconciliation_task").getMaxId();

        Set<Long> segmentIds = new HashSet<>();
        for (int index = 0; index < 1500; index++) {
            long id = idService.nextId(IdNamespace.RECONCILIATION_TASK);
            assertThat(id).isPositive();
            segmentIds.add(id);
        }
        assertThat(segmentIds).hasSize(1500);

        long afterMaxId = leafAllocMapper.selectByBizTag("reconciliation_task").getMaxId();
        assertThat(afterMaxId).isGreaterThan(beforeMaxId);
    }

    @Configuration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            MybatisAutoConfiguration.class
    })
    @ComponentScan(basePackageClasses = {
            SnowflakeProperties.class,
            SnowflakeIdGenerator.class,
            SegmentIdProperties.class,
            SegmentAllocator.class
    })
    @MapperScan(basePackageClasses = LeafAllocMapper.class)
    static class TestConfig {

        @Bean
        DefaultIdService defaultIdService(SnowflakeIdGenerator snowflakeIdGenerator,
                                          SegmentIdGenerator segmentIdGenerator) {
            return new DefaultIdService(snowflakeIdGenerator, segmentIdGenerator);
        }

        @Bean
        SegmentIdGenerator segmentIdGenerator(SegmentAllocator segmentAllocator,
                                              SegmentIdProperties segmentIdProperties) {
            return new SegmentIdGenerator(segmentAllocator, segmentIdProperties);
        }
    }
}
