package com.hotelpms.payment.mail;

import com.hotelpms.payment.service.BookingConfirmationService;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.confirmation-mail.enabled", havingValue = "true")
public class ConfirmationMailDelivery {
    private final JdbcTemplate jdbc;
    private final JavaMailSender mail;
    private final BookingConfirmationService confirmations;
    private final String from;

    ConfirmationMailDelivery(JdbcTemplate jdbc, JavaMailSender mail, BookingConfirmationService confirmations,
            @Value("${app.confirmation-mail.from}") String from) {
        this.jdbc = jdbc; this.mail = mail; this.confirmations = confirmations; this.from = from;
    }

    @Transactional
    public boolean sendNext() {
        var rows = jdbc.queryForList("""
                SELECT q.booking_id, q.attempts, b.booking_code, b.customer_id, u.email
                FROM booking_confirmation_mail q JOIN bookings b ON b.id=q.booking_id
                JOIN app_users u ON u.id=b.customer_id
                WHERE q.status<>'SENT' AND q.attempts<5 AND q.next_attempt_at<=now()
                    AND b.status IN ('CONFIRMED','COMPLETED')
                    AND EXISTS (SELECT 1 FROM payment_attempts p WHERE p.booking_id=b.id AND p.status='SUCCEEDED')
                ORDER BY q.next_attempt_at, q.booking_id LIMIT 1 FOR UPDATE OF q SKIP LOCKED
                """);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        long id = ((Number) row.get("booking_id")).longValue();
        String code = (String) row.get("booking_code");
        try {
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from); helper.setTo((String) row.get("email"));
            helper.setSubject("Hotel PMS - Xác nhận đặt phòng " + code);
            helper.setText("Đặt phòng " + code + " đã được xác nhận. Bản xác nhận và chi tiết thanh toán đính kèm.");
            helper.addAttachment("booking-" + code + ".pdf", new ByteArrayResource(confirmations.pdf(
                    code, ((Number) row.get("customer_id")).longValue(), false)), "application/pdf");
            mail.send(message);
            jdbc.update("UPDATE booking_confirmation_mail SET status='SENT',attempts=attempts+1,sent_at=now() WHERE booking_id=?", id);
        } catch (Exception error) {
            jdbc.update("""
                    UPDATE booking_confirmation_mail SET status='FAILED',attempts=attempts+1,next_attempt_at=?
                    WHERE booking_id=?
                    """, Timestamp.from(Instant.now().plusSeconds(300)), id);
        }
        return true;
    }
}
