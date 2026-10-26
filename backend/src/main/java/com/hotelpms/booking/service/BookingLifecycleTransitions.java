package com.hotelpms.booking.service;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** Each lifecycle transition locks and reloads one booking in its own transaction. */
@Service
public class BookingLifecycleTransitions {
 private final BookingRepository bookings;
 private final PaymentAttemptRepository payments;
 public BookingLifecycleTransitions(BookingRepository bookings,PaymentAttemptRepository payments){this.bookings=bookings;this.payments=payments;}
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public boolean expire(Long id,Instant now){
  var found=bookings.findByIdForUpdate(id);
  if(found.isEmpty())return false;
  var booking=found.get();
  if(booking.getStatus()!=BookingStatus.PENDING_PAYMENT || booking.getHoldExpiresAt().isAfter(now))return false;
  booking.expire(now);bookings.saveAndFlush(booking);return true;
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public boolean complete(Long id,Instant now){
  var found=bookings.findByIdForUpdate(id);
  if(found.isEmpty())return false;
  var booking=found.get();
  if(booking.getStatus()!=BookingStatus.CONFIRMED || booking.getEndAt().isAfter(now)
    ||!payments.existsByBookingIdAndStatus(id,PaymentStatus.SUCCEEDED))return false;
  booking.complete(now);bookings.saveAndFlush(booking);return true;
 }
}
