package com.shiftgrab.shift.web;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.shared.web.ETags;
import com.shiftgrab.shared.web.PageResponse;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftStatus;
import com.shiftgrab.shift.internal.ShiftManagementService;
import com.shiftgrab.shift.internal.ShiftManagementService.ShiftChanges;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/shifts")
@Tag(name = "Shifts")
class ShiftController {

    private final ShiftManagementService service;

    ShiftController(ShiftManagementService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Create a shift (SG-20)",
            description = "Times accept any ISO-8601 offset and are returned in UTC. The response ETag is the version.")
    ResponseEntity<ShiftResponse> create(@Valid @RequestBody CreateShiftRequest request) {
        Shift shift = service.create(request.name(), request.startAt().toInstant(), request.endAt().toInstant(),
                request.registrationOpenAt().toInstant(), request.maxSlots());
        return ResponseEntity.created(URI.create("/api/v1/shifts/" + shift.id()))
                .eTag(Long.toString(shift.version()))
                .body(ShiftResponse.from(shift));
    }

    @GetMapping
    @Operation(summary = "Browse shifts (SG-24)",
            description = "Filters on start time [from, to) and status, sorted by start time. Staff only see "
                    + "OPEN/FULL shifts that have not started.")
    PageResponse<ShiftResponse> search(
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) Set<ShiftStatus> status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(from == null ? null : from.toInstant(), to == null ? null : to.toInstant(), status,
                page, size).map(ShiftResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one shift", description = "Staff only see open or full shifts that have not started.")
    ResponseEntity<ShiftResponse> get(@PathVariable long id) {
        Shift shift = service.get(id);
        return ResponseEntity.ok().eTag(Long.toString(shift.version())).body(ShiftResponse.from(shift));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Edit a shift (SG-21)",
            description = "Send only the fields to change, with the ETag from GET in If-Match. Start/end are frozen "
                    + "once staff have registered (BR-09); maxSlots cannot go below booked slots (BR-08).")
    ResponseEntity<ShiftResponse> update(@PathVariable long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateShiftRequest request) {
        long expectedVersion = ETags.requireVersion(ifMatch);
        Shift shift = service.update(id, expectedVersion, new ShiftChanges(request.name(),
                instant(request.startAt()), instant(request.endAt()), instant(request.registrationOpenAt()),
                request.maxSlots()));
        return ResponseEntity.ok().eTag(Long.toString(shift.version())).body(ShiftResponse.from(shift));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Cancel a shift (SG-22)",
            description = "Cancels the shift and all its registrations in one transaction (BR-07); affected staff are "
                    + "notified with the optional reason. Repeating the call is harmless.")
    ResponseEntity<ShiftResponse> cancel(@PathVariable long id,
            @Valid @RequestBody(required = false) CancelShiftRequest request) {
        Shift shift = service.cancel(id, request == null ? null : request.reason());
        return ResponseEntity.ok().eTag(Long.toString(shift.version())).body(ShiftResponse.from(shift));
    }

    record CancelShiftRequest(@Size(max = 500) String reason) {
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** All fields optional; {@code name}, when present, must not be blank. */
    record UpdateShiftRequest(
            @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            OffsetDateTime registrationOpenAt,
            @Min(1) @Max(500) Integer maxSlots) {
    }

    record CreateShiftRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull OffsetDateTime startAt,
            @NotNull OffsetDateTime endAt,
            @NotNull OffsetDateTime registrationOpenAt,
            @NotNull @Min(1) @Max(500) Integer maxSlots) {
    }
}
