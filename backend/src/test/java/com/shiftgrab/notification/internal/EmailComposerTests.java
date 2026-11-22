package com.shiftgrab.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shiftgrab.identity.Role;
import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.notification.internal.EmailComposer.Email;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftStatus;

class EmailComposerTests {

    private final EmailComposer composer = new EmailComposer(ZoneId.of("Asia/Ho_Chi_Minh"), "https://shiftgrab.example/");
    private final UserAccount an = new UserAccount(7, "sub", "An Nguyen", "an@example.com", Role.STAFF,
            new BigDecimal("25000"), false);
    // 01:00-05:00 UTC = 08:00-12:00 in Ho Chi Minh City, on Monday 7 January 2030
    private final Shift shift = new Shift(1, "Morning bar", Instant.parse("2030-01-07T01:00:00Z"),
            Instant.parse("2030-01-07T05:00:00Z"), Instant.parse("2030-01-06T01:30:00Z"), 3, 1, ShiftStatus.OPEN, null,
            0);

    @Test
    @DisplayName("Claim e-mail: Vietnamese first, then English, times in the organisation zone, link to my shifts")
    void claimed() {
        Email email = composer.claimed(an, shift);

        assertThat(email.to()).isEqualTo("an@example.com");
        assertThat(email.subject()).isEqualTo("[ShiftGrab] Đã giành ca / Shift claimed: Morning bar");
        assertThat(email.body())
                .contains("Chào An Nguyen,", "Bạn đã giành được ca \"Morning bar\".",
                        "Thời gian: Thứ Hai, 07/01/2030, 08:00–12:00 (Asia/Ho_Chi_Minh)",
                        "Hi An Nguyen,", "When: Monday, 7 Jan 2030, 08:00–12:00",
                        "https://shiftgrab.example/my-shifts");
        assertThat(email.body().indexOf("Chào")).isLessThan(email.body().indexOf("Hi An"));
    }

    @Test
    @DisplayName("Shift cancelled by the manager: the reason is included in both languages, or left out if none")
    void shiftCancelled() {
        assertThat(composer.shiftCancelled(an, shift, "Store closed").body())
                .contains("Lý do: Store closed", "Reason: Store closed", "/shifts");
        assertThat(composer.shiftCancelled(an, shift, null).body()).doesNotContain("Lý do", "Reason");
    }

    @Test
    @DisplayName("Cancellation by the staff member and the opening reminder")
    void otherMessages() {
        assertThat(composer.cancelledByStaff(an, shift).subject()).contains("Đã hủy đăng ký / Registration cancelled");
        Email reminder = composer.registrationOpensSoon(an, shift);
        assertThat(reminder.subject()).contains("Sắp mở đăng ký / Registration opens soon");
        assertThat(reminder.body()).contains("mở đăng ký lúc 08:30", "opens at 08:30");
    }
}
