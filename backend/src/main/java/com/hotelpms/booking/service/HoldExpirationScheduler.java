package com.hotelpms.booking.service;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.booking.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirationScheduler.class);

    private final BookingService bookingService;

    public HoldExpirationScheduler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(fixedDelayString = "${app.booking.expiration-sweep-interval-ms:60000}")
    public void sweepExpiredHolds() {
        try {
            int swept = bookingService.sweepExpiredHolds(Instant.now());
            if (swept > 0) {
                log.info("HoldExpirationScheduler: automatically swept {} expired hold bookings", swept);
            }
        } catch (Exception e) {
            log.error("Error during hold expiration sweeping", e);
        }
    }
}
