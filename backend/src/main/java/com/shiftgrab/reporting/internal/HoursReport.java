package com.shiftgrab.reporting.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * SG-50: per staff member, the shifts worked in a period, their hours and pay (BR-10: each shift paid at the rate
 * snapshotted when it was claimed). Only shifts that have ended count; a shift belongs to the day it starts on, in
 * the organisation time zone; staff without shifts are listed with zeros.
 */
public record HoursReport(LocalDate from, LocalDate to, String timeZone, Instant generatedAt, List<Row> rows,
        Totals totals) {

    public record Row(long userId, String fullName, String email, int shifts, BigDecimal hours, BigDecimal pay) {
    }

    public record Totals(int shifts, BigDecimal hours, BigDecimal pay) {
    }
}
