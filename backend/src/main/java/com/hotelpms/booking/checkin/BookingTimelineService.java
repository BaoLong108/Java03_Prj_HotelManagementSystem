package com.hotelpms.booking.checkin;

import static com.hotelpms.booking.service.BookingService.HOTEL_ZONE;

import com.hotelpms.booking.dto.BookingResponse;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.room.dto.RoomImageResponse;
import com.hotelpms.room.repository.RoomTypeRepository;
import com.hotelpms.room.service.RoomImageService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class BookingTimelineService {
    private final BookingRepository bookings;
    private final PaymentAttemptRepository payments;
    private final RoomTypeRepository types;
    private final RoomImageService images;
    private final CheckInRequestRepository requests;
    private final CheckInPolicy policy;

    BookingTimelineService(BookingRepository bookings, PaymentAttemptRepository payments, RoomTypeRepository types,
            RoomImageService images, CheckInRequestRepository requests, CheckInPolicy policy) {
        this.bookings = bookings;
        this.payments = payments;
        this.types = types;
        this.images = images;
        this.requests = requests;
        this.policy = policy;
    }

    enum Stage {
        CREATED, PAYMENT_PENDING, PAYMENT_SUCCEEDED, CONFIRMED, CHECK_IN_REQUESTED,
        CHECK_IN_APPROVED, CHECK_IN_REJECTED, CHECK_IN_CANCELLED, COMPLETED, CANCELLED, EXPIRED
    }

    record Milestone(Stage stage, Instant at) {}
    record Payment(PaymentStatus status, PaymentProvider provider, BigDecimal amount, Instant succeededAt) {
        static Payment from(PaymentAttempt attempt) {
            return new Payment(attempt.getStatus(), attempt.getProvider(), attempt.getRequestedAmount(), attempt.getSucceededAt());
        }
    }
    record Room(String name, String coverImageUrl) {}
    record Timeline(BookingResponse booking, Room room, long nights, Payment payment, boolean confirmationPdfAvailable,
                    CheckInPolicy.Eligibility eligibility, List<CheckInRequest.CustomerView> requests, List<Milestone> milestones) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Timeline get(String code, long customerId) {
        var booking = bookings.findByBookingCodeAndCustomerId(code, customerId).orElseThrow(CheckInService::bookingNotFound);
        long bookingId = booking.getId();
        var success = payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(bookingId, PaymentStatus.SUCCEEDED);
        var payment = success.or(() -> payments.findFirstByBookingIdOrderByCreatedAtDescIdDesc(bookingId))
                .map(Payment::from).orElse(null);
        var type = types.findById(booking.getRoomTypeId()).orElseThrow();
        var cover = type.isActive() ? images.metadata(List.of(type.getId())).getOrDefault(type.getId(), List.of()).stream()
                .filter(RoomImageResponse::cover).map(RoomImageResponse::url).findFirst().orElse(null) : null;
        var now = Instant.now();
        var history = requests.history(bookingId, now);
        var milestones = new ArrayList<Milestone>();
        add(milestones, Stage.CREATED, booking.getCreatedAt());
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) add(milestones, Stage.PAYMENT_PENDING, booking.getCreatedAt());
        success.ifPresent(attempt -> add(milestones, Stage.PAYMENT_SUCCEEDED, attempt.getSucceededAt()));
        add(milestones, Stage.CONFIRMED, booking.getConfirmedAt());
        for (var request : history) {
            add(milestones, Stage.CHECK_IN_REQUESTED, request.createdAt());
            switch (request.status()) {
                case APPROVED -> add(milestones, Stage.CHECK_IN_APPROVED, request.reviewedAt());
                case REJECTED -> add(milestones, Stage.CHECK_IN_REJECTED, request.reviewedAt());
                case CANCELLED -> add(milestones, Stage.CHECK_IN_CANCELLED, request.updatedAt());
                case PENDING, INVALIDATED -> { }
            }
        }
        add(milestones, Stage.COMPLETED, booking.getCompletedAt());
        add(milestones, Stage.CANCELLED, booking.getCancelledAt());
        add(milestones, Stage.EXPIRED, booking.getExpiredAt());
        milestones.sort(Comparator.comparing(Milestone::at));
        boolean active = history.stream().anyMatch(item ->
                item.status() == CheckInRequest.Status.PENDING || item.status() == CheckInRequest.Status.APPROVED);
        long nights = ChronoUnit.DAYS.between(booking.getStartAt().atZone(HOTEL_ZONE).toLocalDate(),
                booking.getEndAt().atZone(HOTEL_ZONE).toLocalDate());
        return new Timeline(BookingResponse.from(booking), new Room(type.getName(), cover), nights, payment, success.isPresent(),
                policy.evaluate(booking, success.isPresent(), active, now), history.stream().map(CheckInRequest::forCustomer).toList(),
                List.copyOf(milestones));
    }

    private static void add(List<Milestone> milestones, Stage stage, Instant at) {
        if (at != null) milestones.add(new Milestone(stage, at));
    }
}
