package com.shiftgrab.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.shiftgrab.ShiftGrabApplication;

/**
 * SG-34 / SG-30 AC7 / NFR-01, black-box: two real backend instances (two Spring applications on two HTTP ports)
 * share one PostgreSQL and one Redis, exactly like the production deployment behind Nginx. Requests are spread
 * across both instances and released at the same moment.
 */
class MultiInstanceClaimTests {

    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.16-alpine");
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);

    static ConfigurableApplicationContext instanceA;
    static ConfigurableApplicationContext instanceB;
    static List<String> baseUrls;
    static JdbcTemplate jdbc;
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void startInfrastructureAndTwoInstances() {
        postgres.start();
        redis.start();
        instanceA = startInstance();
        instanceB = startInstance();
        baseUrls = List.of(baseUrl(instanceA), baseUrl(instanceB));
        jdbc = instanceA.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopEverything() {
        if (instanceB != null) {
            instanceB.close();
        }
        if (instanceA != null) {
            instanceA.close();
        }
        redis.stop();
        postgres.stop();
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE shift_registrations, shifts, shift_templates, audit_log, users RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO users (keycloak_sub, full_name, email, role) VALUES ('m', 'Manager', 'm@load.test', 'MANAGER')");
    }

    @Test
    @DisplayName("AC7: 100 staff on 2 instances race for 5 slots -> exactly 5 succeed, 95 get SHIFT_FULL")
    void hundredStaffFiveSlotsTwoInstances() throws Exception {
        long shiftId = shift("Flash sale", "2030-01-07T01:00:00Z", "2030-01-07T05:00:00Z", 5);

        List<Result> results = race(100, i -> claimRequest(i, "staff-" + i, shiftId));

        Map<String, Long> outcomes = count(results);
        assertThat(outcomes).containsEntry("201", 5L).containsEntry("SHIFT_FULL", 95L).hasSize(2);
        // Both instances actually served requests, including successful ones
        assertThat(results.stream().map(Result::instance).distinct()).hasSize(2);
        // The database agrees with the HTTP answers: no overbooking, counter consistent (BR-01, BR-11)
        assertThat(jdbc.queryForMap("SELECT status, booked_slots FROM shifts WHERE id = ?", shiftId))
                .containsEntry("status", "FULL").containsEntry("booked_slots", 5);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shift_registrations WHERE shift_id = ? AND status = 'CONFIRMED'",
                Integer.class, shiftId)).isEqualTo(5);
    }

    @Test
    @DisplayName("BR-04 needs layer 1: one staff fires 10 claims of 8 h in one week on 2 instances -> exactly 5 (40 h)")
    void weeklyLimitHoldsUnderParallelClaimsOfOneUser() throws Exception {
        List<Long> shifts = new ArrayList<>();
        for (int day = 0; day < 10; day++) {
            // 2030-01-07 is a Monday; ten 8-hour shifts at 00:00, 10:00 local on Mon-Fri: same ISO week, no overlap
            String start = "2030-01-%02dT%02d:00:00+07:00".formatted(7 + day / 2, day % 2 == 0 ? 0 : 10);
            String end = "2030-01-%02dT%02d:00:00+07:00".formatted(7 + day / 2, day % 2 == 0 ? 8 : 18);
            shifts.add(shift("Day " + day, start, end, 5));
        }

        List<Result> results = race(10, i -> claimRequest(i, "eager-staff", shifts.get(i)));

        Map<String, Long> outcomes = count(results);
        assertThat(outcomes).containsEntry("201", 5L).containsEntry("WEEKLY_LIMIT_EXCEEDED", 5L);
        assertThat(jdbc.queryForObject("""
                SELECT sum(extract(epoch FROM upper(time_range) - lower(time_range))) / 3600
                  FROM shift_registrations WHERE status = 'CONFIRMED'
                """, Double.class)).isEqualTo(40.0);
    }

    // ---------------------------------------------------------------- helpers

    private static ConfigurableApplicationContext startInstance() {
        // Command-line arguments: highest precedence, so they win over application.yml (unlike builder properties)
        return new SpringApplicationBuilder(ShiftGrabApplication.class, TestJwt.DecoderConfig.class)
                .run("--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--shiftgrab.lock.redis-address=redis://" + redis.getHost() + ":" + redis.getMappedPort(6379),
                        // AC7 asserts SHIFT_FULL for every loser, so nobody may give up waiting here. Since SG-81 a
                        // claim can give up at three points, not one: the Redis lock, a pooled connection (Hikari,
                        // 1 s -> SERVICE_BUSY) and the shift row (lock_timeout, 1 s -> CLAIM_BUSY). Those fail-fast
                        // budgets are production behaviour tested elsewhere (PoolExhaustionHandlingTests,
                        // ClaimRaceTests); on a slow machine they turned one of the 95 losers into a 503 here.
                        "--shiftgrab.lock.wait-time=20s",
                        "--spring.datasource.hikari.maximum-pool-size=20",
                        "--spring.datasource.hikari.connection-timeout=30000",
                        "--shiftgrab.claims.lock-timeout=20s",
                        "--shiftgrab.claims.statement-timeout=30s",
                        // This suite is about claims; e-mails and scheduled jobs are covered elsewhere.
                        "--shiftgrab.notification.enabled=false",
                        "--shiftgrab.realtime.enabled=false",
                        "--shiftgrab.scheduling.enabled=false");
    }

    private static String baseUrl(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return "http://localhost:" + port;
    }

    private static long shift(String name, String start, String end, int maxSlots) {
        return jdbc.queryForObject("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, created_by)
                VALUES (?, ?::timestamptz, ?::timestamptz, '2020-01-01Z', ?, 1) RETURNING id
                """, Long.class, name, start, end, maxSlots);
    }

    /** Even requests go to instance A, odd ones to instance B. */
    private static HttpRequest claimRequest(int index, String subject, long shiftId) {
        return HttpRequest.newBuilder(URI.create(baseUrls.get(index % 2) + "/api/v1/shifts/" + shiftId + "/registrations"))
                .header("Authorization", "Bearer " + TestJwt.staff(subject))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    /** All requests are prepared first, then released together by one latch. */
    private static List<Result> race(int n, Function<Integer, HttpRequest> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            HttpRequest request = requests.apply(i);
            int instance = i % 2;
            futures.add(pool.submit(() -> {
                go.await();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                return new Result(instance, response.statusCode(), response.body());
            }));
        }
        go.countDown();
        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) {
            results.add(f.get(90, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private static Map<String, Long> count(List<Result> results) {
        return results.stream().collect(Collectors.groupingBy(Result::outcome, Collectors.counting()));
    }

    record Result(int instance, int status, String body) {

        private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"");

        /** "201" for success, otherwise the problem's error code. */
        String outcome() {
            if (status == 201) {
                return "201";
            }
            Matcher m = CODE.matcher(body);
            return m.find() ? m.group(1) : "HTTP " + status;
        }
    }
}
