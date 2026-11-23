package com.shiftgrab.reporting.internal;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Column titles and wording of exported files, in the language the manager is using (vi or en). */
public enum ReportLabels {
    VI("Báo cáo giờ công và lương", List.of("Nhân viên", "Email", "Số ca", "Số giờ", "Tiền lương (VND)"), "Tổng cộng",
            "Múi giờ", "Chỉ tính ca đã kết thúc; ca tính theo ngày bắt đầu; lương theo đơn giá lúc giành ca.",
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("vi"))),
    EN("Hours and pay report", List.of("Staff", "Email", "Shifts", "Hours", "Pay (VND)"), "Total", "Time zone",
            "Ended shifts only; a shift counts on its start day; pay at the rate when it was claimed.",
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));

    final String title;
    final List<String> columns;
    final String total;
    final String timeZone;
    final String note;
    final DateTimeFormatter date;

    ReportLabels(String title, List<String> columns, String total, String timeZone, String note,
            DateTimeFormatter date) {
        this.title = title;
        this.columns = columns;
        this.total = total;
        this.timeZone = timeZone;
        this.note = note;
        this.date = date;
    }

    public static ReportLabels of(String lang) {
        return "en".equalsIgnoreCase(lang) ? EN : VI;
    }

    String period(HoursReport report) {
        return date.format(report.from()) + " – " + date.format(report.to());
    }
}
