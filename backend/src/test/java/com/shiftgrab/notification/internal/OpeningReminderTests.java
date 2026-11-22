package com.shiftgrab.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.shiftgrab.ApiTestSupport;
import com.shiftgrab.MailpitContainer;
import com.shiftgrab.MailpitContainer.Mail;

/** SG-42: opted-in staff get one e-mail per run, 30 minutes before shifts open; each shift is reminded once. */
@TestPropertySource(properties = "shiftgrab.notification.enabled=true")
class OpeningReminderTests extends ApiTestSupport {

    private static final String AN = STAFF_SUB + "@shiftgrab.test";

    @Autowired
    OpeningReminderJob job;

    @Autowired
    MailpitContainer mailpit;

    @BeforeEach
    void emptyInbox() {
        mailpit.clear();
    }

    @Test
    @DisplayName("Opt-in via /me/preferences; one digest lists every shift opening within 30 minutes")
    void remindsOptedInStaff() throws Exception {
        optIn(staff(), true);
        optIn(token("staff-2", "Binh Le", "STAFF"), false); // did not ask for reminders
        optIn(manager(), true); // managers do not claim shifts, so they are never reminded
        long soon = shiftOpeningIn("Morning bar", Duration.ofMinutes(10));
        long alsoSoon = shiftOpeningIn("Evening bar", Duration.ofMinutes(25));
        shiftOpeningIn("Next week", Duration.ofHours(5)); // outside the 30-minute window

        assertThat(job.remindShiftsOpeningSoon()).isEqualTo(2);

        List<Mail> mails = await().atMost(Duration.ofSeconds(15)).until(mailpit::messages, m -> !m.isEmpty());
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> mailpit.messages().size() == 1);
        assertThat(mails).singleElement().satisfies(mail -> {
            assertThat(mail.to()).isEqualTo(AN);
            assertThat(mail.subject()).isEqualTo("[ShiftGrab] 2 ca sắp mở đăng ký / 2 shifts open soon");
            assertThat(mail.text()).contains("Morning bar", "Evening bar").doesNotContain("Next week");
        });
        assertThat(jdbc.queryForList("SELECT shift_id FROM shift_opening_reminders ORDER BY shift_id", Long.class))
                .containsExactly(soon, alsoSoon);
    }

    @Test
    @DisplayName("Running again (or on another instance at the same time) reminds nobody twice")
    void remindsOnce() throws Exception {
        optIn(staff(), true);
        shiftOpeningIn("Morning bar", Duration.ofMinutes(10));

        // Two "instances" run the job at the same moment, then it runs again a minute later.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> run = job::remindShiftsOpeningSoon;
        List<Future<Integer>> results = pool.invokeAll(List.of(run, run));
        pool.shutdown();
        int reminded = results.get(0).get() + results.get(1).get();

        assertThat(reminded).isEqualTo(1);
        assertThat(job.remindShiftsOpeningSoon()).isZero();
        await().atMost(Duration.ofSeconds(15)).until(() -> mailpit.messagesTo(AN).size() == 1);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> mailpit.messages().size() == 1);
    }

    @Test
    @DisplayName("A shift cancelled after the job ran is left out of the e-mail; with nothing left, none is sent")
    void skipsShiftsNoLongerOpen() throws Exception {
        optIn(staff(), true);
        long shiftId = shiftOpeningIn("Morning bar", Duration.ofMinutes(10));
        jdbc.update("UPDATE shifts SET status = 'CANCELLED' WHERE id = ?", shiftId);

        // The query only picks OPEN shifts, so the cancelled one is not even marked.
        assertThat(job.remindShiftsOpeningSoon()).isZero();
        assertThat(mailpit.messages()).isEmpty();
    }

    @Test
    @DisplayName("Opting out is remembered and returned by /me")
    void optOut() throws Exception {
        optIn(staff(), true);
        mvc.perform(put("/api/v1/me/preferences").with(staff()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderOptIn\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminderOptIn").value(false));
        mvc.perform(put("/api/v1/me/preferences").with(staff()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }

    private void optIn(org.springframework.test.web.servlet.request.RequestPostProcessor who, boolean optIn)
            throws Exception {
        mvc.perform(put("/api/v1/me/preferences").with(who).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderOptIn\": " + optIn + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminderOptIn").value(optIn));
    }

    private long shiftOpeningIn(String name, Duration inFuture) {
        long id = insertShift(name, "2030-01-07T01:00:00Z", "2030-01-07T05:00:00Z", 3, 0, "OPEN");
        jdbc.update("UPDATE shifts SET registration_open_at = now() + make_interval(secs => ?) WHERE id = ?",
                (double) inFuture.toSeconds(), id);
        return id;
    }
}
