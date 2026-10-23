package com.hotelpms.pricing.snapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Snapshot giá bất biến của một lượt quote đã được tính xong.
 *
 * <p>Đây là giá trị duy nhất mà module {@code pricing} giao ra ngoài. Nó chỉ
 * <strong>lưu kết quả</strong> của lần báo giá, không tự tính lại giá theo tier và
 * không phụ thuộc vào entity nào khác (Booking, CustomerProfile, LoyaltyAward).
 * Nhờ vậy module khác có thể ghi xuống bảng đích mà không tạo phụ thuộc compile.
 *
 * <h2>Bất biến</h2>
 * <ul>
 *   <li>{@code payableAmount == baseAmount - discountAmount} — bắt buộc. Đây cũng là
 *       constraint {@code ck_bookings_amounts} của migration V1, nên DB vẫn là rào
 *       chống cuối cùng.</li>
 *   <li>Tiền luôn {@link BigDecimal} scale {@value #MONEY_SCALE}.</li>
 *   <li>{@code discountRate} luôn {@link BigDecimal} scale {@value #RATE_SCALE}.</li>
 *   <li>Mọi giá trị phải khác {@code null} và không âm.</li>
 * </ul>
 *
 * <h2>Vì sao không tự tính lại</h2>
 * <p>Kế hoạch ghi rõ: <em>"Snapshot không đổi khi sau này khách thăng hạng"</em>.
 * Nếu snapshot tự suy ra {@code payableAmount} từ tier hiện tại, một lần nâng hạng
 * sau đó sẽ làm đổi lịch sử giá của những booking cũ. Vì vậy lớp này chỉ nhận
 * số đã tính sẵn từ {@link com.hotelpms.pricing.service.QuoteService} và kiểm tra
 * tính nhất quán, không phân tán trách nhiệm tính giá.
 *
 * <h2>Sử dụng</h2>
 * <p>Thường tạo gián tiếp qua
 * {@link com.hotelpms.pricing.dto.QuoteResponse#toSnapshot()}:
 * <pre>{@code
 * PriceSnapshot snapshot = quoteResponse.toSnapshot();
 * booking.setBaseAmount(snapshot.baseAmount());
 * booking.setDiscountRate(snapshot.discountRate());
 * booking.setDiscountAmount(snapshot.discountAmount());
 * booking.setPayableAmount(snapshot.payableAmount());
 * }</pre>
 *
 * @param baseAmount     tổng giá gốc, chưa giảm
 * @param discountRate   tỷ lệ giảm đã áp dụng, 0–1
 * @param discountAmount số tiền được giảm
 * @param payableAmount  số tiền thực thu
 * @param appliedTier    hạng đã áp dụng để tính giảm giá
 *
 * @author Nguyễn Đình Đạt
 */
public record PriceSnapshot(
        BigDecimal baseAmount,
        BigDecimal discountRate,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        String appliedTier) {

    /** Số chữ số thập phân chuẩn cho mọi giá trị tiền. */
    public static final int MONEY_SCALE = 2;

    /** Số chữ số thập phân chuẩn cho {@code discountRate}. */
    public static final int RATE_SCALE = 4;

    /** Kiểu làm tròn dùng chung với {@link com.hotelpms.pricing.service.QuoteService}. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Canonical constructor: chuẩn hóa scale rồi mới kiểm tra bất biến. */
    public PriceSnapshot {
        Objects.requireNonNull(baseAmount, "baseAmount must not be null");
        Objects.requireNonNull(discountRate, "discountRate must not be null");
        Objects.requireNonNull(discountAmount, "discountAmount must not be null");
        Objects.requireNonNull(payableAmount, "payableAmount must not be null");
        Objects.requireNonNull(appliedTier, "appliedTier must not be null");

        baseAmount = money(baseAmount);
        discountAmount = money(discountAmount);
        payableAmount = money(payableAmount);
        discountRate = discountRate.setScale(RATE_SCALE, ROUNDING);

        requireNonNegative(baseAmount, "baseAmount");
        requireNonNegative(discountAmount, "discountAmount");
        requireNonNegative(payableAmount, "payableAmount");
        requireNonNegative(discountRate, "discountRate");

        String tier = appliedTier.strip();
        if (tier.isEmpty()) {
            throw new IllegalArgumentException("appliedTier must not be blank");
        }
        appliedTier = tier;

        BigDecimal expectedPayable = money(baseAmount.subtract(discountAmount));
        if (payableAmount.compareTo(expectedPayable) != 0) {
            throw new IllegalArgumentException("payableAmount must equal baseAmount - discountAmount; expected "
                    + expectedPayable.toPlainString() + " but was " + payableAmount.toPlainString());
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value.toPlainString());
        }
    }
}