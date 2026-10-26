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
 public PaymentController(DemoPaymentService payments){this.payments=payments;}
 @PostMapping("/demo")
 public PaymentResponse demo(@Valid @RequestBody DemoPaymentRequest request,@AuthenticationPrincipal Jwt jwt){
  return payments.pay(request,Long.valueOf(jwt.getSubject()));
 }
}
