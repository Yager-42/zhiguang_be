package com.tongji.storage.text;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.cassandra.DataCassandraTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DataCassandraTest
@Import({CassandraTextStorageService.class, TextStorageSmokeTest.TestConfig.class})
class TextStorageSmokeTest {

    private static final String KEYSPACE = "zhiguang";
    private static final Path SCHEMA_PATH = Path.of("db", "cassandra", "init.cql");
    private static final DockerCliCassandraSupport CASSANDRA = new DockerCliCassandraSupport();

    @Autowired
    private CassandraTextStorageService textStorageService;

    @Autowired
    private PostTextRepository postTextRepository;

    @Autowired
    private CommentTextRepository commentTextRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        CASSANDRA.startIfNeeded();
        ensureSchema();
        registry.add("spring.cassandra.contact-points", CASSANDRA::getHost);
        registry.add("spring.cassandra.port", CASSANDRA::getPort);
        registry.add("spring.cassandra.local-datacenter", CASSANDRA::getLocalDatacenter);
        registry.add("spring.cassandra.keyspace-name", () -> KEYSPACE);
        registry.add("spring.cassandra.schema-action", () -> "none");
    }

    @AfterAll
    static void stopCassandra() {
        CASSANDRA.stop();
    }

    @BeforeEach
    void clearTables() {
        commentTextRepository.deleteAll();
        postTextRepository.deleteAll();
    }

    @Test
    void postSaveReadDeleteRoundTrip() {
        textStorageService.savePostText(101L, "post body", "sha-101");

        assertThat(textStorageService.getPostText(101L, null)).contains("post body");

        textStorageService.deletePostText(101L);

        Optional<String> deleted = textStorageService.getPostText(101L, null);
        assertThat(deleted).isEmpty();
    }

    @Test
    void commentBatchReadReturnsExistingRows() {
        textStorageService.saveCommentText(201L, "comment one");
        textStorageService.saveCommentText(203L, "comment three");

        Map<Long, String> texts = textStorageService.getCommentTexts(java.util.List.of(201L, 202L, 203L));

        assertThat(texts)
                .containsEntry(201L, "comment one")
                .containsEntry(203L, "comment three")
                .doesNotContainKey(202L);
    }

    private static void ensureSchema() {
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(CASSANDRA.getHost(), CASSANDRA.getPort()))
                .withLocalDatacenter(CASSANDRA.getLocalDatacenter())
                .build()) {
            loadSchemaStatements().forEach(session::execute);
        }
    }

    private static java.util.List<String> loadSchemaStatements() {
        try {
            String schema = Files.readString(SCHEMA_PATH);
            return Arrays.stream(schema.split(";"))
                    .map(String::trim)
                    .filter(statement -> !statement.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Cassandra schema from " + SCHEMA_PATH, e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }
    }

    static final class DockerCliCassandraSupport {

        private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
        private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
        private static final Pattern PORT_PATTERN = Pattern.compile("(\\d+)\\s*$");
        private static final String DEFAULT_EXTERNAL_HOST = "127.0.0.1";
        private static final int DEFAULT_EXTERNAL_PORT = 9042;

        private final String containerName = "zhiguang-cassandra-smoke-" + UUID.randomUUID().toString().replace("-", "");
        private final Function<String[], String> commandRunner;
        private final ExternalCassandraProbe externalProbe;
        private String host = "127.0.0.1";
        private int port;
        private boolean started;

        DockerCliCassandraSupport() {
            this(DockerCliCassandraSupport::runCommand, DockerCliCassandraSupport::externalCassandraReady);
        }

        DockerCliCassandraSupport(Function<String[], String> commandRunner, ExternalCassandraProbe externalProbe) {
            this.commandRunner = commandRunner;
            this.externalProbe = externalProbe;
        }

        synchronized void startIfNeeded() {
            if (started) {
                return;
            }

            if (useExternalIfReady()) {
                return;
            }

            runDocker("run",
                    "-d",
                    "--rm",
                    "--name", containerName,
                    "-e", "CASSANDRA_CLUSTER_NAME=zhiguang",
                    "-e", "CASSANDRA_DC=datacenter1",
                    "-e", "CASSANDRA_RACK=rack1",
                    "-e", "CASSANDRA_ENDPOINT_SNITCH=GossipingPropertyFileSnitch",
                    "-e", "MAX_HEAP_SIZE=512M",
                    "-e", "HEAP_NEWSIZE=128M",
                    "-p", "0:9042",
                    "cassandra:4.1");
            this.port = waitForMappedPort();
            waitUntilReady();
            this.started = true;
        }

        String getHost() {
            return host;
        }

        int getPort() {
            return port;
        }

        String getLocalDatacenter() {
            return "datacenter1";
        }

        synchronized void stop() {
            if (!started) {
                return;
            }
            try {
                runDocker("rm", "-f", containerName);
            } catch (IllegalStateException ignored) {
                // Container may already be gone if startup failed or Docker cleaned it up.
            } finally {
                started = false;
            }
        }

        private boolean useExternalIfReady() {
            String externalHost = System.getProperty("cassandra.host",
                    System.getenv().getOrDefault("CASSANDRA_HOST", DEFAULT_EXTERNAL_HOST));
            int externalPort = Integer.parseInt(System.getProperty("cassandra.port",
                    System.getenv().getOrDefault("CASSANDRA_PORT", String.valueOf(DEFAULT_EXTERNAL_PORT))));
            if (externalProbe.isReady(externalHost, externalPort, getLocalDatacenter())) {
                this.host = externalHost;
                this.port = externalPort;
                return true;
            }
            return false;
        }

        private static boolean externalCassandraReady(String host, int port, String localDatacenter) {
            if (!isReachable(host, port)) {
                return false;
            }
            try (CqlSession ignored = CqlSession.builder()
                    .addContactPoint(new InetSocketAddress(host, port))
                    .withLocalDatacenter(localDatacenter)
                    .build()) {
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        private static boolean isReachable(String host, int port) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new InetSocketAddress(host, port), 500);
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }

        private int waitForMappedPort() {
            Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                try {
                    String output = runDocker(
                            "inspect",
                            "--format",
                            "{{(index (index .NetworkSettings.Ports \"9042/tcp\") 0).HostPort}}",
                            containerName
                    ).trim();
                    Matcher matcher = PORT_PATTERN.matcher(output);
                    if (matcher.find()) {
                        return Integer.parseInt(matcher.group(1));
                    }
                } catch (IllegalStateException ignored) {
                    // Port mapping may not be visible immediately after container creation.
                }
                sleep();
            }
            throw new IllegalStateException("Timed out waiting for Cassandra port mapping");
        }

        private void waitUntilReady() {
            Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                try {
                    runDocker("exec", containerName, "cqlsh", "-e", "DESCRIBE KEYSPACES");
                    return;
                } catch (IllegalStateException ignored) {
                    sleep();
                }
            }
            throw new IllegalStateException("Timed out waiting for Cassandra to accept cqlsh connections");
        }

        private String runDocker(String... arguments) {
            String[] command = new String[arguments.length + 1];
            command[0] = "docker";
            System.arraycopy(arguments, 0, command, 1, arguments.length);
            return commandRunner.apply(command);
        }

        private static String runCommand(String... command) {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            try {
                Process process = processBuilder.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new IllegalStateException(String.join(" ", command) + " failed: " + output.trim());
                }
                return output;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to execute command: " + String.join(" ", command), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while executing command: " + String.join(" ", command), e);
            }
        }

        private static void sleep() {
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Cassandra startup", e);
            }
        }

        interface ExternalCassandraProbe {
            boolean isReady(String host, int port, String localDatacenter);
        }
    }
}
