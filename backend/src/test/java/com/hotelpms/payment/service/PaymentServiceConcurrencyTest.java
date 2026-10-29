package com.hotelpms.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.payment.dto.CreatePaymentRequest;
import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.port.BookingPaymentPort;
import com.hotelpms.payment.port.BookingPaymentPort.BookingPaymentInfo;
import com.hotelpms.payment.port.PaymentGatewayPort;
import com.hotelpms.payment.port.PaymentGatewayPort.GatewayRequest;
import com.hotelpms.payment.port.PaymentGatewayPort.Outcome;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Integration test cho {@link PaymentService} trên PostgreSQL thật — M4-04.
 *
 * <p><b>Adapter giả lập thuộc phạm vi test:</b> module Booking chưa có entity Java
 * trên nhánh này, nên test dùng {@link FakeBookingPaymentPort} — một
 * {@code @TestConfiguration} chỉ có hiệu lực trong test này. Adapter này <b>không
 * đọc bảng {@code bookings}</b>: nó trả về số tiền cố định cho một booking id đã
 * biết trước, kèm {@code customerId} của chủ booking để kiểm tra quyền sở hữu.
 *
 * <p><b>Về bảng {@code bookings}:</b> cột {@code payment_attempts.booking_id} có
 * FK {@code REFERENCES bookings(id)}, nên bắt buộc phải có một bản ghi booking hợp
 * lệ trong DB thì mới insert được attempt. Test chỉ <b>insert</b> một booking tối
 * thiểu (không đọc, không sửa logic booking, không dùng để kiểm tra nghiệp vụ
 * booking) — cùng cách {@code PaymentAttemptRepositoryTest} (M4-03) đã làm.
 *
 * <p>Chỉ bảng {@code payment_attempts} được dùng cho kiểm thử nghiệp vụ payment.
 *
 * @author Nguyễn Đình Đạt
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = PaymentServiceConcurrencyTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class PaymentServiceConcurrencyTest {

    /** Số tiền mà adapter giả lập trả về. */
    static final BigDecimal PAYABLE = new BigDecimal("1200000.00");

    /** ID booking dùng cho adapter giả lập. */
    static Long fakeBookingId;

    /** ID chủ sở hữu booking trả về bởi adapter giả lập. */
    static Long fakeBookingOwnerId;

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws IOException {
        String externalUrl = System.getenv("TEST_DB_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            postgres = EmbeddedPostgres.builder().setPort(0).start();
            properties.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
            properties.add("spring.datasource.username", () -> "postgres");
            properties.add("spring.datasource.password", () -> "postgres");
        } else {
            properties.add("spring.datasource.url", () -> externalUrl);
            properties.add("spring.datasource.username", () -> System.getenv("TEST_DB_USER"));
            properties.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        }
        properties.add("app.jwt.secret",
                () -> "integration-test-signing-key-at-least-thirty-two-characters");
        properties.add("app.jwt.access-token-ttl-seconds", () -> "3600");
        properties.add("app.demo-seed.enabled", () -> "true");
        properties.add("app.demo-seed.password", () -> "TestDemo123!");
        // Bật cổng DEMO giả lập cho test integration.
        properties.add("app.payment.use-stub-ports", () -> "true");
    }

    public static class PostgresCleanupListener extends AbstractTestExecutionListener {
        @Override
        public int getOrder() {
            return 1000;
        }

        @Override
        public void afterTestClass(TestContext context) throws IOException {
            if (postgres != null) {
                postgres.close();
            }
        }
    }

    /**
     * Cấu hình test: adapter giả lập cho {@link BookingPaymentPort}.
     *
     * <p>Được đăng ký bằng {@code @TestConfiguration} nên chỉ có hiệu lực trong test
     * này — không ảnh hưởng production.
     */
    @TestConfiguration
    static class FakeBookingAdapterConfiguration {

        @Bean
        FakeBookingPaymentPort fakeBookingPaymentPort() {
            return new FakeBookingPaymentPort();
        }
    }

    /**
     * Adapter giả lập trả về booking hợp lệ cho {@link #fakeBookingId}.
     */
    static class FakeBookingPaymentPort implements BookingPaymentPort {

        @Override
        public Optional<BookingPaymentInfo> findPayableBooking(Long bookingId) {
            if (fakeBookingId != null && fakeBookingId.equals(bookingId)) {
                return Optional.of(new BookingPaymentInfo(
                        bookingId, "FAKE-BOOKING-001", fakeBookingOwnerId, PAYABLE));
            }
            return Optional.empty();
        }
    }

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentAttemptRepository attempts;

    @Autowired
    private PaymentGatewayPort gateway;

    @Autowired
    private CustomerProfileRepository profiles;

    @Autowired
    private JdbcTemplate jdbc;

    private Long customerId;
    private long roomTypeId = 9101L;

    @BeforeEach
    void setUpTestData() {
        attempts.deleteAll();

        this.customerId = profiles.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Demo customer profile not found — profile dev chưa chạy?"))
                .getUserId();

        jdbc.update("""
                INSERT INTO room_types (id, code, name, description, max_occupancy, base_price_per_night)
                VALUES (?, ?, 'Payment Service Test', 'Room for payment service test', 2, 1200000.00)
                ON CONFLICT (id) DO UPDATE SET base_price_per_night = 1200000.00
                """, roomTypeId, "PMS_" + roomTypeId);

        fakeBookingId = insertBooking();
        fakeBookingOwnerId = customerId;
    }

    /**
     * Tạo một bản ghi {@code bookings} tối thiểu để thoà FK của
     * {@code payment_attempts.booking_id}.
     *
     * <p>Mỗi lần gọi dùng {@code physical_room_id} riêng để không vướng exclusion
     * constraint {@code ex_bookings_room_overlap} của PostgreSQL.
     */
    private Long insertBooking() {
        Instant now = Instant.now();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String bookingCode = "PMS-" + tag;
        long testRoomId = Math.abs(("PMSROOM-" + tag).hashCode() % 1_000_000L) + 20_000L;

        jdbc.update("""
                INSERT INTO physical_rooms (id, room_type_id, room_number, floor_number)
                VALUES (?, ?, ?, 1)
                ON CONFLICT (id) DO UPDATE SET room_number = EXCLUDED.room_number
                """, testRoomId, roomTypeId, "PMSR-" + testRoomId);

        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO bookings (
                        booking_code, customer_id, room_type_id, physical_room_id,
                        guest_count, start_at, end_at, hold_expires_at,
                        status, base_amount, discount_rate, discount_amount, payable_amount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_PAYMENT', ?, 0, 0, ?)
                    """);
            ps.setString(1, bookingCode);
            ps.setLong(2, customerId);
            ps.setLong(3, roomTypeId);
            ps.setLong(4, testRoomId);
            ps.setShort(5, (short) 1);
            ps.setObject(6, now.plus(1, ChronoUnit.DAYS).atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(7, now.plus(2, ChronoUnit.DAYS).atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(8, now.plus(15, ChronoUnit.MINUTES).atOffset(java.time.ZoneOffset.UTC));
            ps.setBigDecimal(9, PAYABLE);
            ps.setBigDecimal(10, PAYABLE);
            return ps;
        });
        return jdbc.queryForObject(
                "SELECT id FROM bookings WHERE booking_code = ?", Long.class, bookingCode);
    }

    private CreatePaymentRequest request(String key, BigDecimal amount) {
        return new CreatePaymentRequest(fakeBookingId, key, amount, null);
    }

    // ---------------------------------------------------------------------
    // 1. Idempotency tuần tự trên PostgreSQL thật
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Retry tuần tự cùng key: DB chỉ có 1 attempt, không tạo attempt mới")
    void sequentialRetryCreatesExactlyOneAttempt() {
        String key = "seq-" + UUID.randomUUID();

        PaymentService.PaymentOutcome first = payments.pay(request(key, PAYABLE), customerId);
        PaymentService.PaymentOutcome second = payments.pay(request(key, PAYABLE), customerId);

        assertThat(first.replay()).isFalse();
        assertThat(second.replay()).isTrue();
        assertThat(second.attempt().getId()).isEqualTo(first.attempt().getId());
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(attempts.findById(first.attempt().getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Cùng key + khác amount → 409 IDEMPOTENCY_AMOUNT_CONFLICT, DB không có attempt mới")
    void sameKeyDifferentAmountIsRejected() {
        String key = "conflict-" + UUID.randomUUID();
        payments.pay(request(key, PAYABLE), customerId);

        assertThatThrownBy(() -> payments.pay(request(key, new BigDecimal("1300000.00")), customerId))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo("IDEMPOTENCY_AMOUNT_CONFLICT"));

        assertThat(attempts.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Booking không tồn tại (adapter trả empty) → 404 BOOKING_NOT_FOUND")
    void unknownBookingIsRejected() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                fakeBookingId + 999_999L, "missing-" + UUID.randomUUID(), PAYABLE, null);

        assertThatThrownBy(() -> payments.pay(request, customerId))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo("BOOKING_NOT_FOUND"));

        assertThat(attempts.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("Người gọi không phải chủ booking → 404 BOOKING_NOT_FOUND, DB không có attempt")
    void nonOwnerIsRejected() {
        CreatePaymentRequest request = request("notowner-" + UUID.randomUUID(), PAYABLE);

        assertThatThrownBy(() -> payments.pay(request, customerId + 999_999L))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    assertThat(((ApiException) thrown).getStatus().value()).isEqualTo(404);
                    assertThat(((ApiException) thrown).getCode()).isEqualTo("BOOKING_NOT_FOUND");
                });

        assertThat(attempts.count()).isEqualTo(0);
    }

    // ---------------------------------------------------------------------
    // 2. Race condition — hai request đồng thời cùng idempotency key
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Hai thread đồng thời cùng key + cùng amount: DB chỉ có 1 attempt, không lỗi 5xx")
    void concurrentSameKeyCreatesExactlyOneAttempt() throws Exception {
        String key = "race-" + UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Object> task = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                return payments.pay(request(key, PAYABLE), customerId);
            } catch (ApiException exception) {
                return exception;
            }
        };

        Future<Object> first = executor.submit(task);
        Future<Object> second = executor.submit(task);
        start.countDown();

        List<Object> outcomes = List.of(
                first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        executor.shutdownNow();

        // Mỗi thread phải nhận MỘT trong hai kết quả hợp lệ:
        //  (a) lấy được attempt đã có, hoặc
        //  (b) 409 IDEMPOTENCY_CONFLICT — transaction cạnh tranh chưa commit
        //      tại thời điểm đọc lại nên không thấy attempt.
        // Không được nuốt exception chung chung: mọi lỗi khác đều là FAIL.
        for (Object outcome : outcomes) {
            if (outcome instanceof ApiException exception) {
                assertThat(exception.getStatus().value()).isEqualTo(409);
                assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
            } else {
                assertThat(outcome).isInstanceOf(PaymentService.PaymentOutcome.class);
                PaymentService.PaymentOutcome payment = (PaymentService.PaymentOutcome) outcome;
                assertThat(payment.attempt().getId()).isNotNull();
                assertThat(payment.attempt().getStatus())
                        .isIn(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED);
            }
        }

        // Quan trọng: DB chỉ có ĐÚNG 1 attempt cho cặp (booking, provider, key).
        assertThat(attempts.count()).isEqualTo(1);
        PaymentAttempt stored = attempts.findByBookingIdAndProviderAndIdempotencyKey(
                fakeBookingId, PaymentProvider.DEMO, key).orElseThrow();
        assertThat(stored.getId()).isNotNull();

        // Sau khi request thắng hoàn tất finalize, trạng thái cuối là SUCCEEDED.
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stored.getSucceededAt()).isNotNull();
    }

    @Test
    @DisplayName("Hai thread đồng thời khác key: nhiều nhất 1 attempt SUCCEEDED, không lỗi 5xx")
    void concurrentDifferentKeysYieldsAtMostOneSuccess() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Object> task = () -> {
            start.await(5, TimeUnit.SECONDS);
            String key = "race2-" + UUID.randomUUID();
            try {
                return payments.pay(request(key, PAYABLE), customerId);
            } catch (ApiException exception) {
                return exception;
            }
        };

        Future<Object> first = executor.submit(task);
        Future<Object> second = executor.submit(task);
        start.countDown();

        List<Object> outcomes = List.of(
                first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        executor.shutdownNow();

        // Không được có lỗi 5xx: kết quả chỉ là SUCCEEDED hoặc 409 BOOKING_ALREADY_PAID.
        assertThat(outcomes).allSatisfy(outcome -> {
            if (outcome instanceof ApiException exception) {
                assertThat(exception.getStatus().value()).isEqualTo(409);
                assertThat(exception.getCode()).isEqualTo("BOOKING_ALREADY_PAID");
            } else {
                assertThat(outcome).isInstanceOf(PaymentService.PaymentOutcome.class);
            }
        });

        // Ràng buộc DB: nhiều nhất một attempt SUCCEEDED cho mỗi booking.
        List<PaymentAttempt> all = attempts.findByBookingIdOrderByCreatedAtDesc(fakeBookingId);
        assertThat(all.stream().filter(a -> a.getStatus() == PaymentStatus.SUCCEEDED).count())
                .isLessThanOrEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // 3. Ràng buộc DB được tôn trọng
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("DB từ chối hai attempt SUCCEEDED cùng booking (uq_payment_attempts_one_success_per_booking)")
    void databaseRejectsTwoSucceededAttemptsForSameBooking() {
        String key = "one-success-" + UUID.randomUUID();
        payments.pay(request(key, PAYABLE), customerId);

        PaymentAttempt second = new PaymentAttempt(
                fakeBookingId, PaymentProvider.DEMO, "another-" + UUID.randomUUID(), PAYABLE);
        second.markSucceeded(Instant.now());

        assertThatThrownBy(() -> attempts.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Cổng DEMO giả lập được nạp khi app.payment.use-stub-ports=true")
    void demoGatewayIsActiveInTestProfile() {
        assertThat(gateway).isNotNull();
        assertThat(gateway.supportsOutcomeSimulation()).isTrue();
        assertThat(gateway.charge(new GatewayRequest(
                fakeBookingId, PAYABLE, "probe-key", Outcome.SUCCESS)).providerTransactionId())
                .startsWith("DEMO-");
    }
}
