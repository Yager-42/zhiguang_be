package com.tongji.relation.api;

import com.tongji.auth.config.AuthConfiguration;
import com.tongji.auth.config.AuthProperties;
import com.tongji.auth.token.JwtService;
import com.tongji.relation.manager.RelationManager;
import com.tongji.relation.command.RelationCommandService;
import com.tongji.relation.service.RelationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RelationManagerControllerWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RelationManager.class, () -> mock(RelationManager.class))
            .withBean(RelationCommandService.class, () -> mock(RelationCommandService.class))
            .withBean(RelationService.class, () -> mock(RelationService.class))
            .withBean(JwtService.class, () -> {
                AuthProperties authProperties = new AuthProperties();
                authProperties.getJwt().setIssuer("test-issuer");
                authProperties.getJwt().setPrivateKey(new ClassPathResource("keys/private.pem"));
                authProperties.getJwt().setPublicKey(new ClassPathResource("keys/public.pem"));
                AuthConfiguration authConfiguration = new AuthConfiguration(authProperties);
                return new JwtService(authConfiguration.jwtEncoder(), authConfiguration.jwtDecoder(), authProperties);
            })
            .withBean(com.tongji.counter.service.UserCounterReader.class, () -> mock(com.tongji.counter.service.UserCounterReader.class))
            .withBean(RelationController.class);

    @Test
    void controllerBeanRequiresRelationManagerDependency() {
        contextRunner.run(context -> {
            RelationController controller = context.getBean(RelationController.class);

            assertThat(controller).isNotNull();
            assertThat(context).hasSingleBean(RelationManager.class);
        });
    }

    private static final class TestStringRedisTemplate extends org.springframework.data.redis.core.StringRedisTemplate {
        @Override
        public void afterPropertiesSet() {
        }
    }
}
