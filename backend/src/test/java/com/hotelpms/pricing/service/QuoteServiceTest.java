package com.hotelpms.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    private static final Long ROOM_TYPE_ID = 1L;
    private static final Long CUSTOMER_ID = 42L;
    private static final BigDecimal PRICE_PER_NIGHT = new BigDecimal("1000000.00");

    @Mock
    private RoomTypePricingPort roomTypes;

    @Mock
    private LoyaltyPricingPort loyalty;

    private QuoteService quotes;

    @BeforeEach
    void setUp() {
        quotes = new QuoteService(roomTypes, loyalty);
    }

    @Test
    void guestGetsStandardRateAndZeroDiscount() {
        stubActiveRoom(PRICE_PER_NIGHT, 2);

        QuoteResponse response = quotes.quote(twoNightStay(2), null);

        assertThat(response.nights()).isEqualTo(2);
        assertThat(response.appliedTier()).isEqualTo("STANDARD");
        assertThat(response.basePricePerNight()).isEqualByComparingTo("1000000.00");
        assertThat(response.baseAmount()).isEqualByComparingTo("2000000.00");
        assertThat(response.discountRate()).isEqualByComparingTo("0.0000");
        assertThat(response.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(response.payableAmount()).isEqualByComparingTo("2000000.00");
        verifyNoInteractions(loyalty);
    }

    @Test
    void vipGetsTenPercentDiscount() {
        stubActiveRoom(PRICE_PER_NIGHT, 2);
        when(loyalty.findTierByUserId(CUSTOMER_ID)).thenReturn(LoyaltyTier.VIP);

        QuoteResponse response = quotes.quote(twoNightStay(2), CUSTOMER_ID);

        assertThat(response.appliedTier()).isEqualTo("VIP");
        assertThat(response.discountRate()).isEqualByComparingTo("0.1000");
        assertThat(response.baseAmount()).isEqualByComparingTo("2000000.00");
        assertThat(response.discountAmount()).isEqualByComparingTo("200000.00");
        assertThat(response.payableAmount()).isEqualByComparingTo("1800000.00");
    }

    @Test
    void diamondGetsFifteenPercentDiscount() {
        stubActiveRoom(PRICE_PER_NIGHT, 2);
        when(loyalty.findTierByUserId(CUSTOMER_ID)).thenReturn(LoyaltyTier.DIAMOND);

        QuoteResponse response = quotes.quote(twoNightStay(2), CUSTOMER_ID);

        assertThat(response.appliedTier()).isEqualTo("DIAMOND");
        assertThat(response.discountRate()).isEqualByComparingTo("0.1500");
        assertThat(response.discountAmount()).isEqualByComparingTo("300000.00");
        assertThat(response.payableAmount()).isEqualByComparingTo("1700000.00");
    }

    @Test
    void nightsAreCountedByLocalDateNotClockHours() {
        stubActiveRoom(PRICE_PER_NIGHT, 2);
        QuoteRequest request = new QuoteRequest(
                ROOM_TYPE_ID,
                OffsetDateTime.parse("2026-11-01T12:00:00+07:00"),
                OffsetDateTime.parse("2026-11-03T10:00:00+07:00"),
                2);

        QuoteResponse response = quotes.quote(request, null);

        assertThat(response.nights()).isEqualTo(2);
    }

    @Test
    void discountAmountUsesHalfUpToTwoDecimals() {
        stubActiveRoom(new BigDecimal("100000.00"), 2);
        when(loyalty.findTierByUserId(CUSTOMER_ID)).thenReturn(LoyaltyTier.DIAMOND);

        QuoteRequest request = new QuoteRequest(
                ROOM_TYPE_ID,
                OffsetDateTime.parse("2026-11-01T12:00:00+07:00"),
                OffsetDateTime.parse("2026-11-02T10:00:00+07:00"),
                1);

        QuoteResponse response = quotes.quote(request, CUSTOMER_ID);

        assertThat(response.nights()).isEqualTo(1);
        assertThat(response.baseAmount()).isEqualByComparingTo("100000.00");
        assertThat(response.discountAmount()).isEqualByComparingTo("15000.00");
        assertThat(response.payableAmount()).isEqualByComparingTo("85000.00");
    }

    @Test
    void rejectsWhenGuestCountExceedsCapacity() {
        stubActiveRoom(PRICE_PER_NIGHT, 2);

        assertThatThrownBy(() -> quotes.quote(twoNightStay(3), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException error = (ApiException) ex;
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("GUEST_COUNT_EXCEEDS_CAPACITY");
                });
    }

    @Test
    void rejectsWhenEndAtIsNotAfterStartAt() {
        QuoteRequest request = new QuoteRequest(
                ROOM_TYPE_ID,
                OffsetDateTime.parse("2026-11-02T12:00:00+07:00"),
                OffsetDateTime.parse("2026-11-02T10:00:00+07:00"),
                1);

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
    void rejectsWhenStayIsSameLocalDate() {
        stubActiveRoom(PRICE_PER_NIGHT, 2);
        QuoteRequest request = new QuoteRequest(
                ROOM_TYPE_ID,
                OffsetDateTime.parse("2026-11-01T12:00:00+07:00"),
                OffsetDateTime.parse("2026-11-01T18:00:00+07:00"),
                1);

        assertThatThrownBy(() -> quotes.quote(request, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException error = (ApiException) ex;
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("INVALID_NIGHTS");
                });
    }

    @Test
    void propagatesRoomTypeNotFoundFromPort() {
        when(roomTypes.findActiveById(ROOM_TYPE_ID)).thenThrow(new ApiException(
                HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại"));

        assertThatThrownBy(() -> quotes.quote(twoNightStay(1), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("ROOM_TYPE_NOT_FOUND"));
    }

    private void stubActiveRoom(BigDecimal basePricePerNight, int maxOccupancy) {
        when(roomTypes.findActiveById(ROOM_TYPE_ID)).thenReturn(
                new RoomTypeInfo(ROOM_TYPE_ID, basePricePerNight, maxOccupancy, true));
    }

    private static QuoteRequest twoNightStay(int guestCount) {
        return new QuoteRequest(
                ROOM_TYPE_ID,
                OffsetDateTime.parse("2026-11-01T12:00:00+07:00"),
                OffsetDateTime.parse("2026-11-03T10:00:00+07:00"),
                guestCount);
    }
}
