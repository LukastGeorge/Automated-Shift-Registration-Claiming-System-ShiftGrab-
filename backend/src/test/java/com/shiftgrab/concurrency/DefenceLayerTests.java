package com.shiftgrab.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.shiftgrab.TestcontainersConfiguration;
import com.shiftgrab.shift.ShiftSlots;

/**
 * ADR-0005: each layer must hold on its own, so each is attacked here with the layers above it removed.
 * No distributed lock is taken anywhere in this class.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DefenceLayerTests {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ShiftSlots shiftSlots;

    @Autowired
    TransactionTemplate transaction;

    long shiftId;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE shift_registrations, shifts, shift_templates, audit_log, users RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO users (keycloak_sub, full_name, email, role) VALUES ('m', 'M', 'm@x.test', 'MANAGER')");
        for (int i = 1; i <= 20; i++) {
            jdbc.update("INSERT INTO users (keycloak_sub, full_name, email, role) VALUES (?, ?, ?, 'STAFF')",
                    "s" + i, "S" + i, "s" + i + "@x.test");
        }
        shiftId = jdbc.queryForObject("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, created_by)
                VALUES ('A', '2030-01-07 08:00Z', '2030-01-07 12:00Z', '2020-01-01Z', 5, 1) RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("Layer 2 alone: 50 unlocked transactions reserve a 5-slot shift -> exactly 5 succeed")
    void conditionalUpdateAlonePreventsOverbooking() throws Exception {
        List<Boolean> reserved = race(50, () -> transaction.execute(tx -> shiftSlots.tryReserveSlot(shiftId)));

        assertThat(reserved.stream().filter(Boolean::booleanValue)).hasSize(5);
        assertThat(jdbc.queryForMap("SELECT status, booked_slots FROM shifts WHERE id = ?", shiftId))
                .containsEntry("status", "FULL").containsEntry("booked_slots", 5);
    }

    @Test
    @DisplayName("Layer 3 alone: 20 concurrent duplicate inserts for one user and shift -> exactly 1 row (BR-02)")
    void uniqueIndexAlonePreventsDuplicates() throws Exception {
        List<Boolean> inserted = race(20, () -> insertRegistration(shiftId, 2, "[2030-01-07 08:00Z, 2030-01-07 12:00Z)"));

        assertThat(inserted.stream().filter(Boolean::booleanValue)).hasSize(1);
    }

    @Test
    @DisplayName("Layer 3 alone: 20 concurrent overlapping shifts for one user -> exactly 1 row (BR-03)")
    void exclusionConstraintAlonePreventsOverlap() throws Exception {
        List<Long> overlapping = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            overlapping.add(jdbc.queryForObject("""
                    INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, created_by)
                    VALUES (?, '2030-01-08 08:00Z'::timestamptz + (? || ' minutes')::interval,
                               '2030-01-08 12:00Z'::timestamptz + (? || ' minutes')::interval, '2020-01-01Z', 5, 1)
                    RETURNING id
                    """, Long.class, "O" + i, i, i));
        }
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long id = overlapping.get(i);
            int minutes = i;
            attempts.add(() -> insertRegistration(id, 3, "[" + "2030-01-08 08:00Z" + ", 2030-01-08 12:00Z)",
                    minutes));
        }

        List<Boolean> inserted = raceAll(attempts);

        assertThat(inserted.stream().filter(Boolean::booleanValue)).hasSize(1);
    }

    private boolean insertRegistration(long shift, long user, String range) {
        return insertRegistration(shift, user, range, 0);
    }

    private boolean insertRegistration(long shift, long user, String range, int shiftMinutes) {
        try {
            jdbc.update("""
                    INSERT INTO shift_registrations (shift_id, user_id, time_range, hourly_rate_snapshot)
                    VALUES (?, ?, tstzrange(lower(?::tstzrange) + (? || ' minutes')::interval,
                                            upper(?::tstzrange) + (? || ' minutes')::interval, '[)'), 25000)
                    """, shift, user, range, shiftMinutes, range, shiftMinutes);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private static <T> List<T> race(int n, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(task);
        }
        return raceAll(tasks);
    }

    private static <T> List<T> raceAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
