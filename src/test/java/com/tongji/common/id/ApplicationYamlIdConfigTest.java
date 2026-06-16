package com.tongji.common.id;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlIdConfigTest {

    @Test
    void containsSnowflakeWorkerAndDatacenterProperties() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));

        Properties properties = factory.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("id.snowflake.worker-id"))
                .isEqualTo("${SNOWFLAKE_WORKER_ID:1}");
        assertThat(properties.getProperty("id.snowflake.datacenter-id"))
                .isEqualTo("${SNOWFLAKE_DATACENTER_ID:1}");
    }
}
