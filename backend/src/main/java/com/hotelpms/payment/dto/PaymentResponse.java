package com.hotelpms.payment.dto;
import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.booking.entity.Booking;
import java.math.BigDecimal;
import java.time.Instant;
public record PaymentResponse(Long paymentAttemptId,Long bookingId,String bookingCode,String provider,
 String paymentStatus,String bookingStatus,BigDecimal requestedAmount,Instant succeededAt) {
 public static PaymentResponse from(PaymentAttempt p,Booking b) {
  return new PaymentResponse(p.getId(),b.getId(),b.getBookingCode(),p.getProvider().name(),p.getStatus().name(),
   b.getStatus().name(),p.getRequestedAmount(),p.getSucceededAt());
 }
}
