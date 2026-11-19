package com.hotelpms.crm;

import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.crm.CustomerCrm.*;
import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.payment.dto.CustomerTransaction;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/customers")
@PreAuthorize("hasRole('ADMIN')")
class AdminCustomerController {
    private final CustomerCrmService crm;

    AdminCustomerController(CustomerCrmService crm) {
        this.crm = crm;
    }

    @GetMapping
    ResponseEntity<Directory> list(@RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) LoyaltyTier tier, @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "REGISTERED") Sort sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return privateResponse(crm.directory(search, tier, active, sort, page, size));
    }

    @GetMapping("/{id}")
    ResponseEntity<Profile> profile(@PathVariable long id) {
        return privateResponse(crm.profile(id));
    }

    @GetMapping("/{id}/bookings")
    ResponseEntity<Page<Booking>> bookings(@PathVariable long id, @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return privateResponse(crm.bookings(id, status, from, to, page, size));
    }

    @GetMapping("/{id}/transactions")
    ResponseEntity<CustomerTransaction.Page> transactions(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return privateResponse(crm.transactions(id, page, size));
    }

    @GetMapping("/{id}/transactions/{attempt}")
    ResponseEntity<CustomerTransaction.Detail> transaction(@PathVariable long id, @PathVariable long attempt) {
        return privateResponse(crm.transaction(id, attempt));
    }

    @GetMapping("/{id}/loyalty")
    ResponseEntity<Page<Point>> points(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return privateResponse(crm.points(id, page, size));
    }

    @GetMapping("/{id}/vouchers")
    ResponseEntity<Page<Voucher>> vouchers(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return privateResponse(crm.vouchers(id, page, size));
    }

    @GetMapping("/{id}/check-in-requests")
    ResponseEntity<Page<CheckIn>> checkIns(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return privateResponse(crm.checkIns(id, page, size));
    }

    private static <T> ResponseEntity<T> privateResponse(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);
    }
}
