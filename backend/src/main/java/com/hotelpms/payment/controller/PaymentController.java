package com.hotelpms.payment.controller;

import com.hotelpms.payment.dto.CreatePaymentRequest;
import com.hotelpms.payment.dto.PaymentAttemptResponse;
import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.service.PaymentService;
import com.hotelpms.payment.service.PaymentService.PaymentOutcome;
import com.hotelpms.shared.api.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API thanh toán demo (M4-04).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/payments} — tạo lần thử thanh toán.</li>
 *   <li>{@code GET /api/v1/payments/attempts/{id}} — xem trạng thái một lần thử.</li>
 * </ul>
 *
 * <h2>Cảnh báo về quyền truy cập</h2>
 * <p><b>Hiện tại hai endpoint này có thể trả HTTP 403 {@code FORBIDDEN}.</b>
 * {@code shared/config/SecurityConfig} đang dùng
 * {@code authorizeHttpRequests(... .anyRequest().denyAll())} và chưa có rule cho
 * {@code /api/v1/payments/**}. M4-04 <b>không</b> sửa {@code SecurityConfig}
 * (thuộc phạm vi sở hữu của Nguyễn Ngọc Bảo Long — module {@code shared}).
 * Endpoint sẽ chạy được sau khi quyền truy cập được mở.
 *
 * <p>Đây cũng là tình huống tương tự {@code GET /api/v1/pricing/quote} đã được
 * ghi nhận trong {@code docs/pricing/README.md}.
 *
 * <h2>Quyền sở hữu</h2>
 * <p>Cả hai endpoint đều lấy <b>user ID từ {@code sub} của JWT</b> (do server ký,
 * không phải dữ liệu client tự do) và truyền xuống {@link PaymentService} để kiểm
 * tra người gọi là chủ sở hữu của booking. Không bao giờ tin {@code customerId}
 * trong request body. Nếu không xác minh được quyền sở hữu thì <b>fail closed</b>:
 * trả 404 để không tiết lộ sự tồn tại của dữ liệu của người khác.
 *
 * @author Nguyễn Đình Đạt
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * Tạo lần thử thanh toán demo.
     *
     * <p>Trả 201 khi tạo attempt mới, 200 khi tái sử dụng attempt đã tồn tại
     * (idempotent — cùng {@code idempotencyKey} và cùng số tiền).
     *
     * @param request yêu cầu thanh toán
     * @param jwt     token của người đang đăng nhập; {@code sub} là user ID
     * @return thông tin attempt
     */
    @PostMapping
    public ResponseEntity<PaymentAttemptResponse> create(
            @Valid @RequestBody CreatePaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long customerId = currentUserId(jwt);
        PaymentOutcome outcome = payments.pay(request, customerId);
        HttpStatus status = outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(PaymentAttemptResponse.from(outcome.attempt()));
    }

    /**
     * Xem trạng thái một lần thử thanh toán.
     *
     * @param id  ID attempt
     * @param jwt token của người đang đăng nhập; {@code sub} là user ID
     * @return thông tin attempt
     */
    @GetMapping("/attempts/{id}")
    public PaymentAttemptResponse findById(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        Long customerId = currentUserId(jwt);
        PaymentAttempt attempt = payments.findAttempt(id, customerId);
        if (attempt == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PAYMENT_ATTEMPT_NOT_FOUND",
                    "Không tìm thấy lần thử thanh toán");
        }
        return PaymentAttemptResponse.from(attempt);
    }

    /**
     * Lấy user ID của người đang đăng nhập từ JWT.
     *
     * @param jwt token của người đang đăng nhập
     * @return user ID, hoặc {@code null} nếu token không có {@code sub}
     */
    private Long currentUserId(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(subject.trim());
        } catch (NumberFormatException invalid) {
            // Subject không phải số → không xác minh được chủ sở hữu → fail closed.
            return null;
        }
    }
}
