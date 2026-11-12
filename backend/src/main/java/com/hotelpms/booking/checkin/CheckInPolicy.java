package com.hotelpms.booking.checkin;

import static com.hotelpms.booking.service.BookingService.HOTEL_ZONE;

import com.hotelpms.booking.entity.Booking;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.shared.api.ApiException;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class CheckInPolicy {
    private final int daysBeforeArrival;

    CheckInPolicy(@Value("${app.check-in.days-before-arrival:3}") int daysBeforeArrival) {
        if (daysBeforeArrival < 0 || daysBeforeArrival > 30) {
            throw new IllegalArgumentException("app.check-in.days-before-arrival must be between 0 and 30");
        }
        this.daysBeforeArrival = daysBeforeArrival;
    }

    record Eligibility(boolean allowed, String reason, Instant opensAt, Instant closesAt) {}

    Eligibility evaluate(Booking booking, boolean paid, boolean activeRequest, Instant now) {
        var opens = booking.getStartAt().atZone(HOTEL_ZONE).toLocalDate()
                .minusDays(daysBeforeArrival).atStartOfDay(HOTEL_ZONE).toInstant();
        var closes = booking.getEndAt();
        String reason = null;
        if (booking.getStatus() != BookingStatus.CONFIRMED || !paid) {
            reason = "Yêu cầu nhận phòng chỉ áp dụng cho đặt phòng đã xác nhận và thanh toán thành công.";
        } else if (!now.isBefore(closes)) {
            reason = "Đã hết thời hạn gửi hoặc xử lý yêu cầu nhận phòng.";
        } else if (now.isBefore(opens)) {
            reason = "Chưa đến thời gian gửi yêu cầu nhận phòng.";
        } else if (activeRequest) {
            reason = "Đặt phòng đã có yêu cầu đang chờ hoặc đã duyệt.";
        }
        return new Eligibility(reason == null, reason, opens, closes);
    }

    Instant validateArrival(Booking booking, LocalDateTime localArrival, boolean early, Instant now) {
        var estimated = localArrival.atZone(HOTEL_ZONE).toInstant();
        var arrivalDate = booking.getStartAt().atZone(HOTEL_ZONE).toLocalDate();
        if (localArrival.toLocalDate().isBefore(arrivalDate) || estimated.isBefore(now) || !estimated.isBefore(booking.getEndAt())
                || (estimated.isBefore(booking.getStartAt()) && !early)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ESTIMATED_ARRIVAL",
                    "Chọn giờ đến trong kỳ lưu trú, từ thời điểm hiện tại. Chọn yêu cầu đến sớm nếu trước giờ nhận phòng.");
        }
        return estimated;
    }
}
