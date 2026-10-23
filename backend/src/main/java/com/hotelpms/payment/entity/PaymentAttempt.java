package com.hotelpms.payment.entity;

import com.hotelpms.shared.model.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Một lần thử thanh toán cho một booking.
 *
 * <p>Ánh xạ 1-1 với bảng {@code payment_attempts} trong
 * {@code V1__initial_schema.sql}. Mỗi attempt đại diện cho một request gửi tới
 * provider (DEMO hoặc VNPay) và chờ phản hồi thành công/thất bại.
 *
 * <h2>Quy tắc nghiệp vụ quan trọng</h2>
 * <ul>
 *   <li><b>Idempotency:</b> tổ hợp {@code (bookingId, provider, idempotencyKey)} là duy nhất
 *       (ràng buộc {@code uq_payment_attempts_idempotency}). Cùng một key được dùng để
 *       client retry khi mạng lỗi mà không tạo ra hai attempt.</li>
 *   <li><b>Provider transaction id:</b> nếu provider trả về mã giao dịch thì
 *       {@code (provider, providerTransactionId)} là duy nhất có điều kiện
 *       (partial unique index).</li>
 *   <li><b>Một booking chỉ một SUCCEEDED:</b> partial unique index
 *       {@code uq_payment_attempts_one_success_per_booking} đảm bảo mỗi booking
 *       có tối đa một attempt thành công. Việc cố tình chuyển trạng thái sang
 *       {@code SUCCEEDED} khi đã có attempt khác ở {@code SUCCEEDED} sẽ bị DB từ chối.</li>
 *   <li><b>Trạng thái cuối:</b> {@code SUCCEEDED} và {@code FAILED} là terminal
 *       — không được phép chuyển đi đâu nữa (xem {@link #markSucceeded(Instant)}
 *       và {@link #markFailed(Instant)}).</li>
 * </ul>
 *
 * <h2>Quyết định thiết kế đặc thù của M4-03</h2>
 * <p>Trên branch {@code NguyenDinhDat}, module {@code booking} chưa có entity Java
 * (chỉ có {@code package-info.java}). Vì vậy field {@link #bookingId} được lưu
 * dưới dạng {@code Long} thuần, không dùng {@code @ManyToOne} tới {@code Booking}.
 * Tầng service (M4-04 trở đi) sẽ tra cứu booking thông qua một port do
 * {@code booking} cung cấp sau khi module đó hoàn thiện. Việc này cũng khớp với
 * nguyên tắc "port do module sử dụng tự định nghĩa" đã được M4-01 áp dụng.
 *
 * <p>Entity kế thừa {@link AuditedEntity} để có sẵn {@code createdAt} và
 * {@code updatedAt} — cả hai cột đều tồn tại trong schema V1.
 *
 * @author Nguyễn Đình Đạt
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * ID của booking tương ứng. FK tới {@code bookings.id} trong DB.
     * Không dùng {@code @ManyToOne} vì entity {@code Booking} chưa tồn tại
     * trên branch này (xem Javadoc lớp).
     */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /**
     * Cổng thanh toán. Luôn lưu bằng tên hằng số (DEMO / VNPAY) nhờ
     * {@link EnumType#STRING}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private PaymentProvider provider;

    /**
     * Khóa chống ghi lặp do client sinh ra (UUID/khóa tự đặt). Kết hợp với
     * {@code bookingId} và {@code provider} tạo thành khoá duy nhất trong DB
     * (ràng buộc {@code uq_payment_attempts_idempotency}).
     */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /**
     * Mã giao dịch do cổng thanh toán trả về sau khi xử lý. Có thể {@code null}
     * cho tới khi provider phản hồi. Khi đã có thì cùng cặp {@code (provider, id)}
     * là duy nhất trong DB (partial unique index).
     */
    @Column(name = "provider_transaction_id", length = 100)
    private String providerTransactionId;

    /**
     * Trạng thái vòng đời. Mặc định {@link PaymentStatus#PENDING} khi tạo mới.
     * Được thay đổi duy nhất qua {@link #markSucceeded(Instant)} và
     * {@link #markFailed(Instant)}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    /**
     * Số tiền khách yêu cầu thanh toán (VND). Phải bằng
     * {@code bookings.payable_amount} tại thời điểm tạo attempt — tầng service
     * (M4-04) sẽ chịu trách nhiệm kiểm tra bất biến này.
     */
    @Column(name = "requested_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal requestedAmount;

    /**
     * Thời điểm provider xác nhận thành công. {@code null} khi chưa phản hồi
     * hoặc kết quả là FAILED.
     */
    @Column(name = "succeeded_at")
    private Instant succeededAt;

    /**
     * Constructor bắt buộc cho JPA. Không dùng trực tiếp — dùng
     * {@link #PaymentAttempt(Long, PaymentProvider, String, BigDecimal)} cho
     * tầng domain.
     */
    protected PaymentAttempt() {
    }

    /**
     * Tạo một attempt mới ở trạng thái {@link PaymentStatus#PENDING}.
     *
     * <p>Các tham số bắt buộc được kiểm tra bằng {@link Objects#requireNonNull}
     * để lỗi được ném ra sớm tại tầng domain, không phải tại tầng DB.
     *
     * <p>{@code idempotencyKey} được trim trước khi lưu: cùng một khoá chỉ
     * khác nhau ở khoảng trắng thì vẫn là một khoá duy nhất, tránh việc DB
     * coi {@code "abc"} và {@code " abc "} là hai khoá khác nhau. Chuỗi chỉ
     * chứa khoảng trắng vẫn bị từ chối.
     *
     * @param bookingId      ID booking tương ứng, bắt buộc, phải dương
     * @param provider       cổng thanh toán, bắt buộc
     * @param idempotencyKey khoá chống ghi lặp do client, bắt buộc, không rỗng sau khi trim
     * @param requestedAmount số tiền yêu cầu thanh toán, bắt buộc, không âm
     * @throws NullPointerException     nếu bất kỳ tham số bắt buộc nào là {@code null}
     * @throws IllegalArgumentException nếu {@code bookingId <= 0}, {@code idempotencyKey} rỗng,
     *                                  hoặc {@code requestedAmount} âm
     */
    public PaymentAttempt(
            Long bookingId,
            PaymentProvider provider,
            String idempotencyKey,
            BigDecimal requestedAmount
    ) {
        this.bookingId = requirePositive(Objects.requireNonNull(bookingId, "bookingId must not be null"),
                "bookingId");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.idempotencyKey = requireNonBlank(
                Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null"),
                "idempotencyKey").trim();
        this.requestedAmount = requireNonNegative(
                Objects.requireNonNull(requestedAmount, "requestedAmount must not be null"),
                "requestedAmount");
        this.status = PaymentStatus.PENDING;
    }

    /**
     * Chuyển trạng thái sang {@link PaymentStatus#SUCCEEDED} và ghi
     * {@link #succeededAt}.
     *
     * <p>Chỉ hợp lệ khi đang ở {@link PaymentStatus#PENDING}. Nếu attempt đã
     * ở trạng thái kết thúc (SUCCEEDED hoặc FAILED) sẽ ném
     * {@link IllegalStateException} — phù hợp với ràng buộc "trạng thái cuối"
     * đã mô tả trong Javadoc lớp.
     *
     * <p>Việc chuyển sang SUCCEEDED có thể bị DB từ chối nếu booking đã có
     * attempt khác thành công (partial unique index
     * {@code uq_payment_attempts_one_success_per_booking}); lỗi này sẽ được
     * ném ra ở tầng {@code save()} dưới dạng {@code DataIntegrityViolationException}.
     *
     * @param now thời điểm thành công (thường do service truyền vào để dễ test)
     * @throws NullPointerException  nếu {@code now} là {@code null}
     * @throws IllegalStateException nếu attempt không ở trạng thái PENDING
     */
    public void markSucceeded(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING payment attempt can be marked SUCCEEDED, current status: " + this.status);
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.succeededAt = now;
    }

    /**
     * Chuyển trạng thái sang {@link PaymentStatus#FAILED}.
     *
     * <p>Chỉ hợp lệ khi đang ở {@link PaymentStatus#PENDING}. Tương tự
     * {@link #markSucceeded(Instant)}, gọi khi attempt đã ở trạng thái kết thúc
     * sẽ ném {@link IllegalStateException}. Trường {@code succeededAt} giữ
     * nguyên {@code null} vì thất bại không ghi nhận "thời điểm thành công".
     *
     * @throws IllegalStateException nếu attempt không ở trạng thái PENDING
     */
    public void markFailed() {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING payment attempt can be marked FAILED, current status: " + this.status);
        }
        this.status = PaymentStatus.FAILED;
    }

    /**
     * Ghi nhận mã giao dịch do cổng thanh toán trả về.
     *
     * <p>Cho phép {@code null} (chưa có phản hồi) hoặc chuỗi không rỗng sau
     * khi trim. Nếu set giá trị rỗng sẽ ném {@link IllegalArgumentException}.
     *
     * <p>Việc set hai attempt cùng {@code (provider, providerTransactionId)} sẽ
     * bị DB từ chối (partial unique index) khi {@code save()}.
     *
     * @param providerTransactionId mã giao dịch hoặc {@code null}
     * @throws IllegalArgumentException nếu chuỗi rỗng sau khi trim
     */
    public void setProviderTransactionId(String providerTransactionId) {
        if (providerTransactionId == null) {
            this.providerTransactionId = null;
            return;
        }
        this.providerTransactionId = requireNonBlank(providerTransactionId, "providerTransactionId");
    }

    // ----- Helpers cho validation (dùng nội bộ) ---------------------------

    private static Long requirePositive(Long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
        return value;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String name) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative, got: " + value);
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    // ----- Getters ---------------------------------------------------------

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public PaymentProvider getProvider() {
        return provider;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public Instant getSucceededAt() {
        return succeededAt;
    }
}
