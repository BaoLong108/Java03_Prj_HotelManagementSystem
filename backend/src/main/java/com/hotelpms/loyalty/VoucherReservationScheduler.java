package com.hotelpms.loyalty;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name = "app.loyalty.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class VoucherReservationScheduler {
    private final VoucherReservationMaintenance maintenance;
    public VoucherReservationScheduler(VoucherReservationMaintenance maintenance) { this.maintenance = maintenance; }
    @Scheduled(fixedDelayString = "${app.loyalty.voucher-sweep-interval-ms:60000}")
    public void releaseExpired() { maintenance.releaseExpired(null, Instant.now()); }
}
