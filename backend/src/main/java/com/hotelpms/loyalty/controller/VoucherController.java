package com.hotelpms.loyalty.controller;

import com.hotelpms.booking.dto.BookingResponse;
import com.hotelpms.loyalty.*;
import com.hotelpms.loyalty.dto.*;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class VoucherController {
    private final VoucherService vouchers;
    private final VoucherReservationMaintenance maintenance;
    public VoucherController(VoucherService vouchers, VoucherReservationMaintenance maintenance) {
        this.vouchers = vouchers;
        this.maintenance = maintenance;
    }
    @GetMapping("/customers/me/vouchers/offers")
    public List<VoucherOffer> offers() { return vouchers.offers(); }
    @GetMapping("/customers/me/vouchers")
    public VoucherPage wallet(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page) {
        return vouchers.wallet(Long.valueOf(jwt.getSubject()), page);
    }
    @PostMapping("/customers/me/vouchers/redeem")
    @ResponseStatus(HttpStatus.CREATED)
    public VoucherResponse redeem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RedeemVoucherRequest request) {
        return vouchers.redeem(Long.valueOf(jwt.getSubject()), request);
    }
    @PostMapping("/bookings/{bookingCode}/voucher")
    public BookingResponse apply(@AuthenticationPrincipal Jwt jwt, @PathVariable String bookingCode, @Valid @RequestBody ApplyVoucherRequest request) {
        Long actor = Long.valueOf(jwt.getSubject());
        maintenance.releaseExpired(actor, Instant.now());
        return vouchers.apply(bookingCode, request.code(), actor);
    }
    @DeleteMapping("/bookings/{bookingCode}/voucher")
    public BookingResponse remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String bookingCode) {
        return vouchers.remove(bookingCode, Long.valueOf(jwt.getSubject()));
    }
}
