package com.hotelpms.loyalty;

import com.hotelpms.loyalty.repository.LoyaltyVoucherRepository;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Rechecks each reservation under booking -> voucher locks, including holds reclaimed by room allocation SQL. */
@Service
public class VoucherReservationMaintenance {
    private final LoyaltyVoucherRepository vouchers;
    private final VoucherService service;
    public VoucherReservationMaintenance(LoyaltyVoucherRepository vouchers, VoucherService service) {
        this.vouchers = vouchers;
        this.service = service;
    }
    public void releaseExpired(Long customerId, Instant now) {
        for (Long id : vouchers.findStaleReservationBookings(customerId, now, PageRequest.of(0, 100)))
            service.releaseExpiredReservation(id, now);
    }
}
