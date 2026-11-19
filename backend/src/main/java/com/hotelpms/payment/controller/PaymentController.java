package com.hotelpms.payment.controller;
import com.hotelpms.payment.dto.*;
import com.hotelpms.payment.service.DemoPaymentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
 private final DemoPaymentService payments;
 private final com.hotelpms.payment.service.ZeroPayableSettlementService zero;
 public PaymentController(DemoPaymentService payments,com.hotelpms.payment.service.ZeroPayableSettlementService zero){this.payments=payments;this.zero=zero;}
 @PostMapping("/demo")
 public PaymentResponse demo(@Valid @RequestBody DemoPaymentRequest request,@AuthenticationPrincipal Jwt jwt){
  return payments.pay(request,Long.valueOf(jwt.getSubject()));
 }

 public record ZeroRequest(@jakarta.validation.constraints.NotBlank String bookingCode) {}
 @PostMapping("/zero")
 public PaymentResponse zero(@Valid @RequestBody ZeroRequest request,@AuthenticationPrincipal Jwt jwt){
  return zero.settle(request.bookingCode(),Long.parseLong(jwt.getSubject()));
 }
}
