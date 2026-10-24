package com.hotelpms.pricing.service;

import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.pricing.dto.QuoteRequest;
import com.hotelpms.pricing.dto.QuoteResponse;
import com.hotelpms.pricing.port.LoyaltyPricingPort;
import com.hotelpms.pricing.port.RoomTypePricingPort;
import com.hotelpms.pricing.port.RoomTypePricingPort.RoomTypeInfo;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Tính báo giá theo số đêm, giá loại phòng từ Room, và hạng loyalty.
 *
 * <p>Công thức (VND, {@link BigDecimal}, HALF_UP):
 * <ul>
 *   <li>{@code nights = toLocalDate(endAt) - toLocalDate(startAt)}</li>
 *   <li>{@code baseAmount = basePricePerNight × nights}</li>
 *   <li>{@code discountAmount = baseAmount × discountRate}</li>
 *   <li>{@code payableAmount = baseAmount - discountAmount}</li>
 * </ul>
 *
 * <p>Giá mỗi đêm không hardcode: luôn lấy từ {@link RoomTypePricingPort}.
 *
 * @author Nguyễn Đình Đạt
 */
@Service
public class QuoteService {

    static final int MONEY_SCALE = 2;
    static final int RATE_SCALE = 4;
    static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    static final BigDecimal RATE_STANDARD = new BigDecimal("0.0000");
    static final BigDecimal RATE_VIP = new BigDecimal("0.1000");
    static final BigDecimal RATE_DIAMOND = new BigDecimal("0.1500");

    private final RoomTypePricingPort roomTypes;
    private final LoyaltyPricingPort loyalty;

    public QuoteService(RoomTypePricingPort roomTypes, LoyaltyPricingPort loyalty) {
        this.roomTypes = roomTypes;
        this.loyalty = loyalty;
    }

    /**
     * @param request    tham số quote từ query string
     * @param customerId userId từ JWT; {@code null} nếu Guest chưa đăng nhập
     */
    public QuoteResponse quote(QuoteRequest request, Long customerId) {
        Objects.requireNonNull(request, "request must not be null");
        validateRequest(request);

        RoomTypeInfo roomType = roomTypes.findActiveById(request.roomTypeId());
        if (roomType == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại");
        }
        if (request.guestCount() > roomType.maxOccupancy()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GUEST_COUNT_EXCEEDS_CAPACITY",
                    "Số khách vượt quá sức chứa của loại phòng");
        }

        long nights = nightsBetween(request);
        if (nights < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NIGHTS", "Số đêm tối thiểu là 1");
        }

        LoyaltyTier tier = resolveTier(customerId);
        BigDecimal discountRate = discountRateOf(tier).setScale(RATE_SCALE, MONEY_ROUNDING);
        BigDecimal basePricePerNight = money(roomType.basePricePerNight());
        BigDecimal baseAmount = money(basePricePerNight.multiply(BigDecimal.valueOf(nights)));
        BigDecimal discountAmount = money(baseAmount.multiply(discountRate));
        BigDecimal payableAmount = money(baseAmount.subtract(discountAmount));

        return new QuoteResponse(
                roomType.id(),
                nights,
                basePricePerNight,
                baseAmount,
                discountRate,
                discountAmount,
                payableAmount,
                tier.name());
    }

    private void validateRequest(QuoteRequest request) {
        if (request.roomTypeId() == null || request.roomTypeId() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "roomTypeId phải là số dương");
        }
        if (request.startAt() == null || request.endAt() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "startAt và endAt không được để trống");
        }
        if (!request.endAt().isAfter(request.startAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "startAt phải nhỏ hơn endAt");
        }
        if (request.guestCount() < 1 || request.guestCount() > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "guestCount phải từ 1 đến 20");
        }
    }

    private long nightsBetween(QuoteRequest request) {
        return ChronoUnit.DAYS.between(request.startAt().toLocalDate(), request.endAt().toLocalDate());
    }

    private LoyaltyTier resolveTier(Long customerId) {
        if (customerId == null) {
            return LoyaltyTier.STANDARD;
        }
        LoyaltyTier tier = loyalty.findTierByUserId(customerId);
        return tier == null ? LoyaltyTier.STANDARD : tier;
    }

    private static BigDecimal discountRateOf(LoyaltyTier tier) {
        return switch (tier) {
            case STANDARD -> RATE_STANDARD;
            case VIP -> RATE_VIP;
            case DIAMOND -> RATE_DIAMOND;
        };
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
