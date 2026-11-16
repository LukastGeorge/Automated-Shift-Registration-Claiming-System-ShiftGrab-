package com.shiftgrab.shift.web;

import java.net.URI;
import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.internal.ShiftManagementService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @GetMapping("/{id}")
    @Operation(summary = "Get one shift", description = "Staff only see open or full shifts that have not started.")
    ResponseEntity<ShiftResponse> get(@PathVariable long id) {
        Shift shift = service.get(id);
        return ResponseEntity.ok().eTag(Long.toString(shift.version())).body(ShiftResponse.from(shift));
    }

    record CreateShiftRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull OffsetDateTime startAt,
            @NotNull OffsetDateTime endAt,
            @NotNull OffsetDateTime registrationOpenAt,
            @NotNull @Min(1) @Max(500) Integer maxSlots) {
    }
}
