package com.tongji.moderation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationDependencyContractTest {

    @Test
    void pomUsesSpringAiOpenAiCompatibleClientOnApprovedVersionLine() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("<version>3.5.10</version>");
        assertThat(pom).contains("<spring-ai.version>1.1.2</spring-ai.version>");
        assertThat(pom).contains("<artifactId>spring-ai-bom</artifactId>");
        assertThat(pom).contains("<artifactId>spring-ai-starter-model-openai</artifactId>");
        assertThat(pom).doesNotContain("spring-ai-alibaba-starter-dashscope");
        assertThat(pom).doesNotContain("2.0.0-M1.1");
    }
}
