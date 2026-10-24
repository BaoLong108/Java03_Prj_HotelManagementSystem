package com.hotelpms.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

class LoyaltyServiceTest {
    private final LoyaltyService loyalty = new LoyaltyService();

    @ParameterizedTest
    @CsvSource({"0,STANDARD", "9999999,STANDARD", "9999999.99,STANDARD", "10000000,VIP",
            "29999999,VIP", "29999999.99,VIP", "30000000,DIAMOND", "30000000.01,DIAMOND",
            "9999999999999.99,DIAMOND"})
    void tierThresholdsAreInclusiveAndUseExactDecimalMoney(String amount, LoyaltyTier tier) {
        assertThat(loyalty.resolveTier(new BigDecimal(amount))).isEqualTo(tier);
    }

    @ParameterizedTest
    @CsvSource({"STANDARD,0.00", "VIP,0.10", "DIAMOND,0.15"})
    void discountIsAFractionWithTwoDecimalPlaces(LoyaltyTier tier, String rate) {
        assertThat(loyalty.getDiscountRate(tier)).isEqualTo(new BigDecimal(rate));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "9999,0", "9999.99,0", "10000,1", "25000,2", "25000.99,2",
            "100000,10", "1000000,100", "9999999999999.99,999999999"})
    void pointsFloorEligibleSpendingWithoutRoundingUp(String amount, long points) {
        assertThat(loyalty.calculateEarnedPoints(new BigDecimal(amount))).isEqualTo(points);
    }

    @ParameterizedTest
    @CsvSource({"9999999.99,0.01,VIP", "29999999.99,0.01,DIAMOND", "10000000,0,VIP",
            "30000000,10000,DIAMOND"})
    void additionalSpendingCanCrossATierBoundary(String current, String additional, LoyaltyTier tier) {
        assertThat(loyalty.calculateTierAfterSpending(new BigDecimal(current), new BigDecimal(additional)))
                .isEqualTo(tier);
    }

    @Test
    void pricingReceivesTheCurrentServerCalculatedSnapshotWithoutApplyingTheNewStay() {
        var benefits = loyalty.getMembershipBenefits(new BigDecimal("9999999.99"));
        assertThat(benefits.tierCode()).isEqualTo(LoyaltyTier.STANDARD);
        assertThat(benefits.eligibleSpendTotal()).isEqualTo(new BigDecimal("9999999.99"));
        assertThat(benefits.discountRate()).isEqualTo(new BigDecimal("0.00"));
        assertThat(loyalty.calculateTierAfterSpending(benefits.eligibleSpendTotal(), new BigDecimal("10000")))
                .isEqualTo(LoyaltyTier.VIP);
        assertThat(benefits.tierCode()).isEqualTo(LoyaltyTier.STANDARD);
    }

    @Test
    void redundantZerosAndScientificNotationDoNotLosePrecision() {
        assertThat(loyalty.getMembershipBenefits(new BigDecimal("10000000.0000")).eligibleSpendTotal())
                .isEqualTo(new BigDecimal("10000000.00"));
        assertThat(loyalty.resolveTier(new BigDecimal("1E+7"))).isEqualTo(LoyaltyTier.VIP);
        assertThat(loyalty.calculateEarnedPoints(new BigDecimal("10000.0000"))).isEqualTo(1);
    }

    static Stream<Arguments> invalidMoney() {
        return Stream.of(Arguments.of((BigDecimal) null), Arguments.of(new BigDecimal("-0.01")),
                Arguments.of(new BigDecimal("10000.001")), Arguments.of(new BigDecimal("9999999999999.999")),
                Arguments.of(new BigDecimal("10000000000000")), Arguments.of(new BigDecimal("1E+1000000")),
                Arguments.of(new BigDecimal("1E-1000000")));
    }

    @ParameterizedTest
    @MethodSource("invalidMoney")
    void allMonetaryEntryPointsRejectNullNegativeOutOfRangeAndExcessPrecision(BigDecimal amount) {
        assertInvalid(() -> loyalty.resolveTier(amount));
        assertInvalid(() -> loyalty.calculateEarnedPoints(amount));
        assertInvalid(() -> loyalty.getMembershipBenefits(amount));
        assertInvalid(() -> loyalty.calculateTierAfterSpending(amount, BigDecimal.ZERO));
        assertInvalid(() -> loyalty.calculateTierAfterSpending(BigDecimal.ZERO, amount));
    }

    @Test
    void cumulativeSpendingOverflowIsRejectedBeforePersistence() {
        assertInvalid(() -> loyalty.calculateTierAfterSpending(new BigDecimal("9999999999999.99"),
                new BigDecimal("0.01")));
    }

    @Test
    void missingTierIsAValidationError() {
        assertThatThrownBy(() -> loyalty.getDiscountRate(null)).isInstanceOfSatisfying(ApiException.class,
                error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("INVALID_LOYALTY_TIER");
                });
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error.getCode()).isEqualTo("INVALID_LOYALTY_AMOUNT");
        });
    }
}
