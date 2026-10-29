package com.hotelpms.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hotelpms.payment.dto.CreatePaymentRequest;
import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.service.PaymentService;
import com.hotelpms.payment.service.PaymentService.PaymentOutcome;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Test contract cho {@link PaymentController} — M4-04.
 *
 * <p><b>Lưu ý:</b> test này <b>không</b> dùng {@code SecurityConfig} thật (nơi
 * {@code anyRequest().denyAll()} khiến endpoint trả 403 cho tới khi quyền truy cập
 * được mở). Test tập trung vào hợp đồng HTTP: mã trạng thái và hình dạng JSON.
 *
 * <p>JWT được đưa vào {@link SecurityContextHolder} trực tiếp (vì filter chain
 * bị tắt bằng {@code addFilters = false}) để chứng minh controller lấy user ID
 * từ {@code sub} của token chứ không lấy từ body — dữ liệu do client kiểm soát.
 *
 * @author Nguyễn Đình Đạt
 */
@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {

    private static final Long BOOKING_ID = 88L;
    private static final String KEY = "idem-ctrl-001";
    private static final BigDecimal AMOUNT = new BigDecimal("1200000.00");

    /** User ID trong JWT. */
    private static final Long USER_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService payments;

    /**
     * {@code @WebMvcTest} bao gồm các bean {@code Converter}, nên
     * {@code DatabaseJwtAuthenticationConverter} (module auth) cũng được tạo và cần
     * {@code UserRepository}. Ta cung cấp một mock bean thay vì kéo cả tầng JPA vào
     * test slice — M4-04 không sửa module auth.
     */
    @MockitoBean
    private com.hotelpms.auth.repository.UserRepository users;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private PaymentAttempt attempt(PaymentStatus status) {
        PaymentAttempt attempt = new PaymentAttempt(
                BOOKING_ID, PaymentProvider.DEMO, KEY, AMOUNT);
        if (status == PaymentStatus.SUCCEEDED) {
            attempt.markSucceeded(Instant.now());
        } else if (status == PaymentStatus.FAILED) {
            attempt.markFailed();
        }
        return attempt;
    }

    /**
     * Đưa một JWT (với {@code sub} cho trước) vào {@link SecurityContextHolder}
     * để controller nhận được {@code @AuthenticationPrincipal Jwt}.
     *
     * @param subject giá trị {@code sub}; {@code null} nghĩa là không đăng nhập
     */
    private void authenticate(String subject) {
        SecurityContextHolder.clearContext();
        if (subject == null) {
            return;
        }
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    @DisplayName("POST hợp lệ → 201 với body PaymentAttemptResponse đầy đủ field")
    void createReturnsCreatedWithFullBody() throws Exception {
        authenticate(String.valueOf(USER_ID));
        PaymentAttempt attempt = attempt(PaymentStatus.SUCCEEDED);
        when(payments.pay(any(CreatePaymentRequest.class), eq(USER_ID)))
                .thenReturn(new PaymentOutcome(attempt, false));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookingId": 88,
                                  "idempotencyKey": "idem-ctrl-001",
                                  "amount": 1200000.00
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(88))
                .andExpect(jsonPath("$.provider").value("DEMO"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.requestedAmount").value(1200000.00))
                .andExpect(jsonPath("$.succeededAt").exists());
    }

    @Test
    @DisplayName("POST lặp lại cùng key/cùng amount → 200 (không phải 201)")
    void replayReturnsOk() throws Exception {
        authenticate(String.valueOf(USER_ID));
        PaymentAttempt attempt = attempt(PaymentStatus.SUCCEEDED);
        when(payments.pay(any(CreatePaymentRequest.class), eq(USER_ID)))
                .thenReturn(new PaymentOutcome(attempt, true));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookingId": 88,
                                  "idempotencyKey": "idem-ctrl-001",
                                  "amount": 1200000.00
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("POST: user ID được lấy từ sub của JWT và truyền xuống service")
    void createPassesUserIdFromJwtSubjectToService() throws Exception {
        authenticate("77");
        PaymentAttempt attempt = attempt(PaymentStatus.SUCCEEDED);
        when(payments.pay(any(CreatePaymentRequest.class), eq(77L)))
                .thenReturn(new PaymentOutcome(attempt, false));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookingId": 88,
                                  "idempotencyKey": "idem-ctrl-001",
                                  "amount": 1200000.00
                                }
                                """))
                .andExpect(status().isCreated());

        verify(payments).pay(any(CreatePaymentRequest.class), eq(77L));
    }

    @Test
    @DisplayName("POST: JWT không có sub → user ID null, service phải fail closed (không tin body)")
    void createWithoutJwtSubjectPassesNullUserId() throws Exception {
        PaymentAttempt attempt = attempt(PaymentStatus.SUCCEEDED);
        when(payments.pay(any(CreatePaymentRequest.class), eq(null)))
                .thenReturn(new PaymentOutcome(attempt, false));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookingId": 88,
                                  "idempotencyKey": "idem-ctrl-001",
                                  "amount": 1200000.00
                                }
                                """))
                .andExpect(status().isCreated());

        verify(payments).pay(any(CreatePaymentRequest.class), eq(null));
    }

    @Test
    @DisplayName("POST: người gọi không phải chủ booking → 404 BOOKING_NOT_FOUND")
    void createByNonOwnerReturnsNotFound() throws Exception {
        authenticate(String.valueOf(USER_ID));
        when(payments.pay(any(CreatePaymentRequest.class), eq(USER_ID)))
                .thenThrow(new ApiException(
                        HttpStatus.NOT_FOUND,
                        "BOOKING_NOT_FOUND",
                        "Không tìm thấy booking"));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookingId": 88,
                                  "idempotencyKey": "idem-ctrl-001",
                                  "amount": 1200000.00
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST cùng key/khác amount → 409 IDEMPOTENCY_AMOUNT_CONFLICT")
    void amountConflictReturnsConflict() throws Exception {
        authenticate(String.valueOf(USER_ID));
        when(payments.pay(any(CreatePaymentRequest.class), eq(USER_ID)))
                .thenThrow(new ApiException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_AMOUNT_CONFLICT",
                        "idempotencyKey đã dùng với số tiền khác"));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookingId": 88,
                                  "idempotencyKey": "idem-ctrl-001",
                                  "amount": 1300000.00
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_AMOUNT_CONFLICT"));
    }

    @Test
    @DisplayName("POST thiếu field bắt buộc → 400 VALIDATION_ERROR")
    void missingRequiredFieldReturnsBadRequest() throws Exception {
        authenticate(String.valueOf(USER_ID));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId": 88}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST amount âm → 400 VALIDATION_ERROR")
    void negativeAmountReturnsBadRequest() throws Exception {
        authenticate(String.valueOf(USER_ID));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookingId": 88,
                                  "idempotencyKey": "idem-ctrl-001",
                                  "amount": -1.00
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST khi gateway/adapter không khả dụng → 503 PAYMENT_NOT_AVAILABLE")
    void unavailableServiceReturnsServiceUnavailable() throws Exception {
        authenticate(String.valueOf(USER_ID));
        when(payments.pay(any(CreatePaymentRequest.class), eq(USER_ID)))
                .thenThrow(new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "PAYMENT_NOT_AVAILABLE",
                        "Cổng thanh toán hiện không khả dụng"));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookingId": 88,
                                  "idempotencyKey": "idem-ctrl-001",
                                  "amount": 1200000.00
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_AVAILABLE"));
    }

    @Test
    @DisplayName("GET attempts/{id} hợp lệ → 200 với body đúng shape")
    void findAttemptReturnsOk() throws Exception {
        authenticate(String.valueOf(USER_ID));
        when(payments.findAttempt(1L, USER_ID)).thenReturn(attempt(PaymentStatus.PENDING));

        mockMvc.perform(get("/api/v1/payments/attempts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(88))
                .andExpect(jsonPath("$.provider").value("DEMO"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedAmount").value(1200000.00));
    }

    @Test
    @DisplayName("GET attempts/{id}: user ID lấy từ sub của JWT được truyền xuống service")
    void findAttemptPassesUserIdFromJwtSubjectToService() throws Exception {
        authenticate("55");
        when(payments.findAttempt(1L, 55L)).thenReturn(attempt(PaymentStatus.PENDING));

        mockMvc.perform(get("/api/v1/payments/attempts/1"))
                .andExpect(status().isOk());

        verify(payments).findAttempt(1L, 55L);
    }

    @Test
    @DisplayName("GET attempts/{id} không tồn tại → 404 PAYMENT_ATTEMPT_NOT_FOUND")
    void findMissingAttemptReturnsNotFound() throws Exception {
        authenticate(String.valueOf(USER_ID));
        when(payments.findAttempt(999L, USER_ID)).thenReturn(null);

        mockMvc.perform(get("/api/v1/payments/attempts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ATTEMPT_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET attempts/{id}: không sở hữu → 404 PAYMENT_ATTEMPT_NOT_FOUND (không tiết lộ attempt)")
    void findAttemptOfOtherUserReturnsNotFound() throws Exception {
        authenticate(String.valueOf(USER_ID));
        when(payments.findAttempt(1L, USER_ID))
                .thenThrow(new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PAYMENT_ATTEMPT_NOT_FOUND",
                        "Không tìm thấy lần thử thanh toán"));

        mockMvc.perform(get("/api/v1/payments/attempts/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ATTEMPT_NOT_FOUND"));
    }
}
