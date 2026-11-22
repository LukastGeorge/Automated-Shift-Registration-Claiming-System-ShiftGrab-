package com.shiftgrab.audit.web;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.audit.internal.AuditAction;
import com.shiftgrab.audit.internal.AuditRepository;
import com.shiftgrab.audit.internal.AuditRepository.AuditEntry;
import com.shiftgrab.audit.internal.AuditRepository.AuditQuery;
import com.shiftgrab.shared.error.ValidationErrors;
import com.shiftgrab.shared.web.PageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/audit-log")
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Audit log")
class AuditLogController {

    private final AuditRepository audit;

    AuditLogController(AuditRepository audit) {
        this.audit = audit;
    }

    @GetMapping
    @Operation(summary = "Browse the audit log (SG-52)",
            description = "Newest first. Filters: actorId, action, occurred time [from, to). Details hold a summary "
                    + "of the change, e.g. {\"maxSlots\": {\"from\": 3, \"to\": 5}} for an edit.")
    PageResponse<AuditEntry> search(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        new ValidationErrors()
                .rejectIf(from != null && to != null && !to.isAfter(from), "to", "must be after from")
                .throwIfAny();
        return audit.search(new AuditQuery(actorId, action, from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant()), page, size);
    }
}
