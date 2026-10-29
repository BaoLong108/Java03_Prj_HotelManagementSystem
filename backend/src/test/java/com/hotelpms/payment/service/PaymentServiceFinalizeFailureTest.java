package com.hotelpms.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Test cho trường hợp <b>gateway đã trả kết quả nhưng pha finalize không lưu được</b>
 * — M4-04.
 *
 * <p><b>Mô hình giả lập:</b> mỗi lần gọi {@code attempts.findById(...)} trả về một
 * <b>thực thể mới</b>, giống như đọc lại từ DB. Nhờ vậy khi pha finalize sửa trạng
 * thái rồi lỗi, bản ghi đã lưu ở pha P1 vẫn giữ {@code PENDING} — mô phỏng đúng
 * hành vi rollback của JPA.
 *
 * <p>Các bất biến được bảo vệ:
 * <ul>
 *   <li>Attempt giữ nguyên {@code PENDING}; không tuyên bố {@code SUCCEEDED} khi DB
 *       chưa lưu {@code SUCCEEDED}.</li>
 *   <li>Không gọi lại gateway (tránh thu tiền hai lần).</li>
 *   <li>Không suy luận "gateway đã SUCCESS" chỉ từ việc attempt có
 *       {@code providerTransactionId}.</li>
 *   <li>Chỉ coi {@link IllegalStateException} là replay khi đọc lại được trạng
 *       thái thực tế và trạng thái đó hợp lệ.</li>
 * </ul>
 *
 * @author Nguyễn Đình Đạt
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceFinalizeFailureTest {

    private static final Long BOOKING_ID = 777L;
    private static final String KEY = "idem-finalize-001";
    private static final BigDecimal AMOUNT = new BigDecimal("800000.00");
    private static final Long ATTEMPT_ID = 5001L;

    /** ID chủ sở hữu booking — lấy từ {@code sub} của JWT. */
    private static final Long OWNER_ID = 5L;

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

    /**
     * Chuẩn bị luồng tới bước gọi gateway: gateway sẵn sàng, booking tồn tại,
     * booking chưa có attempt SUCCEEDED.
     *
     * <p>Không stub {@code supportsOutcomeSimulation()} vì request không chỉ định
     * {@code simulatedOutcome} nên service không gọi tới hàm này.
     */
    private void prepareUntilGatewayCall() {
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(bookingPortProvider.getIfAvailable()).thenReturn(bookingPort);
        when(bookingPort.findPayableBooking(BOOKING_ID))
                .thenReturn(Optional.of(new BookingPaymentInfo(BOOKING_ID, "BK-F", OWNER_ID, AMOUNT)));
        when(attempts.existsByBookingIdAndStatus(BOOKING_ID, PaymentStatus.SUCCEEDED)).thenReturn(false);
    }

    /**
     * Tạo một thực thể attempt mới với id cố định (mô phỏng bản ghi trong DB).
     */
    private PaymentAttempt attemptInDatabase() {
        PaymentAttempt attempt = new PaymentAttempt(BOOKING_ID, PaymentProvider.DEMO, KEY, AMOUNT);
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
        return attempt;
    }

    /**
     * Stub {@code saveAndFlush}: lần đầu (P1 reserve) trả attempt đã lưu, các lần
     * sau (P3 finalize) dùng hành vi truyền vào.
     */
    private void stubSaveAndFlush(
            PaymentAttempt persisted,
            Function<PaymentAttempt, PaymentAttempt> onFinalize) {
        AtomicInteger call = new AtomicInteger();
        when(attempts.saveAndFlush(any(PaymentAttempt.class))).thenAnswer(invocation -> {
            PaymentAttempt argument = invocation.getArgument(0, PaymentAttempt.class);
            if (call.getAndIncrement() == 0) {
                return persisted;
            }
            return onFinalize.apply(argument);
        });
    }

    @Test
    @DisplayName("Finalize lỗi hạ tầng: trả 503 PAYMENT_CONFIRMATION_PENDING, KHÔNG tuyên bố SUCCEEDED")
    void finalizeInfrastructureFailureReturnsConfirmationPending() {
        prepareUntilGatewayCall();
        when(gateway.charge(any(GatewayRequest.class)))
                .thenReturn(new GatewayResult(Outcome.SUCCESS, "DEMO-777-idem-finalize-001", "thành công"));

        // Bản ghi đã lưu ở pha P1 — sau rollback nó vẫn phải là PENDING.
        PaymentAttempt persisted = attemptInDatabase();
        when(attempts.findById(ATTEMPT_ID)).thenAnswer(invocation -> {
            Deque<PaymentAttempt> queue = new ArrayDeque<>();
            queue.add(attemptInDatabase());
            return Optional.of(queue.getFirst());
        });
        stubSaveAndFlush(persisted, argument -> {
            throw new DataIntegrityViolationException("hạ tầng lỗi");
        });

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(503);
                    assertThat(api.getCode()).isEqualTo("PAYMENT_CONFIRMATION_PENDING");
                });

        // Bản ghi trong DB vẫn PENDING — không có tuyên bố thành công.
        assertThat(persisted.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(persisted.getSucceededAt()).isNull();
        // Gateway chỉ được gọi MỘT lần — không gọi lại.
        verify(gateway, times(1)).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("Finalize bị chặn bởi uq_payment_attempts_one_success_per_booking: 409 BOOKING_ALREADY_PAID")
    void finalizeBlockedByOneSuccessPerBookingThrowsAlreadyPaid() {
        prepareUntilGatewayCall();
        when(gateway.charge(any(GatewayRequest.class)))
                .thenReturn(new GatewayResult(Outcome.SUCCESS, "DEMO-777-idem-finalize-001", "thành công"));

        PaymentAttempt persisted = attemptInDatabase();
        when(attempts.findById(ATTEMPT_ID)).thenAnswer(invocation -> Optional.of(attemptInDatabase()));
        stubSaveAndFlush(persisted, argument -> {
            // Thông điệp của PostgreSQL chứa tên partial unique index bị vi phạm.
            throw new DataIntegrityViolationException(
                    "could not execute statement [ERROR: duplicate key value violates unique constraint "
                            + "\"uq_payment_attempts_one_success_per_booking\"]");
        });

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("BOOKING_ALREADY_PAID");
                });
        verify(gateway, times(1)).charge(any(GatewayRequest.class));
    }

    @Test
    @DisplayName("Attempt đã terminal: đọc lại được trạng thái hợp lệ thì coi là replay, không lỗi")
    void terminalStateWithValidActualStateIsTreatedAsReplay() {
        prepareUntilGatewayCall();
        when(gateway.charge(any(GatewayRequest.class)))
                .thenReturn(new GatewayResult(Outcome.SUCCESS, "DEMO-777-idem-finalize-001", "thành công"));

        // Lần đọc đầu (pha finalize) trả attempt PENDING; lần đọc lại (sau lỗi)
        // trả attempt đã ở trạng thái terminal.
        PaymentAttempt terminal = attemptInDatabase();
        terminal.markSucceeded(Instant.now());
        AtomicInteger readCount = new AtomicInteger();
        when(attempts.findById(ATTEMPT_ID)).thenAnswer(invocation ->
                Optional.of(readCount.getAndIncrement() == 0 ? attemptInDatabase() : terminal));

        PaymentAttempt persisted = attemptInDatabase();
        stubSaveAndFlush(persisted, argument -> {
            throw new IllegalStateException("attempt đã ở trạng thái terminal");
        });

        PaymentService.PaymentOutcome outcome = service.pay(request(), OWNER_ID);

        assertThat(outcome.replay()).isTrue();
        assertThat(outcome.attempt()).isSameAs(terminal);
        assertThat(outcome.attempt().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("IllegalStateException khi trạng thái thực tế KHÔNG hợp lệ: không che lỗi, ném lại")
    void illegalStateWithInvalidActualStateIsNotSwallowed() {
        prepareUntilGatewayCall();
        when(gateway.charge(any(GatewayRequest.class)))
                .thenReturn(new GatewayResult(Outcome.SUCCESS, "DEMO-777-idem-finalize-001", "thành công"));

        // Lần đọc lại trả attempt vẫn PENDING ⇒ không hợp lệ để coi là replay.
        when(attempts.findById(ATTEMPT_ID)).thenAnswer(invocation -> Optional.of(attemptInDatabase()));

        PaymentAttempt persisted = attemptInDatabase();
        stubSaveAndFlush(persisted, argument -> {
            throw new IllegalStateException("lỗi không liên quan");
        });

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lỗi không liên quan");
    }

    @Test
    @DisplayName("Gateway ném lỗi: 503 PAYMENT_CONFIRMATION_PENDING, attempt giữ PENDING, gateway không gọi lại")
    void gatewayFailureReturnsConfirmationPendingWithoutRetryingGateway() {
        prepareUntilGatewayCall();
        when(gateway.charge(any(GatewayRequest.class)))
                .thenThrow(new IllegalStateException("mạng lỗi"));

        PaymentAttempt persisted = attemptInDatabase();
        stubSaveAndFlush(persisted, argument -> argument);

        assertThatThrownBy(() -> service.pay(request(), OWNER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(503);
                    assertThat(api.getCode()).isEqualTo("PAYMENT_CONFIRMATION_PENDING");
                });

        assertThat(persisted.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(persisted.getSucceededAt()).isNull();
        verify(gateway, times(1)).charge(any(GatewayRequest.class));
    }
}
