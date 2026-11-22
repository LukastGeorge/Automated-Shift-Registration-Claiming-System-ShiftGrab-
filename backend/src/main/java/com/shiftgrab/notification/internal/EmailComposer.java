package com.shiftgrab.notification.internal;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.shift.Shift;

/**
 * Plain-text e-mails in Vietnamese first, then English (decided in Sprint 5: one bilingual message instead of a
 * per-user language setting). Times are shown in the organisation zone. Pure functions, so every text is testable.
 */
class EmailComposer {

    record Email(String to, String subject, String body) {
    }

    private static final DateTimeFormatter VI_DAY = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy",
            Locale.forLanguageTag("vi"));
    private static final DateTimeFormatter EN_DAY = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ZoneId zone;
    private final String appUrl;

    EmailComposer(ZoneId zone, String appUrl) {
        this.zone = zone;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    Email claimed(UserAccount user, Shift shift) {
        return compose(user, shift, "Đã giành ca", "Shift claimed",
                "Bạn đã giành được ca \"%s\".".formatted(shift.name()),
                "You claimed the shift \"%s\".".formatted(shift.name()),
                "Xem ca của bạn", "Your shifts", "/my-shifts");
    }

    Email cancelledByStaff(UserAccount user, Shift shift) {
        return compose(user, shift, "Đã hủy đăng ký", "Registration cancelled",
                "Bạn đã hủy đăng ký ca \"%s\". Suất đã được trả lại cho người khác.".formatted(shift.name()),
                "You cancelled your registration for \"%s\". The slot is available to others again."
                        .formatted(shift.name()),
                "Tìm ca khác", "Find another shift", "/shifts");
    }

    Email shiftCancelled(UserAccount user, Shift shift, String reason) {
        String viReason = reason == null ? "" : "\nLý do: " + reason;
        String enReason = reason == null ? "" : "\nReason: " + reason;
        return compose(user, shift, "Ca đã bị hủy", "Shift cancelled",
                "Quản lý đã hủy ca \"%s\" mà bạn đã giành.%s".formatted(shift.name(), viReason),
                "Your manager cancelled the shift \"%s\" that you held.%s".formatted(shift.name(), enReason),
                "Tìm ca khác", "Find another shift", "/shifts");
    }

    Email registrationOpensSoon(UserAccount user, Shift shift) {
        ZonedDateTime opens = shift.registrationOpenAt().atZone(zone);
        return compose(user, shift, "Sắp mở đăng ký", "Registration opens soon",
                "Ca \"%s\" mở đăng ký lúc %s. Hãy sẵn sàng giành ca!".formatted(shift.name(), TIME.format(opens)),
                "Registration for \"%s\" opens at %s. Get ready to claim it!".formatted(shift.name(),
                        TIME.format(opens)),
                "Xem ca còn trống", "Open shifts", "/shifts");
    }

    private Email compose(UserAccount user, Shift shift, String viTitle, String enTitle, String viLine,
            String enLine, String viLink, String enLink, String path) {
        ZonedDateTime start = shift.startAt().atZone(zone);
        ZonedDateTime end = shift.endAt().atZone(zone);
        String hours = TIME.format(start) + "–" + TIME.format(end);
        String subject = "[ShiftGrab] %s / %s: %s".formatted(viTitle, enTitle, shift.name());
        String body = """
                Chào %s,

                %s
                Thời gian: %s, %s (%s)
                %s: %s

                ----------------------------------------

                Hi %s,

                %s
                When: %s, %s (%s)
                %s: %s

                --
                ShiftGrab
                """.formatted(
                user.fullName(), viLine, capitalise(VI_DAY.format(start)), hours, zone, viLink, appUrl + path,
                user.fullName(), enLine, EN_DAY.format(start), hours, zone, enLink, appUrl + path);
        return new Email(user.email(), subject, body);
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
