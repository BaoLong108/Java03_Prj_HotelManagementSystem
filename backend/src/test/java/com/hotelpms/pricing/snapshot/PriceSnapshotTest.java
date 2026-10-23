package com.hotelpms.pricing.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.hotelpms.pricing.dto.QuoteResponse;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Test cho {@link PriceSnapshot} — phần "snapshot chiết khấu" của M4-02.
 *
 * <p>Nguyên tắc kiểm thử:
 * <ul>
 *   <li>Snapshot là <strong>ghi kết quả</strong>, không phán đoán lại tier. Các test
 *       VIP/DIAMOND bên dưới chỉ xác nhận snapshot nhận đúng {@code rate} và đúng
 *       kết quả tính ra; không có logic nào xác định khách nào thuộc hạng nào.</li>
 *   <li>Bất biến tiền tệ được kiểm tra ở nhiều vị trí: constructor, accessor và
 *       cả khi mapping từ {@link QuoteResponse}.</li>
 * </ul>
 *
 * <p>Các quy tắc nghiệp vụ liên quan tới hạng (ngưỡng 10 triệu / 30 triệu) thuộc
 * M1-04 và {@code M1-06} của Nguyễn Ngọc Bảo Long — file này không kiểm tra chúng.
 *
 * @author Nguyễn Đình Đạt
 */
class PriceSnapshotTest {

    // ---------------------------------------------------------------------
    // Tạo snapshot hợp lệ
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Tạo snapshot hợp lệ")
    class ValidCreation {

        @Test
        @DisplayName("Tạo được từ các giá trị nhất quán và đọc lại nguyên vẹn")
        void createsFromConsistentValues() {
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.1000"),
                    new BigDecimal("200000.00"),
                    new BigDecimal("1800000.00"),
                    "VIP");

            assertThat(snapshot.baseAmount()).isEqualByComparingTo("2000000.00");
            assertThat(snapshot.discountRate()).isEqualByComparingTo("0.1000");
            assertThat(snapshot.discountAmount()).isEqualByComparingTo("200000.00");
            assertThat(snapshot.payableAmount()).isEqualByComparingTo("1800000.00");
            assertThat(snapshot.appliedTier()).isEqualTo("VIP");
        }

        @Test
        @DisplayName("discount = 0 với STANDARD thì payable = base, không giảm gì")
        void standardTierWithZeroDiscount() {
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.0000"),
                    new BigDecimal("0.00"),
                    new BigDecimal("2000000.00"),
                    "STANDARD");

