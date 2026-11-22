package com.shiftgrab.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.shiftgrab.ApiTestSupport;
import com.shiftgrab.MailpitContainer;
import com.shiftgrab.shared.scheduling.EventPublicationMaintenance;

/**
 * SG-41 AC "sending failures never roll back the claim", and the e-mail is not lost either: the event publication
 * registry (transactional outbox) keeps the failed delivery and the maintenance job resubmits it.
 */
@TestPropertySource(properties = "shiftgrab.notification.enabled=true")
class NotificationOutboxTests extends ApiTestSupport {

    @MockitoSpyBean
    JavaMailSender mailSender;

    @Autowired
    MailpitContainer mailpit;

    @Autowired
    EventPublicationMaintenance maintenance;

    @Test
    @DisplayName("Mail server down: the claim succeeds, the e-mail is kept as FAILED and sent on resubmission")
    void failedDeliveryIsResubmitted() throws Exception {
        mailpit.clear();
        doThrow(new MailSendException("SMTP server unreachable")).doCallRealMethod()
                .when(mailSender).send(any(SimpleMailMessage.class));
        long shiftId = insertOpenShift("Morning bar", "2030-01-07T01:00:00Z", "2030-01-07T05:00:00Z", 3);

        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff())).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_registrations WHERE status = 'CONFIRMED'",
                Integer.class)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM event_publication", String.class)).isEqualTo("FAILED"));
        assertThat(mailpit.messages()).isEmpty();

        maintenance.resubmitFailedOlderThan(Duration.ZERO);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(mailpit.messagesTo(STAFF_SUB + "@shiftgrab.test")).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                    Integer.class)).isZero();
        });
    }
}
