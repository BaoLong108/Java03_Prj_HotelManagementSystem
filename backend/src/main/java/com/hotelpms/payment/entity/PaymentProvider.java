package com.hotelpms.payment.entity;

/**
 * Payment provider được hỗ trợ bởi module payment.
 *
 * <p>Giá trị enum phải khớp chính xác với ràng buộc {@code ck_payment_attempts_provider}
 * trong file {@code V1__initial_schema.sql}:
 * <pre>
 * CONSTRAINT ck_payment_attempts_provider CHECK (provider IN ('DEMO', 'VNPAY'))
 * </pre>
 *
 * <p>Ý nghĩa từng provider (theo {@code docs/architecture.md} và {@code docs/ownership.md}):
 * <ul>
 *   <li>{@link #DEMO} — provider giả lập, dùng cho P0 (Sprint 1) để chứng minh
 *       luồng thanh toán end-to-end mà không cần tích hợp cổng thật.</li>
 *   <li>{@link #VNPAY} — cổng VNPay Sandbox, thuộc P1 (Sprint sau) khi M4-04 trở đi
 *       triển khai xong.</li>
 * </ul>
 *
 * <p>Enum này dùng {@code @Enumerated(EnumType.STRING)} khi ánh xạ sang JPA, nên
 * thứ tự khai báo không ảnh hưởng đến dữ liệu DB — chỉ tên hằng số được lưu.
 *
 * @author Nguyễn Đình Đạt
 */
public enum PaymentProvider {
    /** Provider giả lập cho môi trường dev/test, không gọi cổng ngoài. */
    DEMO,

    /** Cổng VNPay Sandbox (sẽ tích hợp ở Sprint sau). */
    VNPAY
}
