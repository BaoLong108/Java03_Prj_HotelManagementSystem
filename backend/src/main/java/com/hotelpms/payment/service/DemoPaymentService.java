package com.hotelpms.payment.service;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.payment.dto.*;
import com.hotelpms.payment.entity.*;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class DemoPaymentService {
 private final BookingRepository bookings;
 private final PaymentAttemptRepository payments;
 public DemoPaymentService(BookingRepository bookings,PaymentAttemptRepository payments){this.bookings=bookings;this.payments=payments;}
 @Transactional(noRollbackFor=ApiException.class)
 public PaymentResponse pay(DemoPaymentRequest request,Long actor) {
  var booking=bookings.findByBookingCodeForUpdate(request.bookingCode())
   .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"BOOKING_NOT_FOUND","Không tìm thấy booking"));
  if(!booking.getCustomerId().equals(actor))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Chỉ chủ booking được thanh toán");
  String key=request.idempotencyKey().strip();
  if(key.isEmpty()||key.length()>100)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY","Khóa thanh toán không hợp lệ");
  var previous=payments.findByBookingIdAndProviderAndIdempotencyKey(booking.getId(),PaymentProvider.DEMO,key);
  if(previous.isPresent())return PaymentResponse.from(previous.get(),booking);
  if(payments.existsByBookingIdAndStatus(booking.getId(),PaymentStatus.SUCCEEDED))
   throw new ApiException(HttpStatus.CONFLICT,"BOOKING_ALREADY_PAID","Booking đã được thanh toán");
  Instant now=Instant.now();
  if(booking.getStatus()!=BookingStatus.PENDING_PAYMENT)
   throw new ApiException(HttpStatus.CONFLICT,"INVALID_BOOKING_STATUS","Booking không còn chờ thanh toán");
  if(!booking.getHoldExpiresAt().isAfter(now)){
   booking.expire(now);bookings.saveAndFlush(booking);
   throw new ApiException(HttpStatus.CONFLICT,"BOOKING_EXPIRED","Đã hết hạn giữ phòng");
  }
  var payment=new PaymentAttempt(booking.getId(),PaymentProvider.DEMO,key,booking.getPayableAmount());
  if(Boolean.TRUE.equals(request.simulateFailure())){
   payment.markFailed();payments.saveAndFlush(payment);
   return PaymentResponse.from(payment,booking);
  }
  payment.markSucceeded(now);
  payments.saveAndFlush(payment);booking.confirm(now);bookings.saveAndFlush(booking);
  return PaymentResponse.from(payment,booking);
 }
}
