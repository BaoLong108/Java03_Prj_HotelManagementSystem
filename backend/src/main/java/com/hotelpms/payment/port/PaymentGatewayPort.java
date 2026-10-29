package com.hotelpms.payment.port;

import java.math.BigDecimal;

/**
 * Port (giao diện) tới cổng thanh toán.
 *
 * <p>M4-04 chỉ hiện thực cổng giả lập {@code DEMO}. Cổng thật (VNPay Sandbox) thuộc
 * M4-05 và sẽ implement interface này mà không cần đổi tầng service.
 *
 * <p><b>Quy tắc mô phỏng kết quả:</b> gateway cho biết qua
 * {@link #supportsOutcomeSimulation()} rằng nó có chấp nhận kết quả do client
 * chỉ định hay không. Tầng service sẽ từ chối {@code simulatedOutcome} với lỗi
 * 400 {@code SIMULATED_OUTCOME_NOT_ALLOWED} nếu gateway không hỗ trợ — nhờ vậy
 * client <b>không thể</b> điều khiển kết quả thanh toán thật.
 *
 * @author Nguyễn Đình Đạt
 */
public interface PaymentGatewayPort {

    /**
     * Cổng này có chấp nhận kết quả mô phỏng do client chỉ định không.
     *
     * <p>Trả {@code true} chỉ với cổng giả lập trong môi trường dev/test.
     *
     * @return {@code true} nếu hỗ trợ mô phỏng kết quả
     */
    boolean supportsOutcomeSimulation();

    /**
     * Gửi yêu cầu thanh toán tới cổng.
     *
     * <p>Phương thức này <b>không</b> được gọi bên trong một transaction đang
     * giữ khóa ghi: tầng service gọi nó ở pha riêng, ngoài transaction.
     *
     * @param request yêu cầu thanh toán
     * @return kết quả từ cổng, không bao giờ {@code null}
     */
    GatewayResult charge(GatewayRequest request);

    /**
     * Kết quả mong đợi của thanh toán.
     *
     * <p>Dùng chung cho kết quả mô phỏng và kết quả thật. Với cổng thật, cổng sẽ
     * trả {@link #SUCCESS} hoặc {@link #FAILURE} tuỳ kết quả giao dịch thực tế.
     */
    enum Outcome {
        /** Thanh toán thành công. */
        SUCCESS,
        /** Thanh toán thất bại. */
        FAILURE
    }

    /**
     * Yêu cầu gửi tới cổng thanh toán.
     *
     * @param bookingId        ID booking cần thanh toán
     * @param amount           số tiền yêu cầu thanh toán
     * @param idempotencyKey   khóa chống ghi lặp do client sinh ra
     * @param simulatedOutcome kết quả mong muốn do client chỉ định, {@code null}
     *                         nếu client không chỉ định (cổng tự quyết định)
     */
    record GatewayRequest(
            Long bookingId,
            BigDecimal amount,
            String idempotencyKey,
            Outcome simulatedOutcome
    ) {}

    /**
     * Kết quả trả về từ cổng thanh toán.
     *
     * @param outcome               kết quả thực tế của giao dịch
     * @param providerTransactionId mã giao dịch do cổng cấp, dùng để đối chiếu
     * @param message               mô tả kết quả để hiển thị/ghi log
     */
    record GatewayResult(
            Outcome outcome,
            String providerTransactionId,
            String message
    ) {}
}
