package com.shiftgrab.reporting.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * SG-51 Excel file: a title with the period and time zone, a header row, one row per staff member, a bold totals
 * row, real numbers (so the manager can sum and filter) with hour and money formats. Text cells are stored as text,
 * never as formulas, so the formula-injection risk of CSV does not exist here.
 */
public final class ReportXlsx {

    private static final int FIRST_DATA_ROW = 4;

    private ReportXlsx() {
    }

    public static byte[] write(HoursReport report, ReportLabels labels) {
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(labels == ReportLabels.VI ? "Giờ công" : "Hours");
            Styles styles = new Styles(book);

            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue(labels.title + " " + labels.period(report));
            title.getCell(0).setCellStyle(styles.title);
            sheet.createRow(1).createCell(0).setCellValue(labels.timeZone + ": " + report.timeZone());
            sheet.createRow(2).createCell(0).setCellValue(labels.note);

            Row header = sheet.createRow(3);
            for (int c = 0; c < labels.columns.size(); c++) {
                header.createCell(c).setCellValue(labels.columns.get(c));
                header.getCell(c).setCellStyle(styles.header);
            }

            int r = FIRST_DATA_ROW;
            for (HoursReport.Row row : report.rows()) {
                fill(sheet.createRow(r++), row.fullName(), row.email(), row.shifts(), row.hours(), row.pay(), styles,
                        false);
            }
            fill(sheet.createRow(r), labels.total, "", report.totals().shifts(), report.totals().hours(),
                    report.totals().pay(), styles, true);

            int[] widths = { 28, 32, 10, 12, 18 };
            for (int c = 0; c < widths.length; c++) {
                sheet.setColumnWidth(c, widths[c] * 256);
            }
            sheet.createFreezePane(0, FIRST_DATA_ROW);
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void fill(Row row, String name, String email, int shifts, BigDecimal hours, BigDecimal pay,
            Styles styles, boolean total) {
        row.createCell(0).setCellValue(name);
        row.createCell(1).setCellValue(email);
        row.createCell(2).setCellValue(shifts);
        row.createCell(3).setCellValue(hours.doubleValue());
        row.createCell(4).setCellValue(pay.doubleValue());
        row.getCell(2).setCellStyle(total ? styles.totalInteger : styles.integer);
        row.getCell(3).setCellStyle(total ? styles.totalHours : styles.hours);
        row.getCell(4).setCellStyle(total ? styles.totalMoney : styles.money);
        if (total) {
            row.getCell(0).setCellStyle(styles.totalText);
        }
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle integer;
        final CellStyle hours;
        final CellStyle money;
        final CellStyle totalText;
        final CellStyle totalInteger;
        final CellStyle totalHours;
        final CellStyle totalMoney;

        Styles(Workbook book) {
            Font bold = book.createFont();
            bold.setBold(true);
            Font big = book.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            short intFormat = book.createDataFormat().getFormat("#,##0");
            short hourFormat = book.createDataFormat().getFormat("0.00");
            title = style(book, big, (short) -1);
            header = style(book, bold, (short) -1);
            integer = style(book, null, intFormat);
            hours = style(book, null, hourFormat);
            money = style(book, null, intFormat);
            totalText = style(book, bold, (short) -1);
            totalInteger = style(book, bold, intFormat);
            totalHours = style(book, bold, hourFormat);
            totalMoney = style(book, bold, intFormat);
        }

        private static CellStyle style(Workbook book, Font font, short format) {
            CellStyle style = book.createCellStyle();
            if (font != null) {
                style.setFont(font);
            }
            if (format >= 0) {
                style.setDataFormat(format);
            }
            return style;
        }
    }
}
