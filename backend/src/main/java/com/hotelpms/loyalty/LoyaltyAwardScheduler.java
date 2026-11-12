package com.hotelpms.loyalty;

import com.hotelpms.loyalty.repository.LoyaltyAwardRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers completed stays from before M1-06, or an interrupted retry, in bounded batches. */
@Component
@ConditionalOnProperty(name = "app.loyalty.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LoyaltyAwardScheduler {
    private static final Logger log = LoggerFactory.getLogger(LoyaltyAwardScheduler.class);
    private final LoyaltyAwardRepository awards;
    private final LoyaltyAwardService service;

    public LoyaltyAwardScheduler(LoyaltyAwardRepository awards, LoyaltyAwardService service) {
        this.awards = awards;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.loyalty.award-sweep-interval-ms:60000}")
    public void reconcileCompletedStays() {
        for (Long bookingId : awards.findUnawardedCompletedBookings(PageRequest.of(0, 100))) {
            try {
                service.awardCompletedBooking(bookingId, Instant.now());
            } catch (RuntimeException exception) {
                log.error("Cannot award loyalty for booking {}", bookingId, exception);
            }
        }
    }
}
