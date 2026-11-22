package com.shiftgrab.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.shiftgrab.ApiTestSupport;
import com.shiftgrab.MailpitContainer;
import com.shiftgrab.MailpitContainer.Mail;

/** SG-41 against a real SMTP server (Mailpit): e-mails are sent after commit, never for a refused action. */
@TestPropertySource(properties = "shiftgrab.notification.enabled=true")
class NotificationApiTests extends ApiTestSupport {

    private static final String STAFF_MAIL = STAFF_SUB + "@shiftgrab.test";

    @Autowired
    MailpitContainer mailpit;

    @BeforeEach
    void emptyInbox() {
        mailpit.clear();
    }

    @Test
    @DisplayName("Claim, then cancel: one e-mail each, to the staff member, bilingual")
    void claimAndCancel() throws Exception {
        long shiftId = insertOpenShift("Morning bar", "2030-01-07T01:00:00Z", "2030-01-07T05:00:00Z", 3);

        String body = mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<Mail> afterClaim = awaitMails(STAFF_MAIL, 1);
        assertThat(afterClaim.getFirst().subject()).isEqualTo("[ShiftGrab] Đã giành ca / Shift claimed: Morning bar");
        assertThat(afterClaim.getFirst().text()).contains("Chào An Nguyen", "Hi An Nguyen", "08:00–12:00");

        long registrationId = Long.parseLong(body.replaceAll(".*?\"id\":(\\d+).*", "$1"));
        mvc.perform(delete("/api/v1/registrations/{id}", registrationId).with(staff()))
                .andExpect(status().isNoContent());
        assertThat(awaitMails(STAFF_MAIL, 2).getFirst().subject()).contains("Đã hủy đăng ký / Registration cancelled");
        assertAllPublicationsCompleted();
    }

    @Test
    @DisplayName("A shift cancelled by the manager notifies each staff member who held it, with the reason")
    void shiftCancelledByManager() throws Exception {
        long shiftId = insertOpenShift("Morning bar", "2030-01-07T01:00:00Z", "2030-01-07T05:00:00Z", 3);
        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff())).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(token("staff-2", "Binh Le", "STAFF")))
                .andExpect(status().isCreated());
        awaitMails(STAFF_MAIL, 1);
        mailpit.clear();

        mvc.perform(post("/api/v1/shifts/{id}/cancel", shiftId).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Store closed\"}"))
                .andExpect(status().isOk());

        assertThat(awaitMails(STAFF_MAIL, 1).getFirst().text()).contains("Lý do: Store closed", "Reason: Store closed");
        assertThat(awaitMails("staff-2@shiftgrab.test", 1).getFirst().subject()).contains("Ca đã bị hủy / Shift cancelled");
        assertAllPublicationsCompleted();
    }

    @Test
    @DisplayName("A refused claim sends nothing")
    void refusedClaimSendsNothing() throws Exception {
        long shiftId = insertShift("Full", "2030-01-07T01:00:00Z", "2030-01-07T05:00:00Z", 1, 1, "FULL");

        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff())).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class)).isZero();
        assertThat(mailpit.messages()).isEmpty();
    }

    private List<Mail> awaitMails(String to, int count) {
        return await().atMost(Duration.ofSeconds(15))
                .until(() -> mailpit.messagesTo(to), mails -> mails.size() >= count);
    }

    private void assertAllPublicationsCompleted() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Integer.class)).isZero());
    }
}
