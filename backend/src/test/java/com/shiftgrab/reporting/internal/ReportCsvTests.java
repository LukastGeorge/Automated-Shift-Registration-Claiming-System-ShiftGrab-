package com.shiftgrab.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportCsvTests {

    @Test
    @DisplayName("Every character that makes a spreadsheet evaluate a cell is neutralised")
    void formulaInjection() {
        assertThat(ReportCsv.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(ReportCsv.cell("+84 912")).isEqualTo("'+84 912");
        assertThat(ReportCsv.cell("-2")).isEqualTo("'-2");
        assertThat(ReportCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(ReportCsv.cell("\tcmd")).isEqualTo("'\tcmd");
        assertThat(ReportCsv.cell("An = Binh")).isEqualTo("An = Binh");
    }

    @Test
    @DisplayName("RFC 4180 quoting for commas, quotes and line breaks; empty for null")
    void quoting() {
        assertThat(ReportCsv.cell("Le, Binh")).isEqualTo("\"Le, Binh\"");
        assertThat(ReportCsv.cell("6\" tall")).isEqualTo("\"6\"\" tall\"");
        assertThat(ReportCsv.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(ReportCsv.cell(null)).isEmpty();
    }
}
