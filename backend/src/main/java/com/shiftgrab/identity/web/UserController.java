package com.shiftgrab.identity.web;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.identity.Role;
import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.identity.internal.UserDirectoryService;
import com.shiftgrab.shared.web.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Users")
class UserController {

    private final UserDirectoryService service;

    UserController(UserDirectoryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List users", description = "Users appear after their first login. Sorted by name.")
    PageResponse<UserResponse> search(@RequestParam(required = false) Role role,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(role, page, size).map(UserResponse::from);
    }

    @PutMapping("/{id}/hourly-rate")
    @Operation(summary = "Set a user's hourly rate (SG-25)",
            description = "Applies to future claims only; past registrations keep their snapshotted rate (BR-10).")
    UserResponse setHourlyRate(@PathVariable long id, @Valid @RequestBody HourlyRateRequest request) {
        return UserResponse.from(service.setHourlyRate(id, request.hourlyRate()));
    }

    /** VND per hour, two decimals, capped to catch typing an extra zero or two. */
    record HourlyRateRequest(
            @NotNull @DecimalMin("0") @DecimalMax("10000000") @Digits(integer = 8, fraction = 2) BigDecimal hourlyRate) {
    }

    record UserResponse(long id, String fullName, String email, Role role, BigDecimal hourlyRate) {

        static UserResponse from(UserAccount user) {
            return new UserResponse(user.id(), user.fullName(), user.email(), user.role(), user.hourlyRate());
        }
    }
}
