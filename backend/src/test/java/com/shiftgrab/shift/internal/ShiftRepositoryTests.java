package com.shiftgrab.shift.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.shiftgrab.ApiTestSupport;
import com.shiftgrab.shift.Shift;

/**
 * The guards of {@link ShiftRepository#update} are what keep SG-21 correct when a claim (Sprint 3) lands between
 * the service's checks and the write. These tests change the row behind the service's back to prove it.
 */
class ShiftRepositoryTests extends ApiTestSupport {

    @Autowired
    ShiftRepository repository;

    @Test
    @DisplayName("Stale version: no row is updated")
    void rejectsStaleVersion() {
        Shift read = load(insertShift("A", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 0, "OPEN"));
        jdbc.update("UPDATE shifts SET version = version + 1 WHERE id = ?", read.id());

        assertThat(repository.update(rename(read), read.version(), false)).isEmpty();
    }

    @Test
    @DisplayName("BR-09: a claim between check and write blocks a time change")
    void rejectsTimeChangeAfterConcurrentClaim() {
        Shift read = load(insertShift("A", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 0, "OPEN"));
        jdbc.update("UPDATE shifts SET booked_slots = 1 WHERE id = ?", read.id()); // concurrent claim, no version bump

        Shift moved = new Shift(read.id(), read.name(), read.startAt().plusSeconds(3600), read.endAt().plusSeconds(3600),
                read.registrationOpenAt(), read.maxSlots(), 0, read.status(), null, read.version());
        assertThat(repository.update(moved, read.version(), true)).isEmpty();
        assertThat(repository.update(rename(read), read.version(), false)).isPresent();
    }

    @Test
    @DisplayName("BR-08: a claim between check and write blocks shrinking below occupancy")
    void rejectsShrinkBelowConcurrentBookings() {
        Shift read = load(insertShift("A", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 1, "OPEN"));
        jdbc.update("UPDATE shifts SET booked_slots = 2 WHERE id = ?", read.id());

        Shift shrunk = new Shift(read.id(), read.name(), read.startAt(), read.endAt(), read.registrationOpenAt(), 1,
                1, read.status(), null, read.version());
        assertThat(repository.update(shrunk, read.version(), false)).isEmpty();
    }

    private Shift load(long id) {
        return repository.findById(id).orElseThrow();
    }

    private static Shift rename(Shift s) {
        return new Shift(s.id(), "Renamed at " + Instant.now(), s.startAt(), s.endAt(), s.registrationOpenAt(),
                s.maxSlots(), s.bookedSlots(), s.status(), s.templateId(), s.version());
    }
}
