package com.shiftgrab.registration.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.registration.internal.CancellationService;
import com.shiftgrab.registration.internal.ClaimService;
import com.shiftgrab.registration.internal.Registration;
import com.shiftgrab.registration.internal.RegistrationStatus;

@RestController
@Tag(name = "Registrations")
class RegistrationController {

    private final ClaimService claims;
    private final CancellationService cancellations;

    RegistrationController(ClaimService claims, CancellationService cancellations) {
        this.claims = claims;
        this.cancellations = cancellations;
    }

    @PostMapping("/api/v1/shifts/{shiftId}/registrations")
    @PreAuthorize("hasRole('STAFF')")
    @Operation(summary = "Claim a slot (SG-30)",
            description = "409: SHIFT_FULL, REGISTRATION_NOT_OPEN, SHIFT_OVERLAP, WEEKLY_LIMIT_EXCEEDED, "
                    + "ALREADY_REGISTERED, SHIFT_CANCELLED, SHIFT_ALREADY_STARTED. 503 with Retry-After: CLAIM_BUSY, "
                    + "LOCK_UNAVAILABLE.")
    ResponseEntity<RegistrationResponse> claim(@PathVariable long shiftId) {
        Registration registration = claims.claim(shiftId);
        return ResponseEntity.created(URI.create("/api/v1/registrations/" + registration.id()))
                .body(RegistrationResponse.from(registration));
    }

    @DeleteMapping("/api/v1/registrations/{id}")
    @PreAuthorize("hasRole('STAFF')")
    @Operation(summary = "Cancel my registration (SG-31)",
            description = "Allowed until 24 h before the shift starts (BR-06); the slot is released immediately. "
                    + "Repeating the call is harmless. 409 CANCEL_DEADLINE_PASSED.")
    ResponseEntity<Void> cancel(@PathVariable long id) {
        cancellations.cancel(id);
        return ResponseEntity.noContent().build();
    }

    record RegistrationResponse(long id, long shiftId, RegistrationStatus status, Instant startAt, Instant endAt,
            BigDecimal hourlyRateSnapshot, Instant registeredAt, Instant cancelledAt) {

        static RegistrationResponse from(Registration r) {
            return new RegistrationResponse(r.id(), r.shiftId(), r.status(), r.startAt(), r.endAt(),
                    r.hourlyRateSnapshot(), r.registeredAt(), r.cancelledAt());
        }
    }
}
