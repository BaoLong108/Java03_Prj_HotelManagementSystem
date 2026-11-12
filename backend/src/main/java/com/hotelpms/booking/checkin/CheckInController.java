package com.hotelpms.booking.checkin;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CheckInController {
    private final CheckInService checkIns;
    private final BookingTimelineService timelines;

    CheckInController(CheckInService checkIns, BookingTimelineService timelines) {
        this.checkIns = checkIns;
        this.timelines = timelines;
    }

    @GetMapping("/api/v1/bookings/{code}/timeline")
    BookingTimelineService.Timeline timeline(@PathVariable String code, @AuthenticationPrincipal Jwt jwt) {
        return timelines.get(code, Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/api/v1/bookings/{code}/check-in-requests")
    @ResponseStatus(HttpStatus.CREATED)
    CheckInRequest.CustomerView submit(@PathVariable String code, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CheckInService.Submission submission) {
        return checkIns.submit(code, Long.parseLong(jwt.getSubject()), submission).forCustomer();
    }

    @PostMapping("/api/v1/bookings/{code}/check-in-requests/{id}/cancel")
    CheckInRequest.CustomerView cancel(@PathVariable String code, @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return checkIns.cancel(code, Long.parseLong(jwt.getSubject()), id).forCustomer();
    }

    @GetMapping("/api/v1/staff/check-in-requests")
    CheckInService.QueuePage list(@RequestParam(required = false) CheckInRequest.Status status,
            @RequestParam(defaultValue = "") String code, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return checkIns.list(status, code, page, size);
    }

    @GetMapping("/api/v1/staff/check-in-requests/{id}")
    CheckInService.QueueItem detail(@PathVariable long id) { return checkIns.detail(id); }

    @PostMapping("/api/v1/staff/check-in-requests/{id}/review")
    CheckInService.QueueItem review(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CheckInService.Review review) {
        return checkIns.review(id, Long.parseLong(jwt.getSubject()), review);
    }
}
