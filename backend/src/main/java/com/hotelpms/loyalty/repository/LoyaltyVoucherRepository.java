package com.hotelpms.loyalty.repository;

import com.hotelpms.loyalty.entity.LoyaltyVoucher;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface LoyaltyVoucherRepository extends JpaRepository<LoyaltyVoucher, Long> {
    Optional<LoyaltyVoucher> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);
    Page<LoyaltyVoucher> findByCustomerIdOrderByCreatedAtDescIdDesc(Long customerId, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from LoyaltyVoucher v where v.code = :code")
    Optional<LoyaltyVoucher> lockByCode(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from LoyaltyVoucher v where v.bookingId = :bookingId and v.usedAt is null")
    Optional<LoyaltyVoucher> lockReservedForBooking(@Param("bookingId") Long bookingId);

    @Query("""
        select v.bookingId from LoyaltyVoucher v join Booking b on b.id = v.bookingId
        where v.usedAt is null and (:customerId is null or v.customerId = :customerId)
        and (b.status in (com.hotelpms.booking.entity.BookingStatus.EXPIRED, com.hotelpms.booking.entity.BookingStatus.CANCELLED)
          or (b.status = com.hotelpms.booking.entity.BookingStatus.PENDING_PAYMENT and b.holdExpiresAt <= :now))
        order by v.bookingId
        """)
    List<Long> findStaleReservationBookings(@Param("customerId") Long customerId, @Param("now") Instant now, Pageable page);
}
