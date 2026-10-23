package com.hotelpms.payment.entity;

/**
 * Vòng đời trạng thái của một {@link PaymentAttempt}.
 *
 * <p>Giá trị enum phải khớp chính xác với ràng buộc {@code ck_payment_attempts_status}
 * trong file {@code V1__initial_schema.sql}:
 * <pre>
 * CONSTRAINT ck_payment_attempts_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
 * </pre>
 *
 * <p>Quy tắc chuyển trạng thái (đã được mã hóa trong {@link PaymentAttempt}):
 * <ul>
 *   <li>Một attempt mới tạo luôn ở trạng thái {@link #PENDING}.</li>
 *   <li>Chỉ có thể chuyển từ {@code PENDING} → {@link #SUCCEEDED} hoặc {@link #FAILED}.</li>
 *   <li>Một khi đã ở {@code SUCCEEDED} hoặc {@code FAILED} thì không thể đổi nữa
 *       (trạng thái kết thúc — terminal state).</li>
 * </ul>
 *
 * <p>Mỗi booking chỉ được phép có tối đa một attempt ở trạng thái {@code SUCCEEDED}
 * (ràng buộc partial unique index {@code uq_payment_attempts_one_success_per_booking}).
 * Ràng buộc này được DB đảm bảo; tầng service (M4-04 trở đi) cần đọc trước khi
 * cố ý chuyển sang {@code SUCCEEDED} để trả HTTP 409 dễ hiểu.
 *
 * @author Nguyễn Đình Đạt
 */
public enum PaymentStatus {
    /**
     * Attempt vừa được tạo, đang chờ cổng thanh toán phản hồi. Đây là trạng thái
     * khởi đầu và là trạng thái trung gian duy nhất — mọi attempt phải đi qua
     * {@code PENDING} trước khi có thể kết thúc.
     */
    PENDING,

    /**
     * Cổng thanh toán xác nhận đã nhận tiền. Trạng thái kết thúc (terminal).
     */
    SUCCEEDED,

    /**
     * Cổng thanh toán hoặc service từ chối/từ-timeout, attempt thất bại.
     * Trạng thái kết thúc (terminal). Có thể tạo attempt mới với
     * {@code idempotencyKey} khác để thử lại.
     */
    FAILED
}
