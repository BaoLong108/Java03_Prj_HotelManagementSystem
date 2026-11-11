package com.hotelpms.payment.controller;

import com.hotelpms.payment.dto.*;
import com.hotelpms.payment.service.PaymentAttemptService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
class PaymentAttemptController {
    private final PaymentAttemptService payments;
    PaymentAttemptController(PaymentAttemptService payments) { this.payments = payments; }

    @PostMapping
    ResponseEntity<PaymentAttemptResponse> create(@Valid @RequestBody CreatePaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var result = payments.pay(request, Long.parseLong(jwt.getSubject()));
        return ResponseEntity.status(result.replay() ? 200 : 201).body(result.payment());
    }

    @GetMapping("/attempts/{id}")
    PaymentAttemptResponse find(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return payments.find(id, Long.parseLong(jwt.getSubject()));
    }
}
