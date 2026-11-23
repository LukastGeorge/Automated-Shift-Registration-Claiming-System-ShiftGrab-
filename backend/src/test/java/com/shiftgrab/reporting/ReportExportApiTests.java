package com.shiftgrab.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shiftgrab.ApiTestSupport;

/** SG-51: the same report as CSV and XLSX downloads. */
class ReportExportApiTests extends ApiTestSupport {

    @BeforeEach
    void data() {
        long an = insertUser("staff-an", "STAFF");
        long eve = insertUser("staff-eve", "STAFF");
        jdbc.update("UPDATE users SET full_name = 'Nguyễn Văn An' WHERE id = ?", an);
        // A name that a spreadsheet would run as a formula, and one with a comma
        jdbc.update("UPDATE users SET full_name = '=HYPERLINK(\"http://evil\",\"Eve, Mallory\")' WHERE id = ?", eve);
        long shiftId = insertShift("Morning", "2026-10-05T01:00:00Z", "2026-10-05T05:00:00Z", 3, 1, "OPEN");
        jdbc.update("""
                INSERT INTO shift_registrations (shift_id, user_id, status, time_range, hourly_rate_snapshot)
                VALUES (?, ?, 'CONFIRMED', tstzrange('2026-10-05T01:00:00Z', '2026-10-05T05:30:00Z'), 25000)
                """, shiftId, an);
    }

    @Test
    @DisplayName("CSV: UTF-8 with BOM for Excel, Vietnamese titles, totals row, formula injection neutralised")
    void csv() throws Exception {
        var response = mvc.perform(get("/api/v1/reports/hours").param("from", "2026-10-01").param("to", "2026-10-31")
                        .param("format", "csv").with(manager()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"shiftgrab-hours-2026-10-01_2026-10-31.csv\""))
                .andReturn().getResponse();

        byte[] bytes = response.getContentAsByteArray();
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8).substring(1);
        assertThat(csv.split("\r\n")).containsExactly(
                "Nhân viên,Email,Số ca,Số giờ,Tiền lương (VND)",
                // apostrophe against formula injection, then RFC 4180 quoting because of the comma and quotes
                "\"'=HYPERLINK(\"\"http://evil\"\",\"\"Eve, Mallory\"\")\",staff-eve@shiftgrab.test,0,0.00,0",
                "Nguyễn Văn An,staff-an@shiftgrab.test,1,4.50,112500",
                "Tổng cộng,,1,4.50,112500");
    }

    @Test
    @DisplayName("XLSX: real numbers with formats, English titles on request, bold totals row")
    void xlsx() throws Exception {
        byte[] body = mvc.perform(get("/api/v1/reports/hours").param("from", "2026-10-01").param("to", "2026-10-31")
                        .param("format", "xlsx").param("lang", "en").with(manager()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            Sheet sheet = book.getSheet("Hours");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("Hours and pay report 1 Oct 2026 – 31 Oct 2026");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Time zone: Asia/Ho_Chi_Minh");
            assertThat(sheet.getRow(3).getCell(4).getStringCellValue()).isEqualTo("Pay (VND)");
            // rows sorted by name: "=HYPERLINK..." sorts before "Nguyễn..."; stored as text, not as a formula
            assertThat(sheet.getRow(4).getCell(0).getCellType().name()).isEqualTo("STRING");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("Nguyễn Văn An");
            assertThat(sheet.getRow(5).getCell(3).getNumericCellValue()).isEqualTo(4.5);
            assertThat(sheet.getRow(5).getCell(4).getNumericCellValue()).isEqualTo(112500);
            assertThat(sheet.getRow(5).getCell(4).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(book.getFontAt(sheet.getRow(6).getCell(4).getCellStyle().getFontIndex()).getBold()).isTrue();
        }
    }

    @Test
    @DisplayName("Unknown format or language is a validation error; JSON stays the default")
    void formats() throws Exception {
        mvc.perform(get("/api/v1/reports/hours").param("from", "2026-10-01").param("to", "2026-10-31")
                .param("format", "pdf").with(manager())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/hours").param("from", "2026-10-01").param("to", "2026-10-31")
                .param("lang", "fr").with(manager())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/hours").param("from", "2026-10-01").param("to", "2026-10-31").with(manager()))
                .andExpect(header().string("Content-Type", "application/json"));
    }
}
