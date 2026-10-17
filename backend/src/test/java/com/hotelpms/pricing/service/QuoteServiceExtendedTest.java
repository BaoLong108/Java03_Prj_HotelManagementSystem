package com.hotelpms.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.pricing.dto.QuoteRequest;
import com.hotelpms.pricing.dto.QuoteResponse;
import com.hotelpms.pricing.port.LoyaltyPricingPort;
import com.hotelpms.pricing.port.RoomTypePricingPort;
import com.hotelpms.pricing.port.RoomTypePricingPort.RoomTypeInfo;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Unit test mở rộng cho {@link QuoteService} trong phạm vi M4-01.
 *
 * <p>Bổ sung các trường hợp biên (boundary), bất biến tiền tệ, fallback khi port trả null,
 * và bảo vệ trước dữ liệu DB có precision > 2. Mục tiêu: giữ M4-01 đạt 100% chất lượng
 * trước khi M4-02 (loyalty) và phần tích hợp Booking (Bình) vào cuộc.
 */
@ExtendWith(MockitoExtension.class)
class QuoteServiceExtendedTest {

    private static final Long ROOM_TYPE_ID = 7L;
    private static final Long CUSTOMER_ID = 99L;

    @Mock private RoomTypePricingPort roomTypes;
    @Mock private LoyaltyPricingPort loyalty;

    private QuoteService quotes;

    @BeforeEach
    void setUp() {
        quotes = new QuoteService(roomTypes, loyalty);
    }

    // ---------------------------------------------------------------------
    // Biên hợp lệ
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("guestCount = maxOccupancy được chấp nhận")
    void acceptsWhenGuestCountEqualsCapacity() {
        when(roomTypes.findActiveById(ROOM_TYPE_ID))
                .thenReturn(new RoomTypeInfo(ROOM_TYPE_ID, new BigDecimal("500000.00"), 2, true));

        QuoteResponse response = quotes.quote(twoNightStay(2), null);

        assertThat(response.nights()).isEqualTo(2);
        assertThat(response.baseAmount()).isEqualByComparingTo("1000000.00");
        assertThat(response.payableAmount()).isEqualByComparingTo("1000000.00");
    }