            assertThat(snapshot.discountRate()).isEqualByComparingTo("0.0000");
            assertThat(snapshot.discountAmount()).isEqualByComparingTo("0.00");
            assertThat(snapshot.payableAmount()).isEqualByComparingTo("2000000.00");
            assertThat(snapshot.appliedTier()).isEqualTo("STANDARD");
        }

        @Test
        @DisplayName("VIP 10%: 2.000.000 → giảm 200.000 → thực thu 1.800.000")
        void vipGetsTenPercent() {
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.1000"),
                    new BigDecimal("200000.00"),
                    new BigDecimal("1800000.00"),
                    "VIP");

            assertThat(snapshot.discountRate()).isEqualByComparingTo("0.1000");
            assertThat(snapshot.discountAmount()).isEqualByComparingTo("200000.00");
            assertThat(snapshot.payableAmount()).isEqualByComparingTo("1800000.00");
        }

        @Test
        @DisplayName("DIAMOND 15%: 2.000.000 → giảm 300.000 → thực thu 1.700.000")
        void diamondGetsFifteenPercent() {
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.1500"),
                    new BigDecimal("300000.00"),
                    new BigDecimal("1700000.00"),
                    "DIAMOND");

            assertThat(snapshot.discountRate()).isEqualByComparingTo("0.1500");
            assertThat(snapshot.discountAmount()).isEqualByComparingTo("300000.00");
            assertThat(snapshot.payableAmount()).isEqualByComparingTo("1700000.00");
        }

        @Test
        @DisplayName("appliedTier được strip khoảng trắng thừa")
        void stripsAppliedTierWhitespace() {
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("1000000.00"),
                    new BigDecimal("0.0000"),
                    new BigDecimal("0.00"),
                    new BigDecimal("1000000.00"),
                    "  STANDARD  ");

            assertThat(snapshot.appliedTier()).isEqualTo("STANDARD");
        }
    }

    // ---------------------------------------------------------------------
    // Bất biến tiền tệ
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Bất biến payableAmount = baseAmount - discountAmount")
    class MoneyInvariant {

        @Test
        @DisplayName("Bất biến đúng khi tạo")
        void invariantHoldsOnCreation() {
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("1234567.89"),
                    new BigDecimal("0.1000"),
                    new BigDecimal("123456.79"),
                    new BigDecimal("1111111.10"),
                    "VIP");

            assertThat(snapshot.payableAmount())
                    .isEqualByComparingTo(snapshot.baseAmount().subtract(snapshot.discountAmount()));
        }

        @Test
        @DisplayName("Reject khi payableAmount sai lệch so với base - discount")
        void rejectsWrongPayableAmount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("2000000.00"),
                            new BigDecimal("0.1000"),
                            new BigDecimal("200000.00"),
                            new BigDecimal("1700000.00"), // đáng lẽ phải là 1.800.000
                            "VIP"))
                    .withMessageContaining("payableAmount");
        }

        @Test
        @DisplayName("Reject khi payableAmount lớn hơn base - discount")
        void rejectsPayableAmountTooLarge() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("1000000.00"),
                            new BigDecimal("0.1500"),
                            new BigDecimal("150000.00"),
                            new BigDecimal("900000.00"), // đáng lẽ phải là 850.000
                            "DIAMOND"))
                    .withMessageContaining("payableAmount");
        }

        @Test
        @DisplayName("Chấp nhận khi payable khớp dù khác scale đầu vào")
        void acceptsMatchingValueWithDifferentScale() {
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("1000000"),
                    new BigDecimal("0.1"),
                    new BigDecimal("100000"),
                    new BigDecimal("900000"),
                    "VIP");

            assertThat(snapshot.baseAmount()).isEqualByComparingTo("1000000.00");
            assertThat(snapshot.discountAmount()).isEqualByComparingTo("100000.00");
            assertThat(snapshot.payableAmount()).isEqualByComparingTo("900000.00");
        }
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Validation giá trị")
    class Validation {

        @Test
        @DisplayName("Reject baseAmount âm")
        void rejectsNegativeBaseAmount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("-1.00"),
                            new BigDecimal("0.0000"),
                            new BigDecimal("0.00"),
                            new BigDecimal("-1.00"),
                            "STANDARD"))
                    .withMessageContaining("baseAmount");
        }

        @Test
        @DisplayName("Reject discountRate âm")
        void rejectsNegativeDiscountRate() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("1000000.00"),
                            new BigDecimal("-0.1000"),
                            new BigDecimal("0.00"),
                            new BigDecimal("1000000.00"),
                            "VIP"))
                    .withMessageContaining("discountRate");
        }

        @Test
        @DisplayName("Reject discountAmount âm")
        void rejectsNegativeDiscountAmount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("1000000.00"),
                            new BigDecimal("0.0000"),
                            new BigDecimal("-100000.00"),
                            new BigDecimal("1100000.00"),
                            "STANDARD"))
                    .withMessageContaining("discountAmount");
        }

        @Test
        @DisplayName("Reject payableAmount âm")
        void rejectsNegativePayableAmount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("0.00"),
                            new BigDecimal("0.0000"),
                            new BigDecimal("0.00"),
                            new BigDecimal("-0.01"),
                            "STANDARD"))
                    .withMessageContaining("payableAmount");
        }

        @Test
        @DisplayName("Reject appliedTier null")
        void rejectsNullAppliedTier() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("1000000.00"),
                            new BigDecimal("0.0000"),
                            new BigDecimal("0.00"),
                            new BigDecimal("1000000.00"),
                            null))
                    .withMessageContaining("appliedTier");
        }

        @Test
        @DisplayName("Reject appliedTier rỗng hoặc chỉ có khoảng trắng")
        void rejectsBlankAppliedTier() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("1000000.00"),
                            new BigDecimal("0.0000"),
                            new BigDecimal("0.00"),
                            new BigDecimal("1000000.00"),
                            "   "))
                    .withMessageContaining("appliedTier");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("1000000.00"),
                            new BigDecimal("0.0000"),
                            new BigDecimal("0.00"),
                            new BigDecimal("1000000.00"),
                            ""))
                    .withMessageContaining("appliedTier");
        }

        @Test
        @DisplayName("Reject khi bất kỳ giá trị tiền nào null")
        void rejectsNullMoneyValues() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new PriceSnapshot(
                            null,
                            new BigDecimal("0.0000"),
                            new BigDecimal("0.00"),
                            new BigDecimal("0.00"),
                            "STANDARD"))
                    .withMessageContaining("baseAmount");

            assertThatNullPointerException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("0.00"),
                            null,
                            new BigDecimal("0.00"),
                            new BigDecimal("0.00"),
                            "STANDARD"))
                    .withMessageContaining("discountRate");

            assertThatNullPointerException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("0.00"),
                            new BigDecimal("0.0000"),
                            null,
                            new BigDecimal("0.00"),
                            "STANDARD"))
                    .withMessageContaining("discountAmount");

            assertThatNullPointerException()
                    .isThrownBy(() -> new PriceSnapshot(
                            new BigDecimal("0.00"),
                            new BigDecimal("0.0000"),
                            new BigDecimal("0.00"),
                            null,
                            "STANDARD"))
                    .withMessageContaining("payableAmount");
        }
    }

    // ---------------------------------------------------------------------
    // Chuẩn hóa scale
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Chuẩn hóa scale")
    class ScaleNormalization {

        @Test
        @DisplayName("Mọi giá trị tiền được chuẩn hóa về scale 2")
        void moneyUsesScaleTwo() {
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("1234567.8943"),
                    new BigDecimal("0.1000"),
                    new BigDecimal("123456.79"),
                    new BigDecimal("1111111.10"),
                    "VIP");

            assertThat(snapshot.baseAmount().scale()).isEqualTo(2);
            assertThat(snapshot.discountAmount().scale()).isEqualTo(2);
            assertThat(snapshot.payableAmount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("discountRate được chuẩn hóa về scale 4")
        void discountRateUsesScaleFour() {
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("1000000.00"),
                    new BigDecimal("0.1"),
                    new BigDecimal("100000.00"),
                    new BigDecimal("900000.00"),
                    "VIP");

            assertThat(snapshot.discountRate().scale()).isEqualTo(4);
            assertThat(snapshot.discountRate()).isEqualByComparingTo("0.1000");
        }

        @Test
        @DisplayName("Làm tròn HALF_UP khi chuẩn hóa tiền")
        void moneyRoundsHalfUp() {
            // 2469135.78 * 0.1000 = 246913.578 → HALF_UP → 246913.58
            PriceSnapshot snapshot = new PriceSnapshot(
                    new BigDecimal("2469135.78"),
                    new BigDecimal("0.1000"),
                    new BigDecimal("246913.578"),
                    new BigDecimal("2222222.20"),
                    "VIP");

            assertThat(snapshot.discountAmount()).isEqualByComparingTo("246913.58");
            assertThat(snapshot.payableAmount()).isEqualByComparingTo("2222222.20");
        }

        @Test
        @DisplayName("Constant MONEY_SCALE / RATE_SCALE khớp hành vi chuẩn hóa")
        void scaleConstantsMatchBehavior() {
            assertThat(PriceSnapshot.MONEY_SCALE).isEqualTo(2);
            assertThat(PriceSnapshot.RATE_SCALE).isEqualTo(4);
            assertThat(PriceSnapshot.ROUNDING).isEqualTo(java.math.RoundingMode.HALF_UP);
        }
    }

    // ---------------------------------------------------------------------
    // Mapping từ QuoteResponse
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("QuoteResponse.toSnapshot()")
    class QuoteResponseMapping {

        @Test
        @DisplayName("Ánh xạ đúng từng field sang snapshot")
        void mapsEveryField() {
            QuoteResponse response = new QuoteResponse(
                    7L,
                    2,
                    new BigDecimal("1000000.00"),
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.1000"),
                    new BigDecimal("200000.00"),
                    new BigDecimal("1800000.00"),
                    "VIP");

            PriceSnapshot snapshot = response.toSnapshot();

            assertThat(snapshot.baseAmount()).isEqualByComparingTo(response.baseAmount());
            assertThat(snapshot.discountRate()).isEqualByComparingTo(response.discountRate());
            assertThat(snapshot.discountAmount()).isEqualByComparingTo(response.discountAmount());
            assertThat(snapshot.payableAmount()).isEqualByComparingTo(response.payableAmount());
            assertThat(snapshot.appliedTier()).isEqualTo(response.appliedTier());
        }

        @Test
        @DisplayName("STANDARD: quote không giảm → snapshot payable bằng base")
        void mapsStandardQuote() {
            QuoteResponse response = new QuoteResponse(
                    7L,
                    2,
                    new BigDecimal("1000000.00"),
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.0000"),
                    new BigDecimal("0.00"),
                    new BigDecimal("2000000.00"),
                    "STANDARD");

            PriceSnapshot snapshot = response.toSnapshot();

            assertThat(snapshot.discountRate()).isEqualByComparingTo("0.0000");
            assertThat(snapshot.discountAmount()).isEqualByComparingTo("0.00");
            assertThat(snapshot.payableAmount()).isEqualByComparingTo("2000000.00");
            assertThat(snapshot.appliedTier()).isEqualTo("STANDARD");
        }

        @Test
        @DisplayName("DIAMOND: quote giảm 15% → snapshot giữ đúng số tiền thực thu")
        void mapsDiamondQuote() {
            QuoteResponse response = new QuoteResponse(
                    7L,
                    2,
                    new BigDecimal("1000000.00"),
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.1500"),
                    new BigDecimal("300000.00"),
                    new BigDecimal("1700000.00"),
                    "DIAMOND");

            PriceSnapshot snapshot = response.toSnapshot();

            assertThat(snapshot.discountRate()).isEqualByComparingTo("0.1500");
            assertThat(snapshot.payableAmount()).isEqualByComparingTo("1700000.00");
            assertThat(snapshot.appliedTier()).isEqualTo("DIAMOND");
        }

        @Test
        @DisplayName("Snapshot nhận đủ scale chuẩn dù DTO mang scale khác")
        void normalizesScaleComingFromDto() {
            QuoteResponse response = new QuoteResponse(
                    7L,
                    1,
                    new BigDecimal("1234567.8943"),
                    new BigDecimal("1234567.8943"),
                    new BigDecimal("0.1"),
                    new BigDecimal("123456.78943"),
                    new BigDecimal("1111111.10"),
                    "VIP");

            PriceSnapshot snapshot = response.toSnapshot();

            assertThat(snapshot.baseAmount().scale()).isEqualTo(2);
            assertThat(snapshot.discountAmount().scale()).isEqualTo(2);
            assertThat(snapshot.discountRate().scale()).isEqualTo(4);
            assertThat(snapshot.baseAmount()).isEqualByComparingTo("1234567.89");
            assertThat(snapshot.discountAmount()).isEqualByComparingTo("123456.79");
            assertThat(snapshot.payableAmount()).isEqualByComparingTo("1111111.10");
        }

        @Test
        @DisplayName("QuoteResponse không đổi behavior sau khi thêm toSnapshot()")
        void quoteResponseAccessorsRemainUnchanged() {
            QuoteResponse response = new QuoteResponse(
                    7L,
                    2,
                    new BigDecimal("1000000.00"),
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.1000"),
                    new BigDecimal("200000.00"),
                    new BigDecimal("1800000.00"),
                    "VIP");

            assertThat(response.roomTypeId()).isEqualTo(7L);
            assertThat(response.nights()).isEqualTo(2);
            assertThat(response.basePricePerNight()).isEqualByComparingTo("1000000.00");
            assertThat(response.baseAmount()).isEqualByComparingTo("2000000.00");
            assertThat(response.discountRate()).isEqualByComparingTo("0.1000");
            assertThat(response.discountAmount()).isEqualByComparingTo("200000.00");
            assertThat(response.payableAmount()).isEqualByComparingTo("1800000.00");
            assertThat(response.appliedTier()).isEqualTo("VIP");

            // Gọi nhiều lần cho cùng kết quả, không có side effect.
            assertThat(response.toSnapshot()).isEqualTo(response.toSnapshot());
        }
    }

    // ---------------------------------------------------------------------
    // Bất biến (immutability)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Bất biến")
    class Immutability {

        @Test
        @DisplayName("Là record nên không có setter hay field ghi được")
        void hasNoMutableState() {
            List<RecordComponent> components = Arrays.stream(PriceSnapshot.class.getRecordComponents()).toList();

            assertThat(PriceSnapshot.class.isRecord()).isTrue();
            assertThat(components)
                    .extracting(RecordComponent::getName)
                    .containsExactly(
                            "baseAmount",
                            "discountRate",
                            "discountAmount",
                            "payableAmount",
                            "appliedTier");

            assertThat(PriceSnapshot.class.getDeclaredMethods())
                    .as("record không được khai báo setter")
                    .noneMatch(method -> method.getName().startsWith("set"));
        }

        @Test
        @DisplayName("Các field là final — không thể gán lại qua reflection mà không phá vỡ")
        void fieldsAreFinal() throws ReflectiveOperationException {
            for (RecordComponent component : PriceSnapshot.class.getRecordComponents()) {
                var field = PriceSnapshot.class.getDeclaredField(component.getName());
                int modifiers = field.getModifiers();
                org.junit.jupiter.api.Assertions.assertTrue(java.lang.reflect.Modifier.isFinal(modifiers),
                        component.getName() + " phải là final");
            }
        }

        @Test
        @DisplayName("Hai snapshot có cùng giá trị thì bằng nhau")
        void valueSemantics() {
            PriceSnapshot left = new PriceSnapshot(
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.1000"),
                    new BigDecimal("200000.00"),
                    new BigDecimal("1800000.00"),
                    "VIP");
            PriceSnapshot right = new PriceSnapshot(
                    new BigDecimal("2000000.00"),
                    new BigDecimal("0.1000"),
                    new BigDecimal("200000.00"),
                    new BigDecimal("1800000.00"),
                    "VIP");

            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
        }
    }
}