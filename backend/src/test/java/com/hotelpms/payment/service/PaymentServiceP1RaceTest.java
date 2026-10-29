package com.hotelpms.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.payment.dto.CreatePaymentRequest;
import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.port.BookingPaymentPort;
import com.hotelpms.payment.port.BookingPaymentPort.BookingPaymentInfo;
import com.hotelpms.payment.port.PaymentGatewayPort;
import com.hotelpms.payment.port.PaymentGatewayPort.GatewayRequest;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Integration test cho <b>race tại pha P1</b> (reserve attempt) — M4-04.
 *
 * <p><b>Mục tiêu:</b> chứng minh bằng PostgreSQL thật rằng:
 * <ul>
 *   <li>Khi insert ở P1 vi phạm đúng constraint
 *       {@code uq_payment_attempts_idempotency}, service <b>rollback</b> transaction
 *       đó rồi <b>đọc lại attempt bằng transaction MỚI</b> và trả về attempt cũ.</li>
 *   <li>Cùng key + cùng amount ⇒ trả attempt cũ và <b>không</b> gọi gateway lần
 *       nữa.</li>
 *   <li>Cùng key + khác amount ⇒ {@code IDEMPOTENCY_AMOUNT_CONFLICT}.</li>
 *   <li>Lỗi DB khác (ví dụ vi phạm FK) <b>không</b> bị biến thành
 *       {@code IDEMPOTENCY_CONFLICT}.</li>
 * </ul>
 *
 * <h2>Race được dựng như thế nào (toàn bộ bằng PostgreSQL thật)</h2>
 * <p>Test mở một connection riêng, insert dòng attempt cùng idempotency key rồi
 * <b>giữ transaction chưa commit</b>. Request song song của service chạy song song:
 * <ul>
 *   <li>Bước 1 (tra cứu idempotency) <b>không</b> thấy dòng chưa commit ⇒ quyết
 *       định tạo attempt mới.</li>
 *   <li>Bước P1 insert sẽ <b>bị PostgreSQL chặn</b> (chờ unique index) cho tới
 *       khi transaction kia commit; test chờ tới lúc insert thực sự bị block
 *       ({@code wait_event_type = 'Lock'}) rồi mới commit.</li>
 *   <li>Khi transaction kia commit, insert của service bị PostgreSQL từ chối bằng
 *       <b>lỗi constraint thật</b> {@code uq_payment_attempts_idempotency}.</li>
 * </ul>
 *
 * <p><b>Về tính thật của test:</b> lỗi constraint, cơ chế chờ (blocking), rollback
 * và việc đọc lại bằng transaction mới đều do PostgreSQL thật quyết định — không
 * mock. Mock chỉ dùng để <b>đếm số lần gọi gateway</b> (để chứng minh gateway
 * không bị gọi lần hai), không dùng để giả lập lỗi DB hay trạng thái transaction.
 *
 * <p><b>Bằng chứng "đọc lại bằng transaction mới":</b> nếu service truy vấn lại trong
 * transaction đã rollback-only, PostgreSQL sẽ báo lỗi
 * {@code 25P02 current transaction is aborted}. Vì đọc lại thành công và trả về
 * đúng dòng đã commit, service <b>chắc chắn</b> đã mở transaction mới.
 *
 * @author Nguyễn Đình Đạt
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = PaymentServiceP1RaceTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class PaymentServiceP1RaceTest {

    /** Số tiền mà adapter giả lập trả về. */
    static final BigDecimal PAYABLE = new BigDecimal("1200000.00");

    /** Booking có bản ghi thật trong DB. */
    static Long realBookingId;

    /** Booking chỉ tồn tại trong adapter, KHÔNG có bản ghi trong DB (để sinh lỗi FK). */
    static Long ghostBookingId;

    /** ID chủ sở hữu booking. */
    static Long ownerId;

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
     * Adapter giả lập — chỉ có hiệu lực trong test này.
     */
    @TestConfiguration
    static class FakeBookingAdapterConfiguration {

        @Bean
        FakeBookingPaymentPort fakeBookingPaymentPort() {
            return new FakeBookingPaymentPort();
        }
    }

    /**
     * Adapter giả lập trả booking hợp lệ cho cả booking thật và "ghost".
     */
    static class FakeBookingPaymentPort implements BookingPaymentPort {

        @Override
        public Optional<BookingPaymentInfo> findPayableBooking(Long bookingId) {
            if (realBookingId != null && realBookingId.equals(bookingId)) {
                return Optional.of(new BookingPaymentInfo(bookingId, "FAKE-REAL", ownerId, PAYABLE));
            }
            if (ghostBookingId != null && ghostBookingId.equals(bookingId)) {
                // Cố tình trả về booking KHÔNG tồn tại trong DB để sinh lỗi FK.
                return Optional.of(new BookingPaymentInfo(bookingId, "FAKE-GHOST", ownerId, PAYABLE));
            }
            return Optional.empty();
        }
    }

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentAttemptRepository attempts;

    /** Spy chỉ để <b>đếm số lần gọi gateway</b> — không giả lập lỗi DB. */
    @MockitoSpyBean
    private PaymentGatewayPort gateway;

    @Autowired
    private CustomerProfileRepository profiles;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private long roomTypeId = 9202L;

    @BeforeEach
    void setUpTestData() {
        attempts.deleteAll();

        ownerId = profiles.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Demo customer profile not found — profile dev chưa chạy?"))
                .getUserId();

        jdbc.update("""
                INSERT INTO room_types (id, code, name, description, max_occupancy, base_price_per_night)
                VALUES (?, ?, 'Payment P1 Race Test', 'Room for P1 race test', 2, 1200000.00)
                ON CONFLICT (id) DO UPDATE SET base_price_per_night = 1200000.00
                """, roomTypeId, "PMS_" + roomTypeId);

        realBookingId = insertBooking();
        ghostBookingId = realBookingId + 987_654L;
    }

    private Long insertBooking() {
        Instant now = Instant.now();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String bookingCode = "P1R-" + tag;
        long testRoomId = Math.abs(("P1RROOM-" + tag).hashCode() % 1_000_000L) + 40_000L;

        jdbc.update("""
                INSERT INTO physical_rooms (id, room_type_id, room_number, floor_number)
                VALUES (?, ?, ?, 1)
                ON CONFLICT (id) DO UPDATE SET room_number = EXCLUDED.room_number
                """, testRoomId, roomTypeId, "P1RR-" + testRoomId);

        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO bookings (
                        booking_code, customer_id, room_type_id, physical_room_id,
                        guest_count, start_at, end_at, hold_expires_at,
                        status, base_amount, discount_rate, discount_amount, payable_amount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_PAYMENT', ?, 0, 0, ?)
                    """);
            ps.setString(1, bookingCode);
            ps.setLong(2, ownerId);
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

    /**
     * Mở một transaction chưa commit chứa attempt cùng idempotency key — mô phỏng
     * "request song song đã thắng nhưng chưa commit".
     *
     * @param key     idempotency key
     * @param amount  số tiền của attempt đang giữ
     * @return connection đang giữ transaction
     */
    private Connection openUncommittedAttempt(String key, BigDecimal amount) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO payment_attempts (
                    booking_id, provider, idempotency_key, status, requested_amount)
                VALUES (?, 'DEMO', ?, 'PENDING', ?)
                """)) {
            statement.setLong(1, realBookingId);
            statement.setString(2, key);
            statement.setBigDecimal(3, amount);
            statement.executeUpdate();
        }
        return connection;
    }

    /**
     * Chờ tới khi có một câu lệnh INSERT vào {@code payment_attempts} đang bị
     * PostgreSQL chặn (chờ unique index) — dấu hiệu request song song đã đi tới
     * pha P1 insert.
     *
     * @param timeout thời gian chờ tối đa (giây)
     */
    private void awaitBlockedInsert(long timeout) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock'
                      AND query ILIKE '%insert into payment_attempts%'
                    """, Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException(
                "Không thấy insert bị block trong thời gian chờ — race không được dựng đúng");
    }

    // ---------------------------------------------------------------------
    // 1. Race tại P1 — vi phạm uq_payment_attempts_idempotency
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("P1 race (uq_payment_attempts_idempotency): rollback → đọc lại transaction mới → trả attempt cũ, KHÔNG gọi gateway")
    void idempotencyConstraintViolationRollsBackAndReloadsExistingAttempt() throws Exception {
        String key = "p1race-" + UUID.randomUUID();

        Connection competitor = openUncommittedAttempt(key, PAYABLE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentService.PaymentOutcome> future =
                    executor.submit(() -> payments.pay(
                            new CreatePaymentRequest(realBookingId, key, PAYABLE, null), ownerId));

            // Chờ request song song bị PostgreSQL chặn ở pha P1 insert.
            awaitBlockedInsert(30);

            // Commit dòng cạnh tranh ⇒ insert của service bị từ chối bởi
            // uq_payment_attempts_idempotency (lỗi constraint THẬT).
            competitor.commit();

            PaymentService.PaymentOutcome outcome = future.get(30, TimeUnit.SECONDS);

            assertThat(outcome.replay()).isTrue();
            assertThat(outcome.attempt().getId()).isNotNull();
            assertThat(outcome.attempt().getStatus()).isEqualTo(PaymentStatus.PENDING);

            // KHÔNG gọi gateway lần nữa (đọc lại được attempt ⇒ không cần charge).
            verify(gateway, never()).charge(any(GatewayRequest.class));

            // DB chỉ có đúng 1 attempt cho cặp (booking, provider, key) — không rác.
            assertThat(attempts.count()).isEqualTo(1);
            assertThat(attempts.findByBookingIdAndProviderAndIdempotencyKey(
                    realBookingId, PaymentProvider.DEMO, key)).isPresent();
        } finally {
            executor.shutdownNow();
            competitor.close();
        }
    }

    @Test
    @DisplayName("P1 race + khác amount: 409 IDEMPOTENCY_AMOUNT_CONFLICT, không gọi gateway")
    void idempotencyConstraintViolationWithDifferentAmountConflicts() throws Exception {
        String key = "p1conflict-" + UUID.randomUUID();

        // Attempt cạnh tranh giữ số tiền KHÁC số tiền request (nhưng vẫn khớp
        // payableAmount của booking, nếu không service sẽ dừng ở bước đối chiếu
        // số tiền và không bao giờ chạm tới P1).
        Connection competitor = openUncommittedAttempt(key, new BigDecimal("1300000.00"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> future = executor.submit(() -> {
                try {
                    return payments.pay(new CreatePaymentRequest(
                            realBookingId, key, PAYABLE, null), ownerId);
                } catch (RuntimeException exception) {
                    return exception;
                }
            });

            awaitBlockedInsert(30);
            competitor.commit();

            Object result = future.get(30, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(ApiException.class);
            ApiException api = (ApiException) result;
            assertThat(api.getStatus().value()).isEqualTo(409);
            assertThat(api.getCode()).isEqualTo("IDEMPOTENCY_AMOUNT_CONFLICT");

            verify(gateway, never()).charge(any(GatewayRequest.class));
            assertThat(attempts.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            competitor.close();
        }
    }

    // ---------------------------------------------------------------------
    // 2. Lỗi DB KHÁC không được coi là xung đột idempotency
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Lỗi DB không phải idempotency (vi phạm FK): KHÔNG trả IDEMPOTENCY_CONFLICT, mà 503 PAYMENT_NOT_AVAILABLE")
    void nonIdempotencyConstraintViolationIsNotTreatedAsIdempotencyConflict() {
        String key = "fk-" + UUID.randomUUID();

        // Booking "ghost" có trong adapter nhưng KHÔNG có bản ghi trong DB ⇒
        // insert ở P1 vi phạm FK (lỗi DB thật, không phải lỗi idempotency).
        assertThatThrownBy(() -> payments.pay(
                new CreatePaymentRequest(ghostBookingId, key, PAYABLE, null), ownerId))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.getStatus().value()).isEqualTo(503);
                    assertThat(api.getCode()).isEqualTo("PAYMENT_NOT_AVAILABLE");
                    assertThat(api.getCode()).isNotEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(api.getCode()).isNotEqualTo("IDEMPOTENCY_AMOUNT_CONFLICT");
                });

        verify(gateway, never()).charge(any(GatewayRequest.class));
        assertThat(attempts.count()).isEqualTo(0);
    }
}