    @Test
    @DisplayName("basePricePerNight = 0 cho ra payableAmount = 0")
    void zeroBasePriceProducesZeroPayable() {
        when(roomTypes.findActiveById(ROOM_TYPE_ID))
                .thenReturn(new RoomTypeInfo(ROOM_TYPE_ID, BigDecimal.ZERO, 2, true));
        when(loyalty.findTierByUserId(CUSTOMER_ID)).thenReturn(LoyaltyTier.DIAMOND);

        QuoteResponse response = quotes.quote(twoNightStay(2), CUSTOMER_ID);

        assertThat(response.basePricePerNight()).isEqualByComparingTo("0.00");
        assertThat(response.baseAmount()).isEqualByComparingTo("0.00");
        assertThat(response.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(response.payableAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Loyalty port trả null → fallback về STANDARD, không ném lỗi")
    void nullLoyaltyTierFallsBackToStandard() {
        when(roomTypes.findActiveById(ROOM_TYPE_ID))
                .thenReturn(new RoomTypeInfo(ROOM_TYPE_ID, new BigDecimal("800000.00"), 2, true));
        when(loyalty.findTierByUserId(CUSTOMER_ID)).thenReturn(null);

        QuoteResponse response = quotes.quote(twoNightStay(2), CUSTOMER_ID);

        assertThat(response.appliedTier()).isEqualTo("STANDARD");
        assertThat(response.discountRate()).isEqualByComparingTo("0.0000");
        assertThat(response.payableAmount()).isEqualByComparingTo("1600000.00");
    }

    // ---------------------------------------------------------------------
    // Bảo vệ trước null/precision từ port
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("RoomType port trả null → 404 ROOM_TYPE_NOT_FOUND")
    void nullRoomTypeInfoBecomesNotFound() {
        when(roomTypes.findActiveById(ROOM_TYPE_ID)).thenReturn(null);

        assertThatThrownBy(() -> quotes.quote(twoNightStay(2), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException error = (ApiException) ex;
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.getCode()).isEqualTo("ROOM_TYPE_NOT_FOUND");
                });
        verify(loyalty, never()).findTierByUserId(anyLong());
    }

    @Test
    @DisplayName("Giá DB có scale > 2 vẫn được làm tròn về 2 chữ số thập phân")
    void highPrecisionBasePriceIsRoundedToTwoDecimals() {
        when(roomTypes.findActiveById(ROOM_TYPE_ID))
                .thenReturn(new RoomTypeInfo(ROOM_TYPE_ID, new BigDecimal("1234567.8943"), 2, true));
        when(loyalty.findTierByUserId(CUSTOMER_ID)).thenReturn(LoyaltyTier.VIP);

        QuoteResponse response = quotes.quote(twoNightStay(2), CUSTOMER_ID);

        // basePricePerNight hiển thị scale 2: 1234567.89 (HALF_UP)
        assertThat(response.basePricePerNight()).isEqualByComparingTo("1234567.89");
        // baseAmount = 1234567.89 * 2 = 2469135.78
        assertThat(response.baseAmount()).isEqualByComparingTo("2469135.78");
        // discount = 2469135.78 * 0.10 = 246913.578 → 246913.58
        assertThat(response.discountAmount()).isEqualByComparingTo("246913.58");
        // payable = 2469135.78 - 246913.58 = 2222222.20
        assertThat(response.payableAmount()).isEqualByComparingTo("2222222.20");
    }

    // ---------------------------------------------------------------------
    // Bất biến tiền tệ
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Bất biến: payableAmount = baseAmount - discountAmount với mọi tier")
    void payableAlwaysEqualsBaseMinusDiscount() {
        when(roomTypes.findActiveById(ROOM_TYPE_ID))
                .thenReturn(new RoomTypeInfo(ROOM_TYPE_ID, new BigDecimal("1000000.00"), 2, true));

        for (LoyaltyTier tier : LoyaltyTier.values()) {
            when(loyalty.findTierByUserId(CUSTOMER_ID)).thenReturn(tier);

            QuoteResponse response = quotes.quote(twoNightStay(1), CUSTOMER_ID);

            BigDecimal expected = response.baseAmount().subtract(response.discountAmount());
            assertThat(response.payableAmount())
                    .as("payable phải khớp base - discount với tier %s", tier)
                    .isEqualByComparingTo(expected);
        }
    }

    @Test
    @DisplayName("Lưu trú 30 đêm vẫn giữ scale 2 cho tiền và 4 cho tỷ lệ")
    void longStayKeepsMoneyAndRateScales() {
        when(roomTypes.findActiveById(ROOM_TYPE_ID))
                .thenReturn(new RoomTypeInfo(ROOM_TYPE_ID, new BigDecimal("1000000.00"), 2, true));
        when(loyalty.findTierByUserId(CUSTOMER_ID)).thenReturn(LoyaltyTier.DIAMOND);

        QuoteRequest request = new QuoteRequest(
                ROOM_TYPE_ID,
                OffsetDateTime.parse("2026-11-01T12:00:00+07:00"),
                OffsetDateTime.parse("2026-12-01T10:00:00+07:00"),
                1);

        QuoteResponse response = quotes.quote(request, CUSTOMER_ID);

        assertThat(response.nights()).isEqualTo(30);
        assertThat(response.baseAmount()).isEqualByComparingTo("30000000.00");
        assertThat(response.discountRate().scale()).isEqualTo(4);
        assertThat(response.baseAmount().scale()).isEqualTo(2);
        assertThat(response.discountAmount().scale()).isEqualTo(2);
        assertThat(response.payableAmount().scale()).isEqualTo(2);
        assertThat(response.payableAmount()).isEqualByComparingTo("25500000.00");
    }

    // ---------------------------------------------------------------------
    // Validation bổ sung
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("guestCount = 0 bị từ chối bởi validateRequest (VALIDATION_ERROR)")
    void rejectsZeroGuestCount() {
        assertThatThrownBy(() -> quotes.quote(twoNightStay(0), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException error = (ApiException) ex;
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR");
                });
        verifyNoInteractions(roomTypes, loyalty);
    }

    @Test
    @DisplayName("startAt = endAt bị từ chối bởi validateRequest (INVALID_DATE_RANGE)")
    void rejectsEqualStartAndEndAt() {
        OffsetDateTime same = OffsetDateTime.parse("2026-11-02T12:00:00+07:00");
        QuoteRequest request = new QuoteRequest(ROOM_TYPE_ID, same, same, 1);

        assertThatThrownBy(() -> quotes.quote(request, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException error = (ApiException) ex;
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("INVALID_DATE_RANGE");
                });
        verifyNoInteractions(roomTypes, loyalty);
    }

    @Test
    @DisplayName("startAt null bị từ chối ngay tại validateRequest")
    void rejectsNullStartAt() {
        QuoteRequest request = new QuoteRequest(
                ROOM_TYPE_ID,
                null,
                OffsetDateTime.parse("2026-11-02T10:00:00+07:00"),
                1);

        assertThatThrownBy(() -> quotes.quote(request, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(roomTypes, loyalty);
    }

    @Test
    @DisplayName("endAt null bị từ chối ngay tại validateRequest")
    void rejectsNullEndAt() {
        QuoteRequest request = new QuoteRequest(
                ROOM_TYPE_ID,
                OffsetDateTime.parse("2026-11-01T12:00:00+07:00"),
                null,
                1);

        assertThatThrownBy(() -> quotes.quote(request, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(roomTypes, loyalty);
    }

    @Test
    @DisplayName("roomTypeId = 0 bị từ chối (VALIDATION_ERROR)")
    void rejectsNonPositiveRoomTypeId() {
        QuoteRequest request = new QuoteRequest(
                0L,
                OffsetDateTime.parse("2026-11-01T12:00:00+07:00"),
                OffsetDateTime.parse("2026-11-02T10:00:00+07:00"),
                1);

        assertThatThrownBy(() -> quotes.quote(request, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException error = (ApiException) ex;
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR");
                });
        verifyNoInteractions(roomTypes, loyalty);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static QuoteRequest twoNightStay(int guestCount) {
        return new QuoteRequest(
                ROOM_TYPE_ID,
                OffsetDateTime.parse("2026-11-01T12:00:00+07:00"),
                OffsetDateTime.parse("2026-11-03T10:00:00+07:00"),
                guestCount);
    }
}
