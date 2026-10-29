package com.hotelpms.payment.dto;

import com.hotelpms.payment.port.PaymentGatewayPort.Outcome;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * DTO yêu cầu tạo một lần thử thanh toán.
 *
 * <p>Nhận qua body của {@code POST /api/v1/payments}.
 *
 * <p><b>Không có trường {@code provider}</b>: M4-04 chỉ hỗ trợ cổng DEMO, nên
 * provider luôn là {@code DEMO}. Không để client tự chọn provider để tránh gọi
 * nhầm cổng.
 *
 * <p><b>Không có trường {@code customerId}</b>: người dùng lấy từ JWT do server ký.
 *
 * <p><b>Trường {@code simulatedOutcome}</b> chỉ được dùng ở môi trường demo:
 * tầng service sẽ từ chối bằng 400 {@code SIMULATED_OUTCOME_NOT_ALLOWED} nếu cổng
 * thanh toán không hỗ trợ mô phỏng — nhờ vậy client không thể điều khiển kết quả
 * thanh toán thật.
 *
 * @param bookingId        ID booking cần thanh toán, phải dương
 * @param idempotencyKey   khóa chống ghi lặp do client sinh ra, tối đa 100 ký tự
 *                         (khớp cột {@code idempotency_key} trong schema V1)
 * @param amount           số tiền yêu cầu thanh toán; phải bằng số tiền phải trả
 *                         của booking
 * @param simulatedOutcome kết quả mong muốn {@code SUCCESS}/{@code FAILURE} khi
 *                         mô phỏng; {@code null} nghĩa là để cổng quyết định
 *
 * @author Nguyễn Đình Đạt
 */
public record CreatePaymentRequest(

        @NotNull(message = "bookingId không được để trống")
        @Positive(message = "bookingId phải là số dương")
        Long bookingId,

        @NotBlank(message = "idempotencyKey không được để trống")
        @Size(max = 100, message = "idempotencyKey không được vượt quá 100 ký tự")
        String idempotencyKey,

        @NotNull(message = "amount không được để trống")
        @DecimalMin(value = "0.00", message = "amount không được âm")
        @Digits(integer = 13, fraction = 2, message = "amount tối đa 13 chữ số và 2 chữ số thập phân")
        BigDecimal amount,

        Outcome simulatedOutcome

) {}
