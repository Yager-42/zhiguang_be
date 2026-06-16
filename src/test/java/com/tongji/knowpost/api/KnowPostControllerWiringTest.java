package com.tongji.knowpost.api;

import com.tongji.auth.config.AuthConfiguration;
import com.tongji.auth.config.AuthProperties;
import com.tongji.auth.token.JwtService;
import com.tongji.knowpost.manager.PublishManager;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.service.KnowPostService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KnowPostControllerWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(KnowPostService.class, () -> mock(KnowPostService.class))
            .withBean(KnowPostFeedService.class, () -> mock(KnowPostFeedService.class))
            .withBean(JwtService.class, () -> {
                AuthProperties authProperties = new AuthProperties();
                authProperties.getJwt().setIssuer("test-issuer");
                authProperties.getJwt().setPrivateKey(new ClassPathResource("keys/private.pem"));
                authProperties.getJwt().setPublicKey(new ClassPathResource("keys/public.pem"));
                AuthConfiguration authConfiguration = new AuthConfiguration(authProperties);
                return new JwtService(authConfiguration.jwtEncoder(), authConfiguration.jwtDecoder(), authProperties);
            })
            .withBean(PublishManager.class, () -> mock(PublishManager.class))
            .withBean(KnowPostController.class);

    @Test
    void controllerBeanRequiresPublishManagerDependency() {
        contextRunner.run(context -> {
            KnowPostController controller = context.getBean(KnowPostController.class);

            assertThat(controller).isNotNull();
            assertThat(context).hasSingleBean(PublishManager.class);
        });
    }
}
