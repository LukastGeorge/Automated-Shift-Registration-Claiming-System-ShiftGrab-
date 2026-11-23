package com.shiftgrab.reporting.web;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.reporting.internal.HoursReport;
import com.shiftgrab.reporting.internal.HoursReportService;
import com.shiftgrab.reporting.internal.ReportCsv;
import com.shiftgrab.reporting.internal.ReportLabels;
import com.shiftgrab.reporting.internal.ReportXlsx;

import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Reports")
class ReportController {

    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final HoursReportService reports;

    ReportController(HoursReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/hours")
    @Operation(summary = "Hours and pay per staff member (SG-50), as JSON, CSV or XLSX (SG-51)",
            description = "from/to are days in the organisation time zone, both included (max 366 days). Only shifts "
                    + "that have ended count; a shift belongs to the day it starts on; pay uses the rate snapshotted "
                    + "at claim time (BR-10). Staff without shifts are listed with zeros. format=csv|xlsx downloads "
                    + "a file; lang=vi|en sets its column titles.")
    ResponseEntity<?> hours(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "json") @Pattern(regexp = "json|csv|xlsx", message = "must be json, csv or xlsx")
            String format,
            @RequestParam(defaultValue = "vi") @Pattern(regexp = "vi|en", message = "must be vi or en") String lang) {
        HoursReport report = reports.hours(from, to);
        ReportLabels labels = ReportLabels.of(lang);
        String name = "shiftgrab-hours-%s_%s".formatted(from, to);
        return switch (format) {
            case "csv" -> download(ReportCsv.write(report, labels), CSV, name + ".csv");
            case "xlsx" -> download(ReportXlsx.write(report, labels), XLSX, name + ".xlsx");
            default -> ResponseEntity.ok(report);
        };
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
