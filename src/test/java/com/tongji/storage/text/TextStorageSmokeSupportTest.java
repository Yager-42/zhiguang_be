package com.tongji.storage.text;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextStorageSmokeSupportTest {

    @Test
    void usesExternalCassandraWhenItIsReady() {
        AtomicBoolean dockerCalled = new AtomicBoolean(false);
        TextStorageSmokeTest.DockerCliCassandraSupport support =
                new TextStorageSmokeTest.DockerCliCassandraSupport(command -> {
                    dockerCalled.set(true);
                    throw new AssertionError("docker should not be called");
                }, (host, port, localDatacenter) -> true);

        support.startIfNeeded();

        org.assertj.core.api.Assertions.assertThat(dockerCalled).isFalse();
        org.assertj.core.api.Assertions.assertThat(support.getHost()).isEqualTo("127.0.0.1");
        org.assertj.core.api.Assertions.assertThat(support.getPort()).isEqualTo(9042);
    }

    @Test
    void fallsBackToDockerWhenExternalCassandraIsUnavailable() {
        AtomicBoolean dockerCalled = new AtomicBoolean(false);
        TextStorageSmokeTest.DockerCliCassandraSupport support =
                new TextStorageSmokeTest.DockerCliCassandraSupport(command -> {
                    dockerCalled.set(true);
                    throw new IllegalStateException("stop after docker fallback");
                }, (host, port, localDatacenter) -> false);

        assertThatThrownBy(support::startIfNeeded)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stop after docker fallback");

        org.assertj.core.api.Assertions.assertThat(dockerCalled).isTrue();
    }
}
