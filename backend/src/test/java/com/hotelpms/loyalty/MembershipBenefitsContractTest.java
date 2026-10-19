package com.hotelpms.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;

class MembershipBenefitsContractTest {
    @ParameterizedTest
    @CsvSource({"0.00,STANDARD,0.00,0.00", "9999999.99,STANDARD,0.00,0.00", "10000000.00,VIP,0.10,200000.00",
            "29999999.99,VIP,0.10,200000.00", "30000000.00,DIAMOND,0.15,300000.00"})
    void springConsumerReceivesExactTierAndFractionThroughThePublishedInterface(
            String recordedSpending, LoyaltyTier tier, String rate, String expectedDiscount) {
        try (var context = new AnnotationConfigApplicationContext(LoyaltyService.class)) {
            var provider = context.getBean(MembershipBenefitsProvider.class);
            var benefits = provider.getMembershipBenefits(new BigDecimal(recordedSpending));
            assertThat(benefits.tierCode()).isEqualTo(tier);
            assertThat(benefits.eligibleSpendTotal()).isEqualTo(new BigDecimal(recordedSpending));
            assertThat(benefits.discountRate()).isEqualTo(new BigDecimal(rate));
            // A Pricing consumer multiplies by the fraction, without dividing by 100 again.
            assertThat(new BigDecimal("2000000.00").multiply(benefits.discountRate()))
                    .isEqualByComparingTo(new BigDecimal(expectedDiscount));
        }
    }

    @Test
    void quotingAnotherAmountCannotChangeAnEarlierBenefitsSnapshot() {
        MembershipBenefitsProvider provider = new LoyaltyService();
        var before = provider.getMembershipBenefits(new BigDecimal("9999999.99"));
        provider.getMembershipBenefits(new BigDecimal("10000000.00"));
        assertThat(before.tierCode()).isEqualTo(LoyaltyTier.STANDARD);
        assertThat(before.discountRate()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void invalidRecordedSpendingNeverBecomesAnApparentlyValidStandardBenefit() {
        MembershipBenefitsProvider provider = new LoyaltyService();
        for (BigDecimal amount : new BigDecimal[] {null, new BigDecimal("-0.01"), new BigDecimal("0.001")}) {
            assertThatThrownBy(() -> provider.getMembershipBenefits(amount))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(error.getCode()).isEqualTo("INVALID_LOYALTY_AMOUNT");
                    });
        }
    }
}
