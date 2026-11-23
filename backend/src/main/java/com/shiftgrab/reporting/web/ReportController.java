package com.shiftgrab.reporting.web;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.reporting.internal.HoursReport;
import com.shiftgrab.reporting.internal.HoursReportService;

@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Reports")
class ReportController {

    private final HoursReportService reports;

    ReportController(HoursReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/hours")
    @Operation(summary = "Hours and pay per staff member (SG-50)",
            description = "from/to are days in the organisation time zone, both included (max 366 days). Only shifts "
                    + "that have ended count; a shift belongs to the day it starts on; pay uses the rate snapshotted "
                    + "at claim time (BR-10). Staff without shifts are listed with zeros.")
    HoursReport hours(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.hours(from, to);
    }
}
