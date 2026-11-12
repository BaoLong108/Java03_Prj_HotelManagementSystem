package com.hotelpms.loyalty;

import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.loyalty.entity.LoyaltyAward;
import com.hotelpms.loyalty.repository.LoyaltyAwardRepository;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoyaltyAwardService {
    private final BookingRepository bookings;
    private final PaymentAttemptRepository payments;
    private final CustomerProfileRepository profiles;
    private final LoyaltyAwardRepository awards;
    private final LoyaltyService rules;

    public LoyaltyAwardService(BookingRepository bookings, PaymentAttemptRepository payments,
            CustomerProfileRepository profiles, LoyaltyAwardRepository awards, LoyaltyService rules) {
        this.bookings = bookings;
        this.payments = payments;
        this.profiles = profiles;
        this.awards = awards;
        this.rules = rules;
    }

    /** Lock order is always booking -> profile. Joins the completion transaction when called there. */
    @Transactional
    public boolean awardCompletedBooking(Long bookingId, Instant now) {
        var booking = bookings.findByIdForUpdate(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.COMPLETED
                || booking.getCompletedAt() == null || booking.getCompletedAt().isAfter(now)
                || booking.getEndAt().isAfter(now) || awards.existsByBookingId(bookingId)) return false;
        var payment = payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(bookingId, PaymentStatus.SUCCEEDED);
        if (payment.isEmpty() || payment.get().getSucceededAt() == null
                || payment.get().getSucceededAt().isAfter(now)
                || payment.get().getRequestedAmount().compareTo(booking.getPayableAmount()) != 0) return false;

        var profile = profiles.findByIdForUpdate(booking.getCustomerId()).orElseThrow();
        var amount = payment.get().getRequestedAmount();
        var tier = rules.calculateTierAfterSpending(profile.getEligibleSpendTotal(), amount);
        long points = rules.calculateEarnedPoints(amount);
        awards.saveAndFlush(new LoyaltyAward(bookingId, booking.getCustomerId(), amount, points, now));
        profile.applyLoyaltyAward(amount, points, tier);
        profiles.saveAndFlush(profile);
        // Never recalculate an existing booking's price snapshot after a tier change.
        return true;
    }
}
