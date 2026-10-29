package com.hotelpms.payment.dto;

import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO trả về cho client về một lần thử thanh toán.
 *
 * <p>Các trường ánh xạ 1-1 từ getter của {@link PaymentAttempt} — không diễn giải
 * lại, không tính toán thêm. Đặc biệt {@code status} lấy <b>nguyên văn</b> từ
 * entity: {@code PENDING} / {@code SUCCEEDED} / {@code FAILED}.
 *
 * <p>Chỉ {@code status == SUCCEEDED} mới được hiểu là "đã thanh toán thành công".
 * Attempt ở {@code PENDING} nghĩa là kết quả chưa được xác định chắc chắn.
 *
 * @param id                    ID attempt
 * @param bookingId             ID booking tương ứng
 * @param provider              cổng thanh toán ({@code DEMO} ở M4-04)
 * @param status                trạng thái hiện tại của attempt
 * @param requestedAmount       số tiền khách yêu cầu thanh toán
 * @param providerTransactionId mã giao dịch do cổng cấp; {@code null} nếu chưa có
 * @param succeededAt           thời điểm thành công; {@code null} nếu chưa thành công
 *
 * @author Nguyễn Đình Đạt
 */
public record PaymentAttemptResponse(
        Long id,
        Long bookingId,
        String provider,
        PaymentStatus status,
        BigDecimal requestedAmount,
        String providerTransactionId,
        Instant succeededAt
) {

    /**
     * Ánh xạ từ entity {@link PaymentAttempt}.
     *
     * @param attempt entity nguồn
     * @return DTO tương ứng
     */
    public static PaymentAttemptResponse from(PaymentAttempt attempt) {
        return new PaymentAttemptResponse(
                attempt.getId(),
                attempt.getBookingId(),
                attempt.getProvider() == null ? null : attempt.getProvider().name(),
                attempt.getStatus(),
                attempt.getRequestedAmount(),
                attempt.getProviderTransactionId(),
                attempt.getSucceededAt());
    }
}
