package com.hotelpms.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hotelpms.payment.dto.CreatePaymentRequest;
import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.port.BookingPaymentPort;
import com.hotelpms.payment.port.BookingPaymentPort.BookingPaymentInfo;
import com.hotelpms.payment.port.PaymentGatewayPort;
import com.hotelpms.payment.port.PaymentGatewayPort.GatewayRequest;
import com.hotelpms.payment.port.PaymentGatewayPort.GatewayResult;
import com.hotelpms.payment.port.PaymentGatewayPort.Outcome;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit test cho {@link PaymentService} — M4-04.
 *
 * <p>Dùng {@link MockitoExtension} và một {@link PlatformTransactionManager} giả lập
 * (chạy callback trực tiếp, không transaction thật) để kiểm tra logic thuần: thứ
 * tự kiểm tra idempotency, so sánh số tiền, ánh xạ lỗi, kiểm tra quyền sở hữu và
 * việc <b>không</b> gọi lại gateway.
 *
 * @author Nguyễn Đình Đạt
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Long BOOKING_ID = 4242L;
    private static final String KEY = "idem-key-001";
    private static final BigDecimal AMOUNT = new BigDecimal("1500000.00");

    /** ID chủ sở hữu booking — lấy từ {@code sub} của JWT. */
    private static final Long OWNER_ID = 99L;

    /** ID của người gọi khác — không phải chủ booking. */
    private static final Long OTHER_USER_ID = 1234L;

    @Mock
    private PaymentAttemptRepository attempts;

    @Mock
    private PaymentGatewayPort gateway;

    @Mock
    private BookingPaymentPort bookingPort;

    @Mock
    private ObjectProvider<PaymentGatewayPort> gatewayProvider;

    @Mock
    private ObjectProvider<BookingPaymentPort> bookingPortProvider;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                attempts, gatewayProvider, bookingPortProvider, noOpTransactionManager());
    }

    /**
     * Transaction manager giả lập: chạy callback ngay, không cần DB.
     */
    private static PlatformTransactionManager noOpTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // no-op
            }

            @Override
            public void rollback(TransactionStatus status) {
                // no-op
            }
        };
    }

    private CreatePaymentRequest request() {
        return new CreatePaymentRequest(BOOKING_ID, KEY, AMOUNT, null);
    }

    private void gatewayAvailable() {
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
    }

    private void bookingPortAvailable() {
        when(bookingPortProvider.getIfAvailable()).thenReturn(bookingPort);
    }

    private void bookingFound() {
        when(bookingPort.findPayableBooking(BOOKING_ID))
                .thenReturn(Optional.of(new BookingPaymentInfo(
                        BOOKING_ID, "BK-001", OWNER_ID, AMOUNT)));
    }

    /**
     * Chuẩn bị luồng hợp lệ: gateway sẵn sàng, booking tồn tại, chưa thanh toán.
     *
     * <p>Không stub {@code supportsOutcomeSimulation()} vì request không chỉ định
     * {@code simulatedOutcome} nên service không gọi tới hàm này.
     */
    private void prepareHappyPath() {
        gatewayAvailable();
        bookingPortAvailable();
        bookingFound();
        when(attempts.existsByBookingIdAndStatus(BOOKING_ID, PaymentStatus.SUCCEEDED)).thenReturn(false);
    }

    /**
     * Giả lập repository: attempt được lưu ở P1 và đọc lại được ở P3.
     *
     * <p>Mỗi lần {@code findById} trả về một thực thể mới — giống đọc lại từ DB.
     */
    private PaymentAttempt stubPersistence() {
        PaymentAttempt persisted = new PaymentAttempt(
                BOOKING_ID, PaymentProvider.DEMO, KEY, AMOUNT);
        ReflectionTestUtils.setField(persisted, "id", 9001L);
        AtomicInteger saveCalls = new AtomicInteger();
        when(attempts.saveAndFlush(any(PaymentAttempt.class))).thenAnswer(invocation -> {
            PaymentAttempt argument = invocation.getArgument(0, PaymentAttempt.class);
            if (saveCalls.getAndIncrement() == 0) {
                return persisted;
            }
            return argument;
        });
        when(attempts.findById(any())).thenAnswer(invocation -> Optional.of(freshAttempt()));
        return persisted;
    }

    /**
     * Tạo attempt mới với id cố định (mô phỏng bản ghi đọc lại từ DB).
     */
    private PaymentAttempt freshAttempt() {
        PaymentAttempt attempt = new PaymentAttempt(
                BOOKING_ID, PaymentProvider.DEMO, KEY, AMOUNT);
        ReflectionTestUtils.setField(attempt, "id", 9001L);
        return attempt;
    }

    // ---------------------------------------------------------------------
    // 1. Idempotency — kiểm tra TRƯỚC booking đã thanh toán
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Cùng key + cùng amount: trả attempt cũ, KHÔNG tạo attempt mới, KHÔNG gọi gateway")
    void sameKeySameAmountReturnsExistingAttemptWithoutCallingGateway() {
        PaymentAttempt existing = existingAttempt(PaymentStatus.SUCCEEDED, AMOUNT);
        when(attempts.findByBookingIdAndProviderAndIdempotencyKey(
                BOOKING_ID, PaymentProvider.DEMO, KEY)).thenReturn(Optional.of(existing));
        bookingPortAvailable();
        bookingFound();

        PaymentService.PaymentOutcome outcome = service.pay(request(), OWNER_ID);

        assertThat(outcome.replay()).isTrue();
        assertThat(outcome.attempt()).isSameAs(existing);
        assertThat(outcome.attempt().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(attempts, never()).saveAndFlush(any(PaymentAttempt.class));
        verify(gateway, never()).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("Cùng key + khác amount: 409 IDEMPOTENCY_AMOUNT_CONFLICT, không gọi gateway")
    void sameKeyDifferentAmountThrowsConflict() {
        PaymentAttempt existing = existingAttempt(PaymentStatus.PENDING, AMOUNT);
        when(attempts.findByBookingIdAndProviderAndIdempotencyKey(
                BOOKING_ID, PaymentProvider.DEMO, KEY)).thenReturn(Optional.of(existing));
        bookingPortAvailable();
        bookingFound();

        CreatePaymentRequest request =
                new CreatePaymentRequest(BOOKING_ID, KEY, new BigDecimal("1600000.00"), null);

        assertThatThrownBy(() -> service.pay(request, OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("IDEMPOTENCY_AMOUNT_CONFLICT");
                });
        verify(gateway, never()).charge(any(GatewayRequest.class));
        verify(attempts, never()).saveAndFlush(any(PaymentAttempt.class));
    }

    @Test
    @DisplayName("Idempotency kiểm tra TRƯỚC: attempt đã tồn tại thì không gọi existsByBookingIdAndStatus")
    void idempotencyCheckRunsBeforeAlreadyPaidCheck() {
        PaymentAttempt existing = existingAttempt(PaymentStatus.SUCCEEDED, AMOUNT);
        when(attempts.findByBookingIdAndProviderAndIdempotencyKey(
                BOOKING_ID, PaymentProvider.DEMO, KEY)).thenReturn(Optional.of(existing));
        bookingPortAvailable();
        bookingFound();

        PaymentService.PaymentOutcome outcome = service.pay(request(), OWNER_ID);

        assertThat(outcome.replay()).isTrue();
        verify(attempts, never()).existsByBookingIdAndStatus(any(), any());
    }

    @Test
    @DisplayName("BigDecimal 1000000 và 1000000.00 được coi là KHỚP (dùng compareTo)")
    void amountsAreComparedWithCompareTo() {
        PaymentAttempt existing = existingAttempt(PaymentStatus.SUCCEEDED, new BigDecimal("1000000.00"));
        when(attempts.findByBookingIdAndProviderAndIdempotencyKey(
                BOOKING_ID, PaymentProvider.DEMO, KEY)).thenReturn(Optional.of(existing));
        bookingPortAvailable();
        when(bookingPort.findPayableBooking(BOOKING_ID))
                .thenReturn(Optional.of(new BookingPaymentInfo(
                        BOOKING_ID, "BK-001", OWNER_ID, new BigDecimal("1000000.00"))));

        CreatePaymentRequest request =
                new CreatePaymentRequest(BOOKING_ID, KEY, new BigDecimal("1000000"), null);

        PaymentService.PaymentOutcome outcome = service.pay(request, OWNER_ID);

        assertThat(outcome.replay()).isTrue();
        assertThat(outcome.attempt()).isSameAs(existing);
    }

    // ---------------------------------------------------------------------
    // 2. Quyền sở hữu (chống IDOR / chống chiếm booking)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("POST: người gọi KHÔNG phải chủ booking → 404 BOOKING_NOT_FOUND, không tạo attempt, không gọi gateway")
    void payRejectsRequesterWhoIsNotBookingOwner() {
        gatewayAvailable();
        bookingPortAvailable();
        bookingFound();

        assertThatThrownBy(() -> service.pay(request(), OTHER_USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(404);
                    assertThat(api.getCode()).isEqualTo("BOOKING_NOT_FOUND");
                });
        verify(attempts, never()).saveAndFlush(any(PaymentAttempt.class));
        verify(gateway, never()).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("POST: không xác định được người gọi (userId null) → fail closed 401, không tạo attempt")
    void payFailsClosedWhenRequesterIsUnknown() {
        gatewayAvailable();
        bookingPortAvailable();

        assertThatThrownBy(() -> service.pay(request(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(401);
                    assertThat(api.getCode()).isEqualTo("UNAUTHORIZED");
                });
        verify(attempts, never()).saveAndFlush(any(PaymentAttempt.class));
        verify(gateway, never()).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("POST: booking không có customerId → fail closed 503, không tạo attempt")
    void payFailsClosedWhenBookingHasNoCustomerId() {
        gatewayAvailable();
        bookingPortAvailable();
        when(bookingPort.findPayableBooking(BOOKING_ID))
                .thenReturn(Optional.of(new BookingPaymentInfo(
                        BOOKING_ID, "BK-001", null, AMOUNT)));

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(503);
                    assertThat(api.getCode()).isEqualTo("PAYMENT_NOT_AVAILABLE");
                });
        verify(attempts, never()).saveAndFlush(any(PaymentAttempt.class));
        verify(gateway, never()).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("POST: replay của attempt đã có vẫn phải xác minh chủ booking — người khác bị chặn")
    void payReplayAlsoEnforcesOwnership() {
        PaymentAttempt existing = existingAttempt(PaymentStatus.SUCCEEDED, AMOUNT);
        when(attempts.findByBookingIdAndProviderAndIdempotencyKey(
                BOOKING_ID, PaymentProvider.DEMO, KEY)).thenReturn(Optional.of(existing));
        bookingPortAvailable();
        bookingFound();

        assertThatThrownBy(() -> service.pay(request(), OTHER_USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(404);
                    assertThat(api.getCode()).isEqualTo("BOOKING_NOT_FOUND");
                });
        verify(attempts, never()).saveAndFlush(any(PaymentAttempt.class));
        verify(gateway, never()).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("POST: người khác dùng cùng key nhưng khác amount cũng bị chặn 404 (không rò rỉ key đã tồn tại)")
    void payReplayByNonOwnerWithDifferentAmountStillHidesKeyExistence() {
        PaymentAttempt existing = existingAttempt(PaymentStatus.SUCCEEDED, AMOUNT);
        when(attempts.findByBookingIdAndProviderAndIdempotencyKey(
                BOOKING_ID, PaymentProvider.DEMO, KEY)).thenReturn(Optional.of(existing));
        bookingPortAvailable();
        bookingFound();

        CreatePaymentRequest request =
                new CreatePaymentRequest(BOOKING_ID, KEY, new BigDecimal("1600000.00"), null);

        assertThatThrownBy(() -> service.pay(request, OTHER_USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(404);
                    assertThat(api.getCode()).isEqualTo("BOOKING_NOT_FOUND");
                    // KHÔNG được phản hồi khác 409 IDEMPOTENCY_AMOUNT_CONFLICT —
                    // mã đó chứng minh key đã tồn tại trong DB.
                    assertThat(api.getCode()).isNotEqualTo("IDEMPOTENCY_AMOUNT_CONFLICT");
                });
        verify(gateway, never()).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("GET attempt: người gọi KHÔNG phải chủ booking → 404 PAYMENT_ATTEMPT_NOT_FOUND")
    void findAttemptRejectsRequesterWhoIsNotBookingOwner() {
        when(attempts.findById(any())).thenReturn(Optional.of(freshAttempt()));
        bookingPortAvailable();
        bookingFound();

        assertThatThrownBy(() -> service.findAttempt(9001L, OTHER_USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(404);
                    assertThat(api.getCode()).isEqualTo("PAYMENT_ATTEMPT_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("GET attempt: chủ booking được thấy attempt của mình")
    void findAttemptReturnsAttemptForOwner() {
        PaymentAttempt attempt = freshAttempt();
        when(attempts.findById(any())).thenReturn(Optional.of(attempt));
        bookingPortAvailable();
        bookingFound();

        PaymentAttempt found = service.findAttempt(9001L, OWNER_ID);

        assertThat(found).isSameAs(attempt);
    }

    @Test
    @DisplayName("GET attempt: thiếu BookingPaymentPort → fail closed 503, KHÔNG trả attempt")
    void findAttemptFailsClosedWhenBookingPortMissing() {
        when(attempts.findById(any())).thenReturn(Optional.of(freshAttempt()));
        when(bookingPortProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.findAttempt(9001L, OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(503);
                    assertThat(api.getCode()).isEqualTo("PAYMENT_NOT_AVAILABLE");
                });
    }

    @Test
    @DisplayName("GET attempt: không xác định được người gọi (userId null) → fail closed 401")
    void findAttemptFailsClosedWhenRequesterIsUnknown() {
        when(attempts.findById(any())).thenReturn(Optional.of(freshAttempt()));
        bookingPortAvailable();

        assertThatThrownBy(() -> service.findAttempt(9001L, null))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(401);
                    assertThat(api.getCode()).isEqualTo("UNAUTHORIZED");
                });
    }

    @Test
    @DisplayName("GET attempt: không tồn tại → null (controller trả 404 PAYMENT_ATTEMPT_NOT_FOUND)")
    void findAttemptReturnsNullWhenMissing() {
        when(attempts.findById(any())).thenReturn(Optional.empty());

        assertThat(service.findAttempt(404L, OWNER_ID)).isNull();
    }

    // ---------------------------------------------------------------------
    // 3. Lỗi nghiệp vụ
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("amount khác payableAmount của booking: 409 PAYMENT_AMOUNT_MISMATCH")
    void amountMismatchThrowsConflict() {
        gatewayAvailable();
        bookingPortAvailable();
        when(bookingPort.findPayableBooking(BOOKING_ID))
                .thenReturn(Optional.of(new BookingPaymentInfo(
                        BOOKING_ID, "BK-001", OWNER_ID, new BigDecimal("2000000.00"))));

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("PAYMENT_AMOUNT_MISMATCH");
                });
        verify(gateway, never()).charge(any(GatewayRequest.class));
        verify(attempts, never()).saveAndFlush(any(PaymentAttempt.class));
    }

    @Test
    @DisplayName("Booking không tồn tại: 404 BOOKING_NOT_FOUND")
    void missingBookingThrowsNotFound() {
        gatewayAvailable();
        bookingPortAvailable();
        when(bookingPort.findPayableBooking(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(404);
                    assertThat(api.getCode()).isEqualTo("BOOKING_NOT_FOUND");
                });
        verify(gateway, never()).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("Booking đã có attempt SUCCEEDED: 409 BOOKING_ALREADY_PAID")
    void alreadyPaidThrowsConflict() {
        gatewayAvailable();
        bookingPortAvailable();
        bookingFound();
        when(attempts.existsByBookingIdAndStatus(BOOKING_ID, PaymentStatus.SUCCEEDED)).thenReturn(true);

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("BOOKING_ALREADY_PAID");
                });
        verify(gateway, never()).charge(any(GatewayRequest.class));
        verify(attempts, never()).saveAndFlush(any(PaymentAttempt.class));
    }

    // ---------------------------------------------------------------------
    // 4. Thiếu adapter / gateway → 503 PAYMENT_NOT_AVAILABLE
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Thiếu PaymentGatewayPort: 503 PAYMENT_NOT_AVAILABLE")
    void missingGatewayThrowsServiceUnavailable() {
        when(gatewayProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(503);
                    assertThat(api.getCode()).isEqualTo("PAYMENT_NOT_AVAILABLE");
                });
    }

    @Test
    @DisplayName("Thiếu BookingPaymentPort: 503 PAYMENT_NOT_AVAILABLE (M4-04 chưa có adapter production)")
    void missingBookingPortThrowsServiceUnavailable() {
        gatewayAvailable();
        when(bookingPortProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(503);
                    assertThat(api.getCode()).isEqualTo("PAYMENT_NOT_AVAILABLE");
                });
        verify(gateway, never()).charge(any(GatewayRequest.class));
    }

    // ---------------------------------------------------------------------
    // 5. simulatedOutcome chỉ được phép khi gateway hỗ trợ mô phỏng
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("simulatedOutcome bị từ chối 400 khi gateway KHÔNG hỗ trợ mô phỏng")
    void simulatedOutcomeRejectedWhenGatewayDoesNotSupportIt() {
        gatewayAvailable();
        when(gateway.supportsOutcomeSimulation()).thenReturn(false);

        CreatePaymentRequest request =
                new CreatePaymentRequest(BOOKING_ID, KEY, AMOUNT, Outcome.FAILURE);

        assertThatThrownBy(() -> service.pay(request, OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(400);
                    assertThat(api.getCode()).isEqualTo("SIMULATED_OUTCOME_NOT_ALLOWED");
                });
        verify(gateway, never()).charge(any(GatewayRequest.class));
        verify(attempts, never()).saveAndFlush(any(PaymentAttempt.class));
    }

    @Test
    @DisplayName("simulatedOutcome được chuyển xuống gateway khi gateway hỗ trợ mô phỏng")
    void simulatedOutcomeIsForwardedToGatewayWhenSupported() {
        gatewayAvailable();
        when(gateway.supportsOutcomeSimulation()).thenReturn(true);
        bookingPortAvailable();
        bookingFound();
        when(attempts.existsByBookingIdAndStatus(BOOKING_ID, PaymentStatus.SUCCEEDED)).thenReturn(false);
        when(gateway.charge(any(GatewayRequest.class)))
                .thenReturn(new GatewayResult(Outcome.FAILURE, "DEMO-4242-idem-key-001", "thất bại"));
        stubPersistence();

        service.pay(new CreatePaymentRequest(BOOKING_ID, KEY, AMOUNT, Outcome.FAILURE), OWNER_ID);

        verify(gateway, times(1)).charge(any(GatewayRequest.class));
    }

    // ---------------------------------------------------------------------
    // 6. Luồng hợp lệ
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("SUCCESS: attempt được tạo, gateway được gọi, trạng thái SUCCEEDED")
    void successfulPaymentMarksAttemptSucceeded() {
        prepareHappyPath();
        when(gateway.charge(any(GatewayRequest.class)))
                .thenReturn(new GatewayResult(Outcome.SUCCESS, "DEMO-4242-idem-key-001", "thành công"));
        PaymentAttempt persisted = stubPersistence();

        PaymentService.PaymentOutcome outcome = service.pay(request(), OWNER_ID);

        assertThat(outcome.replay()).isFalse();
        assertThat(outcome.attempt().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(outcome.attempt().getSucceededAt()).isNotNull();
        assertThat(outcome.attempt().getProviderTransactionId())
                .isEqualTo("DEMO-4242-idem-key-001");
        verify(gateway, times(1)).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("FAILURE: attempt ở trạng thái FAILED, succeededAt = null (không phải lỗi API)")
    void failedPaymentMarksAttemptFailed() {
        prepareHappyPath();
        when(gateway.supportsOutcomeSimulation()).thenReturn(true);
        when(gateway.charge(any(GatewayRequest.class)))
                .thenReturn(new GatewayResult(Outcome.FAILURE, "DEMO-4242-idem-key-001", "thất bại"));
        PaymentAttempt persisted = stubPersistence();

        PaymentService.PaymentOutcome outcome = service.pay(
                new CreatePaymentRequest(BOOKING_ID, KEY, AMOUNT, Outcome.FAILURE), OWNER_ID);

        assertThat(outcome.replay()).isFalse();
        assertThat(outcome.attempt().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(outcome.attempt().getSucceededAt()).isNull();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private PaymentAttempt existingAttempt(PaymentStatus status, BigDecimal amount) {
        PaymentAttempt attempt = new PaymentAttempt(BOOKING_ID, PaymentProvider.DEMO, KEY, amount);
        if (status == PaymentStatus.SUCCEEDED) {
            attempt.markSucceeded(Instant.now());
        } else if (status == PaymentStatus.FAILED) {
            attempt.markFailed();
        }
        return attempt;
    }
}
