package com.hotelpms.booking.checkin;

import com.hotelpms.booking.entity.Booking;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CheckInService {
    private final BookingRepository bookings;
    private final PaymentAttemptRepository payments;
    private final CheckInRequestRepository requests;
    private final CheckInPolicy policy;

    CheckInService(BookingRepository bookings, PaymentAttemptRepository payments,
            CheckInRequestRepository requests, CheckInPolicy policy) {
        this.bookings = bookings;
        this.payments = payments;
        this.requests = requests;
        this.policy = policy;
    }

    record Submission(@NotNull LocalDateTime estimatedArrival, Boolean earlyCheckInRequested, @Size(max = 500) String arrivalNote) {}
    record Review(@NotNull CheckInRequest.Decision decision, @Size(max = 300) String rejectionReason) {}
    record QueueItem(long bookingId, String bookingCode, BookingStatus bookingStatus, String roomTypeName,
                     Instant startAt, Instant endAt, int guestCount, CheckInRequest request) {}
    record QueuePage(List<QueueItem> items, int page, boolean hasNext) {}

    @Transactional
    public CheckInRequest submit(String code, long customerId, Submission submission) {
        var booking = lockOwned(code, customerId);
        var now = Instant.now();
        boolean active = requests.history(booking.getId(), now).stream().anyMatch(item ->
                item.status() == CheckInRequest.Status.PENDING || item.status() == CheckInRequest.Status.APPROVED);
        requireEligible(booking, active, now);
        boolean early = Boolean.TRUE.equals(submission.earlyCheckInRequested());
        var estimated = policy.validateArrival(booking, submission.estimatedArrival(), early, now);
        var id = requests.insert(booking.getId(), estimated, early,
                normalized(submission.arrivalNote()), now);
        return find(id, now).request();
    }

    @Transactional
    public CheckInRequest cancel(String code, long customerId, long id) {
        var booking = lockOwned(code, customerId);
        var now = Instant.now();
        var pending = requests.pending(booking.getId(), now).filter(item -> item.id() == id)
                .orElseThrow(CheckInService::changed);
        if (pending.status() != CheckInRequest.Status.PENDING
                || !requests.transition(id, CheckInRequest.Status.CANCELLED, null, null, now)) throw changed();
        return find(id, now).request();
    }

    @Transactional
    public QueueItem review(long id, long actor, Review review) {
        var locator = find(id, Instant.now());
        // Use the same booking lock as payment, cancellation and scheduled completion.
        var booking = bookings.findByIdForUpdate(locator.bookingId()).orElseThrow(CheckInService::bookingNotFound);
        var now = Instant.now();
        var current = find(id, now);
        if (current.request().status() != CheckInRequest.Status.PENDING) throw changed();
        requireEligible(booking, false, now);
        boolean rejected = review.decision() == CheckInRequest.Decision.REJECTED;
        var rejection = rejected ? normalized(review.rejectionReason()) : null;
        if (rejected && rejection == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTION_REASON_REQUIRED", "Nhập lý do từ chối yêu cầu.");
        }
        var status = rejected ? CheckInRequest.Status.REJECTED : CheckInRequest.Status.APPROVED;
        if (!requests.transition(id, status, actor, rejection, now)) throw changed();
        return find(id, now);
    }

    @Transactional(readOnly = true)
    public QueuePage list(CheckInRequest.Status status, String code, int page, int size) {
        if (page < 0 || size < 1 || size > 50 || code.length() > 30) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHECK_IN_FILTER", "Bộ lọc hoặc số trang không hợp lệ.");
        }
        var rows = requests.list(status, code.strip(), page, size, Instant.now());
        return new QueuePage(rows.stream().limit(size).toList(), page, rows.size() > size);
    }

    @Transactional(readOnly = true)
    public QueueItem detail(long id) { return find(id, Instant.now()); }

    private Booking lockOwned(String code, long customerId) {
        var booking = bookings.findByBookingCodeForUpdate(code).orElseThrow(CheckInService::bookingNotFound);
        if (!booking.getCustomerId().equals(customerId)) throw bookingNotFound();
        return booking;
    }

    private void requireEligible(Booking booking, boolean active, Instant now) {
        var eligible = policy.evaluate(booking,
                payments.existsByBookingIdAndStatus(booking.getId(), PaymentStatus.SUCCEEDED), active, now);
        if (!eligible.allowed()) throw new ApiException(HttpStatus.CONFLICT, "CHECK_IN_UNAVAILABLE", eligible.reason());
    }

    private QueueItem find(long id, Instant now) {
        return requests.find(id, now).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CHECK_IN_NOT_FOUND", "Không tìm thấy yêu cầu nhận phòng."));
    }

    private static String normalized(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    static ApiException bookingNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Không tìm thấy đơn đặt phòng.");
    }

    private static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "CHECK_IN_REQUEST_CHANGED", "Yêu cầu không còn chờ xử lý. Hãy tải lại dữ liệu.");
    }
}
