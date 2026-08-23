package com.tongji.favorite;

import com.tongji.favorite.config.FavoriteSchemaInitializer;
import com.tongji.favorite.mapper.FavoriteMapper;
import com.tongji.favorite.model.UserFavorite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏持久化 MySQL 集成测试：复合主键幂等、删除和稳定游标分页。
 */
@SpringBootTest(classes = FavoriteMysqlIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/zhiguang?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=zhiguang",
        "spring.datasource.password=zhiguang123456",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@EnabledIf("mysqlReachable")
class FavoriteMysqlIntegrationTest {
    @Autowired
    private FavoriteMapper favoriteMapper;

    @Test
    void relationIsIdempotentAndCursorOrdered() {
        long userId = uniqueId();
        long newerPostId = uniqueId();
        long olderPostId = uniqueId();
        Instant newer = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant older = newer.minusSeconds(1);

        assertThat(favoriteMapper.insertIgnore(userId, olderPostId, older)).isEqualTo(1);
        assertThat(favoriteMapper.insertIgnore(userId, newerPostId, newer)).isEqualTo(1);
        assertThat(favoriteMapper.insertIgnore(userId, newerPostId, newer)).isZero();

        List<UserFavorite> firstPage = favoriteMapper.listPage(userId, null, null, 1);
        assertThat(firstPage).extracting(UserFavorite::getPostId).containsExactly(newerPostId);
        List<UserFavorite> secondPage = favoriteMapper.listPage(userId, newer, newerPostId, 2);
        assertThat(secondPage).extracting(UserFavorite::getPostId).containsExactly(olderPostId);

        assertThat(favoriteMapper.delete(userId, newerPostId)).isEqualTo(1);
        assertThat(favoriteMapper.delete(userId, newerPostId)).isZero();
        favoriteMapper.delete(userId, olderPostId);
    }

    private static long uniqueId() {
        return (System.nanoTime() & Long.MAX_VALUE) | 1L;
    }

    static boolean mysqlReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 3306), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Configuration
    @Import(FavoriteSchemaInitializer.class)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            MybatisAutoConfiguration.class
    })
    @MapperScan(basePackageClasses = FavoriteMapper.class)
    static class TestConfig {
    }
}
