package com.hotelpms.booking.controller;

import com.hotelpms.booking.dto.BookingResponse;
import com.hotelpms.booking.dto.CreateBookingRequest;
import com.hotelpms.booking.service.BookingService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateBookingRequest request
    ) {
        Long customerId = Long.valueOf(jwt.getSubject());
        BookingResponse response = bookingService.createBooking(customerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{bookingCode}")
    public BookingResponse getBooking(
            @PathVariable String bookingCode,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication
    ) {
        Long callerUserId = Long.valueOf(jwt.getSubject());
        boolean isStaffOrAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_STAFF".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));

        return bookingService.getBookingByCode(bookingCode, callerUserId, isStaffOrAdmin);
    }

    @GetMapping
    public List<BookingResponse> getMyBookings(@AuthenticationPrincipal Jwt jwt) {
        Long customerId = Long.valueOf(jwt.getSubject());
        return bookingService.getCustomerBookings(customerId);
    }

    @PostMapping("/{bookingCode}/cancel")
    public BookingResponse cancelBooking(
            @PathVariable String bookingCode,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long callerUserId = Long.valueOf(jwt.getSubject());
        // This is the Customer write route, including accounts that also have an operational role.
        return bookingService.cancelBooking(bookingCode, callerUserId, false);
    }

    @PostMapping("/{bookingCode}/confirm")
    public BookingResponse confirmBooking(@PathVariable String bookingCode, @AuthenticationPrincipal Jwt jwt) {
        // Authorize the persisted owner before any state change. Payment verification belongs to M4-03.
        bookingService.getBookingByCode(bookingCode, Long.valueOf(jwt.getSubject()), false);
        return bookingService.confirmBooking(bookingCode);
    }

    @PostMapping("/{bookingCode}/complete")
    public BookingResponse completeBooking(
            @PathVariable String bookingCode,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication
    ) {
        Long callerUserId = Long.valueOf(jwt.getSubject());
        boolean isStaffOrAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_STAFF".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));

        return bookingService.completeBooking(bookingCode, callerUserId, isStaffOrAdmin);
    }
}
