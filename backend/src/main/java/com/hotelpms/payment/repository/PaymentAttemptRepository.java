package com.hotelpms.payment.repository;

import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.entity.PaymentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository cho entity {@link PaymentAttempt}.
 *
 * <p>Các finder được đặt tên theo quy ước của Spring Data JPA
 * (Derived Query Methods) để Spring tự sinh implementation — không cần viết
 * {@code @Query} cho những truy vấn đơn giản. Mọi method trả về
 * {@link Optional} cho lookup có thể không thấy, và trả về {@code boolean}
 * hoặc {@code List} cho các truy vấn nghiệp vụ.
 *
 * <h2>Vai trò với idempotency và constraint unique</h2>
 * <ul>
 *   <li>{@link #findByBookingIdAndProviderAndIdempotencyKey} dùng để tra cứu
 *       một attempt đã tồn tại trước khi insert — phục vụ cơ chế retry
 *       idempotent khi client gửi lại cùng {@code idempotencyKey} do mạng lỗi.</li>
 *   <li>{@link #existsByBookingIdAndStatus} cho phép tầng service kiểm tra
 *       nhanh xem một booking đã có attempt SUCCEEDED chưa, trước khi cố
 *       chuyển sang trạng thái đó (DB vẫn là rào cuối cùng nhờ partial unique
 *       index {@code uq_payment_attempts_one_success_per_booking}).</li>
 *   <li>{@link #findByProviderAndProviderTransactionId} phục vụ callback
 *       webhook từ cổng thanh toán: khi provider gọi lại với
 *       {@code providerTransactionId}, repo tra ra attempt tương ứng.</li>
 * </ul>
 *
 * @author Nguyễn Đình Đạt
 */
@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    /**
     * Tra cứu attempt theo khoá idempotency. Thường dùng trong luồng:
     * <ol>
     *   <li>Client gửi request với {@code idempotencyKey}.</li>
     *   <li>Service gọi method này trước khi tạo mới — nếu đã tồn tại, trả
     *       lại attempt cũ thay vì insert bản sao.</li>
     * </ol>
     *
     * @param bookingId      ID booking
     * @param provider       cổng thanh toán
     * @param idempotencyKey khoá chống ghi lặp
     * @return attempt tương ứng hoặc {@link Optional#empty()} nếu chưa tồn tại
     */
    Optional<PaymentAttempt> findByBookingIdAndProviderAndIdempotencyKey(
            Long bookingId, PaymentProvider provider, String idempotencyKey);

    /**
     * Kiểm tra nhanh một booking đã có attempt ở trạng thái chỉ định chưa.
     * Dùng để chặn việc tạo attempt SUCCEEDED thứ hai trước khi DB từ chối.
     *
     * @param bookingId ID booking cần kiểm tra
     * @param status    trạng thái cần hỏi (thường là {@link PaymentStatus#SUCCEEDED})
     * @return {@code true} nếu đã tồn tại
     */
    boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    /**
     * Tìm attempt mới nhất của một booking theo trạng thái.
     * Phục vụ việc khôi phục phiên thanh toán (tiếp tục polling một attempt
     * đang PENDING) hoặc tra lại attempt SUCCEEDED gần nhất.
     *
     * @param bookingId ID booking
     * @param status    trạng thái cần lọc
     * @return attempt mới nhất hoặc {@link Optional#empty()}
     */
    Optional<PaymentAttempt> findFirstByBookingIdAndStatusOrderByCreatedAtDesc(
            Long bookingId, PaymentStatus status);

    /**
     * Lấy tất cả attempt của một booking, sắp xếp mới nhất trước.
     * Dùng cho màn hình lịch sử thanh toán / debug khi cần.
     *
     * @param bookingId ID booking
     * @return danh sách attempt (rỗng nếu booking chưa từng có attempt)
     */
    List<PaymentAttempt> findByBookingIdOrderByCreatedAtDesc(Long bookingId);

    /**
     * Tra cứu attempt theo mã giao dịch mà cổng thanh toán trả về.
     * Áp dụng cho cả hai cổng DEMO và VNPay — tên mã nội bộ của DEMO cũng
     * phải là duy nhất để tránh nhầm lẫn giữa các lần chạy test.
     *
     * <p>Do cột {@code provider_transaction_id} có thể {@code null} và có
     * partial unique index, khoá truy vấn luôn kèm {@code provider}.
     *
     * @param provider              cổng thanh toán
     * @param providerTransactionId mã giao dịch do provider cấp
     * @return attempt tương ứng hoặc {@link Optional#empty()}
     */
    Optional<PaymentAttempt> findByProviderAndProviderTransactionId(
            PaymentProvider provider, String providerTransactionId);
}
