package com.hotelpms.payment.vnpay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/vnpay")
class VnpayController {
    private final VnpayService service;
    VnpayController(VnpayService service) { this.service = service; }

    @GetMapping("/availability")
    Map<String, Boolean> availability() { return Map.of("available", service.available()); }

    @PostMapping("/checkout")
    VnpayService.Checkout checkout(@Valid @RequestBody VnpayService.CheckoutRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.create(request, Long.parseLong(jwt.getSubject()), http.getRemoteAddr());
    }

    @GetMapping("/ipn")
    Map<String, String> ipn(@RequestParam Map<String, String> fields, HttpServletRequest request) {
        if (request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            return Map.of("RspCode", "97", "Message", "Duplicate parameters");
        }
        try { return service.ipn(fields); }
        catch (DataIntegrityViolationException conflict) { return Map.of("RspCode", "99", "Message", "Transaction conflict"); }
    }
}
