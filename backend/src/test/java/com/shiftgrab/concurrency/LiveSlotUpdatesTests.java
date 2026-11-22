package com.shiftgrab.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.shiftgrab.ShiftGrabApplication;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SG-40 AC: a browser connected to instance B sees the slots change within 1 s when someone claims on instance A.
 * Two real backends, one PostgreSQL, one Redis (the channel that connects the instances).
 */
class LiveSlotUpdatesTests {

    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.16-alpine");
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);
    static final HttpClient http = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    static ConfigurableApplicationContext instanceA;
    static ConfigurableApplicationContext instanceB;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        postgres.start();
        redis.start();
        instanceA = startInstance();
        instanceB = startInstance();
        jdbc = instanceA.getBean(JdbcTemplate.class);
        jdbc.update("INSERT INTO users (keycloak_sub, full_name, email, role) VALUES ('m', 'Manager', 'm@load.test', 'MANAGER')");
    }

    @AfterAll
    static void stop() {
        if (instanceB != null) {
            instanceB.close();
        }
        if (instanceA != null) {
            instanceA.close();
        }
        redis.stop();
        postgres.stop();
    }

    @Test
    @DisplayName("Claim on instance A, subscriber on instance B sees the new count within 1 s")
    void claimOnOneInstanceIsSeenOnTheOther() throws Exception {
        long shiftId = shift("Flash sale", 3);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        StompSession session = connect(instanceB, "Bearer " + TestJwt.staff("watcher"));
        session.subscribe("/topic/shifts", collector(received));

        // Warm-up: the first claim also loads classes and opens connections on both instances.
        assertThat(claim(instanceA, "staff-1", shiftId)).isEqualTo(201);
        assertThat(nextFor(received, shiftId, Duration.ofSeconds(10)).path("bookedSlots").asInt()).isEqualTo(1);

        long started = System.nanoTime();
        assertThat(claim(instanceA, "staff-2", shiftId)).isEqualTo(201);
        JsonNode update = nextFor(received, shiftId, Duration.ofSeconds(1));
        Duration latency = Duration.ofNanos(System.nanoTime() - started);

        assertThat(update.path("bookedSlots").asInt()).isEqualTo(2);
        assertThat(update.path("availableSlots").asInt()).isEqualTo(1);
        assertThat(update.path("status").asString()).isEqualTo("OPEN");
        assertThat(update.path("observedAt").asString()).isNotBlank();
        assertThat(latency).isLessThan(Duration.ofSeconds(1));
        session.disconnect();
    }

    @Test
    @DisplayName("The last slot makes the shift FULL for everyone watching")
    void lastSlotIsBroadcastAsFull() throws Exception {
        long shiftId = shift("Last one", 1);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        StompSession session = connect(instanceA, "Bearer " + TestJwt.staff("watcher-2"));
        session.subscribe("/topic/shifts", collector(received));

        assertThat(claim(instanceB, "staff-3", shiftId)).isEqualTo(201);

        JsonNode update = nextFor(received, shiftId, Duration.ofSeconds(5));
        assertThat(update.path("status").asString()).isEqualTo("FULL");
        assertThat(update.path("availableSlots").asInt()).isZero();
        session.disconnect();
    }

    @Test
    @DisplayName("Connecting without a valid token is refused")
    void connectRequiresToken() {
        assertThatThrownBy(() -> connect(instanceA, null)).isNotNull();
        assertThatThrownBy(() -> connect(instanceA, "Bearer not-a-jwt")).isNotNull();
    }

    // ---------------------------------------------------------------- helpers

    private static ConfigurableApplicationContext startInstance() {
        return new SpringApplicationBuilder(ShiftGrabApplication.class, TestJwt.DecoderConfig.class)
                .run("--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--shiftgrab.lock.redis-address=redis://" + redis.getHost() + ":" + redis.getMappedPort(6379),
                        "--shiftgrab.notification.enabled=false",
                        "--shiftgrab.scheduling.enabled=false");
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static long shift(String name, int maxSlots) {
        return jdbc.queryForObject("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, created_by)
                VALUES (?, '2030-01-07T01:00:00Z', '2030-01-07T05:00:00Z', '2020-01-01Z', ?, 1) RETURNING id
                """, Long.class, name, maxSlots);
    }

    private static int claim(ConfigurableApplicationContext instance, String subject, long shiftId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port(instance) + "/api/v1/shifts/" + shiftId + "/registrations"))
                .header("Authorization", "Bearer " + TestJwt.staff(subject))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static StompSession connect(ConfigurableApplicationContext instance, String authorization)
            throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        if (authorization != null) {
            connectHeaders.add("Authorization", authorization);
        }
        return client.connectAsync("ws://localhost:" + port(instance) + "/ws", new WebSocketHttpHeaders(),
                        connectHeaders, new StompSessionHandlerAdapter() {
                        })
                .get(10, TimeUnit.SECONDS);
    }

    private static StompFrameHandler collector(BlockingQueue<String> queue) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((String) payload);
            }
        };
    }

    /** The next update for this shift, skipping updates of other shifts; fails after {@code timeout}. */
    private static JsonNode nextFor(BlockingQueue<String> queue, long shiftId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            String payload = left > 0 ? queue.poll(left, TimeUnit.NANOSECONDS) : null;
            if (payload == null) {
                throw new AssertionError("No update for shift " + shiftId + " within " + timeout);
            }
            JsonNode node = JSON.readTree(payload);
            if (node.path("shiftId").asLong() == shiftId) {
                return node;
            }
        }
    }
}
