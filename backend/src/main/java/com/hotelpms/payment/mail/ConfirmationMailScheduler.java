package com.hotelpms.payment.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.confirmation-mail.enabled", havingValue = "true")
class ConfirmationMailScheduler {
    private final ConfirmationMailDelivery delivery;
    ConfirmationMailScheduler(ConfirmationMailDelivery delivery) { this.delivery = delivery; }

    @Scheduled(fixedDelayString = "${app.confirmation-mail.interval-ms:60000}")
    void deliver() {
        for (int sent = 0; sent < 10 && delivery.sendNext(); sent++) { }
    }
}
