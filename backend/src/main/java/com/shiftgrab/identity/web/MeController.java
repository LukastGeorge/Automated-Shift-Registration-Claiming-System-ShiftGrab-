package com.shiftgrab.identity.web;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.identity.CurrentUserService;
import com.shiftgrab.identity.Role;
import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.identity.internal.UserDirectoryService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Identity")
class MeController {

    private final CurrentUserService currentUserService;

    private final UserDirectoryService directory;

    MeController(CurrentUserService currentUserService, UserDirectoryService directory) {
        this.currentUserService = currentUserService;
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Current user's profile", description = "Creates the local profile on first call (SG-10).")
    MeResponse me() {
        return MeResponse.from(currentUserService.currentUser());
    }

    @PutMapping("/preferences")
    @Operation(summary = "Update my preferences (SG-42)",
            description = "reminderOptIn: e-mail me 30 minutes before shifts open for registration (staff only).")
    MeResponse updatePreferences(@Valid @RequestBody PreferencesRequest request) {
        return MeResponse.from(directory.setReminderOptIn(request.reminderOptIn()));
    }

    record PreferencesRequest(@NotNull Boolean reminderOptIn) {
    }

    record MeResponse(long id, String fullName, String email, Role role, BigDecimal hourlyRate,
            boolean reminderOptIn) {

        static MeResponse from(UserAccount user) {
            return new MeResponse(user.id(), user.fullName(), user.email(), user.role(), user.hourlyRate(),
                    user.reminderOptIn());
        }
    }
}
