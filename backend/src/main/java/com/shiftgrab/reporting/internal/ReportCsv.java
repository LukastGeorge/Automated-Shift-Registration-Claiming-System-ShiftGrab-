package com.shiftgrab.reporting.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import com.shiftgrab.reporting.internal.HoursReport.Row;

/**
 * SG-51 CSV (RFC 4180). Opened directly in Excel, so: a UTF-8 byte order mark (otherwise Vietnamese names are
 * garbled), plain numbers without thousands separators, and protection against formula injection.
 */
public final class ReportCsv {

    private static final String BOM = "﻿";

    private ReportCsv() {
    }

    public static byte[] write(HoursReport report, ReportLabels labels) {
        StringBuilder csv = new StringBuilder(BOM);
        line(csv, labels.columns);
        for (Row row : report.rows()) {
            line(csv, List.of(row.fullName(), row.email(), String.valueOf(row.shifts()), row.hours().toPlainString(),
                    row.pay().toPlainString()));
        }
        line(csv, List.of(labels.total, "", String.valueOf(report.totals().shifts()),
                report.totals().hours().toPlainString(), report.totals().pay().toPlainString()));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder csv, List<String> cells) {
        csv.append(cells.stream().map(ReportCsv::cell).collect(Collectors.joining(","))).append("\r\n");
    }

    /**
     * A cell starting with = + - @ (or a tab/CR) is run as a formula by spreadsheet apps, so a staff member called
     * {@code =HYPERLINK(...)} could attack whoever opens the file. Such text gets a leading apostrophe (OWASP).
     * Numbers are produced by us and never start with these characters except negative values, which do not exist.
     */
    static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
