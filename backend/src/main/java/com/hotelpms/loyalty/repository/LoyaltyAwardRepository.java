package com.hotelpms.loyalty.repository;

import com.hotelpms.loyalty.dto.LoyaltyAwardResponse;
import com.hotelpms.loyalty.entity.LoyaltyAward;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoyaltyAwardRepository extends JpaRepository<LoyaltyAward, Long> {
    boolean existsByBookingId(Long bookingId);

    @Query("""
        select new com.hotelpms.loyalty.dto.LoyaltyAwardResponse(b.bookingCode, a.eligibleAmount, a.pointsAwarded, a.awardedAt)
        from LoyaltyAward a join Booking b on b.id = a.bookingId
        where a.customerId = :customerId order by a.awardedAt desc, a.id desc
        """)
    Page<LoyaltyAwardResponse> findHistory(@Param("customerId") Long customerId, Pageable pageable);

    @Query("""
        select b.id from Booking b where b.status = com.hotelpms.booking.entity.BookingStatus.COMPLETED
        and not exists (select a.id from LoyaltyAward a where a.bookingId = b.id)
        and exists (select p.id from PaymentAttempt p where p.bookingId = b.id
          and p.status = com.hotelpms.payment.entity.PaymentStatus.SUCCEEDED and p.requestedAmount = b.payableAmount)
        order by b.id
        """)
    List<Long> findUnawardedCompletedBookings(Pageable pageable);
}
