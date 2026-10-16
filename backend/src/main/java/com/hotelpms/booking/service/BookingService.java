package com.hotelpms.booking.service;

import com.hotelpms.auth.entity.CustomerProfile;
import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.booking.dto.BookingResponse;
import com.hotelpms.booking.dto.CreateBookingRequest;
import com.hotelpms.booking.entity.Booking;
import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    public static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_CODE_FORMAT = DateTimeFormatter.ofPattern("yyMMdd");

    private final BookingRepository bookingRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final RoomAllocationService roomAllocationService;

    public BookingService(
            BookingRepository bookingRepository,
            CustomerProfileRepository customerProfileRepository,
            RoomAllocationService roomAllocationService
    ) {
        this.bookingRepository = bookingRepository;
        this.customerProfileRepository = customerProfileRepository;
        this.roomAllocationService = roomAllocationService;
    }

    @Transactional
    public BookingResponse createBooking(Long customerId, CreateBookingRequest request) {
        // 1. Verify customer exists
        CustomerProfile profile = customerProfileRepository.findById(customerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Hồ sơ khách hàng không tồn tại"));

        // 2. Validate time period
        if (request.startAt().compareTo(request.endAt()) >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", "Thời gian nhận phòng phải trước thời gian trả phòng");
        }

        LocalDate checkInDate = request.startAt().atZone(HOTEL_ZONE).toLocalDate();
        LocalDate checkOutDate = request.endAt().atZone(HOTEL_ZONE).toLocalDate();
        long nights = ChronoUnit.DAYS.between(checkInDate, checkOutDate);
        if (nights <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", "Kỳ nghỉ phải tối thiểu là 1 đêm");
        }

        // 3. Verify room type exists and active
        var roomType = roomAllocationService.getRoomTypeInfo(request.roomTypeId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại"));

        if (!roomType.active()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROOM_TYPE_INACTIVE", "Loại phòng này hiện đang tạm dừng phục vụ");
        }

        if (request.guestCount() > roomType.maxOccupancy()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "EXCEEDS_MAX_OCCUPANCY",
                    "Số lượng khách (" + request.guestCount() + ") vượt quá sức chứa tối đa của phòng (" + roomType.maxOccupancy() + " người)"
            );
        }

        // 4. Calculate pricing snapshot
        BigDecimal baseAmount = roomType.basePricePerNight()
                .multiply(BigDecimal.valueOf(nights))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal discountRate = getDiscountRateForTier(profile.getTierCode());
        BigDecimal discountAmount = baseAmount.multiply(discountRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal payableAmount = baseAmount.subtract(discountAmount);

        // 5. Concurrency control: Find and lock an available physical room
        Instant now = Instant.now();
        Long physicalRoomId = roomAllocationService.findAndLockAvailablePhysicalRoom(
                request.roomTypeId(),
                request.startAt(),
                request.endAt(),
                now
        ).orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT,
                "ROOM_NOT_AVAILABLE",
                "Loại phòng này không còn phòng trống trong khoảng thời gian đã chọn"
        ));

        // 6. Generate booking code and 15-minute hold expiration
        String bookingCode = generateBookingCode();
        Instant holdExpiresAt = now.plus(15, ChronoUnit.MINUTES);

        // 7. Persist booking
        Booking booking = new Booking(
                bookingCode,
                customerId,
                request.roomTypeId(),
                physicalRoomId,
                request.guestCount(),
                request.startAt(),
                request.endAt(),
                holdExpiresAt,
                baseAmount,
                discountRate,
                discountAmount,
                payableAmount
        );

        Booking saved = bookingRepository.save(booking);
        return BookingResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingByCode(String bookingCode, Long callerUserId, boolean isStaffOrAdmin) {
        Booking booking = bookingRepository.findByBookingCode(bookingCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Không tìm thấy đơn đặt phòng"));

        if (!isStaffOrAdmin && !booking.getCustomerId().equals(callerUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền xem đơn đặt phòng này");
        }

        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getCustomerBookings(Long customerId) {
        return bookingRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .map(BookingResponse::from)
                .toList();
    }

    private BigDecimal getDiscountRateForTier(LoyaltyTier tier) {
        if (tier == null) return BigDecimal.ZERO;
        return switch (tier) {
            case VIP -> new BigDecimal("0.1000");
            case DIAMOND -> new BigDecimal("0.1500");
            case STANDARD -> BigDecimal.ZERO;
        };
    }

    private String generateBookingCode() {
        String datePart = LocalDate.now(HOTEL_ZONE).format(DATE_CODE_FORMAT);
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "BK" + datePart + "-" + randomPart;
    }
}
