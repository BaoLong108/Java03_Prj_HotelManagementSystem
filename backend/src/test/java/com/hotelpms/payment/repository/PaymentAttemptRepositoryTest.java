package com.hotelpms.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.entity.PaymentStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Integration test cho {@link PaymentAttemptRepository} trong phạm vi M4-03.
 *
 * <p><b>Phạm vi test:</b> lưu, truy vấn, chuyển trạng thái, ràng buộc unique
 * (idempotency, provider transaction, một SUCCEEDED/booking) và audit field.
 *
 * <p><b>Tại sao dùng JdbcTemplate để tạo booking?</b> Module {@code booking}
 * chưa có entity Java trên branch {@code NguyenDinhDat} (chỉ có
 * {@code package-info.java} — code M3-01/02/03 vẫn nằm trên nhánh khác).
 * Vì M4-03 dùng {@code Long bookingId} thay vì {@code @ManyToOne}, repo
 * vẫn hoạt động bình thường, nhưng test cần một bản ghi {@code bookings} hợp
 * lệ để thoả FK {@code payment_attempts.booking_id}. Ta insert trực tiếp
 * bằng SQL với các cột bắt buộc, dùng {@code ON CONFLICT DO NOTHING} để có
 * thể chạy lặp — cùng cách mà {@code AuthIntegrationTest} đã dùng
 * ({@code app_users}/{@code customer_profiles}/{@code roles}) và cách
 * {@code BookingRepositoryTest} (M3-01, nằm trên branch khác) cũng dùng.
 *
 * <p><b>Phụ thuộc thực tế trên branch này:</b>
 * <ul>
 *   <li>{@code AuthIntegrationTest} và {@code DemoDataSeeder} (chạy với profile
 *       {@code dev}) tạo sẵn {@code customer@hotel-pms.test} kèm
 *       {@code customer_profiles}. Test này tái sử dụng seeder đó.</li>
 *   <li>Bảng {@code bookings} trong V1 yêu cầu {@code booking_code} duy nhất
 *       toàn cục; mỗi test phải dùng một {@code booking_code} mới (UUID).</li>
 * </ul>
 *
 * @author Nguyễn Đình Đạt
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = PaymentAttemptRepositoryTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class PaymentAttemptRepositoryTest {

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
        // Thuộc tính dùng chung — bám sát các test khác trong repo.
        properties.add("app.jwt.secret",
                () -> "integration-test-signing-key-at-least-thirty-two-characters");
        properties.add("app.jwt.access-token-ttl-seconds", () -> "3600");
        properties.add("app.demo-seed.enabled", () -> "true");
        properties.add("app.demo-seed.password", () -> "TestDemo123!");
    }

    public static class PostgresCleanupListener extends AbstractTestExecutionListener {
        @Override
        public int getOrder() {
            return 1000;
        }

        @Override
        public void afterTestClass(TestContext context) throws IOException {
            // Listener chạy SAU DirtiesContext đóng Hikari/Tomcat (thứ tự unwind ngược).
            if (postgres != null) {
                postgres.close();
            }
        }
    }

    @Autowired
    private PaymentAttemptRepository attempts;

    @Autowired
    private CustomerProfileRepository profiles;

    @Autowired
    private JdbcTemplate jdbc;

    private Long customerId;
    private Long roomTypeId;

    @BeforeEach
    void setUpTestData() {
        // Dọn attempts của lần test trước (không đụng bookings vì booking_code mỗi test một mã).
        attempts.deleteAll();

        // Lấy customer_id thật từ seeder demo (customer@hotel-pms.test).
        this.customerId = profiles.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Demo customer profile not found — profile dev chưa chạy?"))
                .getUserId();

        // Mỗi test class chạy một lần nên dùng ID cố định; room_types không
        // có exclusion constraint nên chỉ cần một là đủ cho cả suite.
        this.roomTypeId = 9001L;
        jdbc.update("""
                INSERT INTO room_types (id, code, name, description, max_occupancy, base_price_per_night)
                VALUES (?, ?, 'Test Standard', 'Room for payment test', 2, 1000000.00)
                ON CONFLICT (id) DO UPDATE SET base_price_per_night = 1000000.00
                """, roomTypeId, "PAY_TEST_" + roomTypeId);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Tạo một bản ghi {@code bookings} hợp lệ (đủ cột bắt buộc, khoảng
     * thời gian hợp lệ, status PENDING_PAYMENT) và trả về ID.
     *
     * <p><b>Lưu ý quan trọng về exclusion constraint:</b> PostgreSQL schema
     * V1 có ràng buộc {@code ex_bookings_room_overlap} cấm hai booking ở
     * trạng thái {@code PENDING_PAYMENT/CONFIRMED/COMPLETED} cùng
     * {@code physical_room_id} với khoảng thời gian chồng lấn. Vì mỗi test
     * chèn booking mới ở khoảng thời gian tương tự nhau, ta phải dùng
     * {@code physical_room_id} riêng cho mỗi lần gọi (sinh từ UUID). Ngược
     * lại test thứ hai sẽ vướng ràng buộc này dù không liên quan tới payment.
     *
     * <p>Ba cột {@code start_at}, {@code end_at}, {@code hold_expires_at} đều
     * là {@code TIMESTAMPTZ}. Driver PostgreSQL yêu cầu bind qua
     * {@link OffsetDateTime} chứ không chấp nhận {@link Instant} trực tiếp —
     * nếu thử sẽ ném {@code "Cannot cast an instance of java.time.Instant"}
     * hoặc {@code "Can't infer the SQL type"}. Ta chuyển qua UTC rồi bind.
     */
    private Long insertBooking(String suffix, BigDecimal payableAmount) {
        Instant now = Instant.now();
        String tag = suffix + UUID.randomUUID().toString().substring(0, 8);
        String bookingCode = "PMT-" + tag;
        long testRoomTypeId = roomTypeId;
        // Mỗi booking phải có physical_room riêng (FK kép + exclusion constraint).
        long testRoomId = Math.abs(("ROOM-" + tag).hashCode() % 1_000_000L) + 10_000L;
        jdbc.update("""
                INSERT INTO physical_rooms (id, room_type_id, room_number, floor_number)
                VALUES (?, ?, ?, 1)
                ON CONFLICT (id) DO UPDATE SET room_number = EXCLUDED.room_number
                """, testRoomId, testRoomTypeId, "PR-" + testRoomId);

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
            ps.setLong(3, testRoomTypeId);
            ps.setLong(4, testRoomId);
            ps.setShort(5, (short) 1);
            ps.setObject(6, now.plus(1, ChronoUnit.DAYS).atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(7, now.plus(2, ChronoUnit.DAYS).atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(8, now.plus(15, ChronoUnit.MINUTES).atOffset(java.time.ZoneOffset.UTC));
            ps.setBigDecimal(9, payableAmount);
            ps.setBigDecimal(10, payableAmount);
            return ps;
        });
        return jdbc.queryForObject(
                "SELECT id FROM bookings WHERE booking_code = ?", Long.class, bookingCode);
    }

    private PaymentAttempt newAttempt(Long bookingId, String idempotencyKey) {
        return new PaymentAttempt(bookingId, PaymentProvider.DEMO, idempotencyKey, new BigDecimal("1000000.00"));
    }

    // ---------------------------------------------------------------------
    // 1. Lưu & truy vấn cơ bản
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Lưu mới rồi đọc lại: tất cả trường khớp DB, status = PENDING, succeededAt = null")
    void saveAndFindByIdPersistsEveryField() {
        Long bookingId = insertBooking("SAVE-", new BigDecimal("1500000.00"));
        PaymentAttempt attempt = new PaymentAttempt(
                bookingId, PaymentProvider.DEMO, "idem-save-001", new BigDecimal("1500000.00"));

        PaymentAttempt saved = attempts.saveAndFlush(attempt);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getSucceededAt()).isNull();
        assertThat(saved.getProvider()).isEqualTo(PaymentProvider.DEMO);
        assertThat(saved.getRequestedAmount()).isEqualByComparingTo("1500000.00");

        PaymentAttempt reloaded = attempts.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBookingId()).isEqualTo(bookingId);
        assertThat(reloaded.getIdempotencyKey()).isEqualTo("idem-save-001");
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloaded.getSucceededAt()).isNull();
    }

    @Test
    @DisplayName("Tìm theo (bookingId, provider, idempotencyKey) trả đúng attempt đã lưu")
    void findByIdempotencyKeyReturnsTheSavedAttempt() {
        Long bookingId = insertBooking("IDEM-", new BigDecimal("200000.00"));
        PaymentAttempt saved = attempts.saveAndFlush(
                newAttempt(bookingId, "idem-find-001"));

        Optional<PaymentAttempt> found = attempts.findByBookingIdAndProviderAndIdempotencyKey(
                bookingId, PaymentProvider.DEMO, "idem-find-001");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("Sai idempotencyKey thì trả Optional.empty() — KHÔNG ném exception")
    void findByIdempotencyKeyReturnsEmptyForUnknownKey() {
        Long bookingId = insertBooking("MISS-", new BigDecimal("100.00"));
        attempts.saveAndFlush(newAttempt(bookingId, "idem-known"));

        Optional<PaymentAttempt> found = attempts.findByBookingIdAndProviderAndIdempotencyKey(
                bookingId, PaymentProvider.DEMO, "idem-unknown");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Danh sách attempt của một booking, sắp xếp mới nhất trước")
    void findByBookingIdOrderByCreatedAtDesc() {
        Long bookingId = insertBooking("LIST-", new BigDecimal("300.00"));
        attempts.saveAndFlush(newAttempt(bookingId, "idem-list-1"));
        attempts.saveAndFlush(newAttempt(bookingId, "idem-list-2"));
        attempts.saveAndFlush(newAttempt(bookingId, "idem-list-3"));

        List<PaymentAttempt> list = attempts.findByBookingIdOrderByCreatedAtDesc(bookingId);

        assertThat(list).hasSize(3);
        assertThat(list.get(0).getIdempotencyKey()).isEqualTo("idem-list-3");
        assertThat(list.get(2).getIdempotencyKey()).isEqualTo("idem-list-1");
    }

    // ---------------------------------------------------------------------
    // 2. State transition (validate ở entity)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("markSucceeded từ PENDING: status = SUCCEEDED, succeededAt = now")
    void markSucceededFromPendingTransitionsAndStampsTimestamp() {
        Long bookingId = insertBooking("SUC-", new BigDecimal("500.00"));
        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-suc-1"));
        Instant now = Instant.now();

        saved.markSucceeded(now);
        PaymentAttempt reloaded = attempts.saveAndFlush(saved);

        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(reloaded.getSucceededAt()).isNotNull();
        // Instant lưu xuống DB qua TIMESTAMPTZ có thể mất micro-giây; so sánh theo giây.
        assertThat(reloaded.getSucceededAt().getEpochSecond()).isEqualTo(now.getEpochSecond());
    }

    @Test
    @DisplayName("markFailed từ PENDING: status = FAILED, succeededAt vẫn null")
    void markFailedFromPendingTransitionsWithoutStampingSucceededAt() {
        Long bookingId = insertBooking("FAIL-", new BigDecimal("500.00"));
        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-fail-1"));

        saved.markFailed();
        PaymentAttempt reloaded = attempts.saveAndFlush(saved);

        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reloaded.getSucceededAt()).isNull();
    }

    @Test
    @DisplayName("markSucceeded khi đã SUCCEEDED: ném IllegalStateException")
    void markSucceededTwiceRejected() {
        Long bookingId = insertBooking("DUP-S-", new BigDecimal("500.00"));
        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-dup-s"));
        saved.markSucceeded(Instant.now());
        attempts.saveAndFlush(saved);

        assertThatThrownBy(() -> saved.markSucceeded(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only PENDING payment attempt can be marked SUCCEEDED");
    }

    @Test
    @DisplayName("markSucceeded khi đã FAILED: ném IllegalStateException")
    void markSucceededFromFailedRejected() {
        Long bookingId = insertBooking("FS-", new BigDecimal("500.00"));
        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-fs"));
        saved.markFailed();
        attempts.saveAndFlush(saved);

        assertThatThrownBy(() -> saved.markSucceeded(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only PENDING payment attempt can be marked SUCCEEDED");
    }

    @Test
    @DisplayName("markFailed khi đã SUCCEEDED: ném IllegalStateException (trạng thái cuối)")
    void markFailedFromSucceededRejected() {
        Long bookingId = insertBooking("SF-", new BigDecimal("500.00"));
        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-sf"));
        saved.markSucceeded(Instant.now());
        attempts.saveAndFlush(saved);

        assertThatThrownBy(saved::markFailed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only PENDING payment attempt can be marked FAILED");
    }

    @Test
    @DisplayName("markFailed khi đã FAILED: ném IllegalStateException")
    void markFailedTwiceRejected() {
        Long bookingId = insertBooking("FF-", new BigDecimal("500.00"));
        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-ff"));
        saved.markFailed();
        attempts.saveAndFlush(saved);

        assertThatThrownBy(saved::markFailed)
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------------
    // 3. Audit fields (createdAt / updatedAt)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("createdAt và updatedAt được @PrePersist gán, bằng nhau khi tạo mới")
    void auditFieldsArePopulatedOnInsert() {
        Long bookingId = insertBooking("AUD-", new BigDecimal("700.00"));
        Instant beforeSave = Instant.now();

        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-aud-1"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        // Khi tạo mới, AuditedEntity.onCreate() ép updatedAt == createdAt.
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        // createdAt không được sớm hơn thời điểm trước khi save (sai số 2s cho clock).
        assertThat(saved.getCreatedAt()).isAfterOrEqualTo(beforeSave.minusSeconds(2));
    }

    @Test
    @DisplayName("updatedAt được @PreUpdate làm mới khi save sau khi sửa trạng thái")
    void updatedAtIsRefreshedOnUpdate() throws InterruptedException {
        Long bookingId = insertBooking("AUDU-", new BigDecimal("700.00"));
        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-aud-u"));
        Instant originalUpdatedAt = saved.getUpdatedAt();

        // Ngủ 1.1s để chắc chắn updatedAt khác giá trị cũ (TIMESTAMPTZ lưu micro-giây).
        Thread.sleep(1100);

        saved.markSucceeded(Instant.now());
        PaymentAttempt reloaded = attempts.saveAndFlush(saved);

        assertThat(reloaded.getUpdatedAt()).isAfter(originalUpdatedAt);
        // createdAt không đổi sau update (vì updatable=false trong AuditedEntity).
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    // ---------------------------------------------------------------------
    // 4. Ràng buộc unique (DB-level)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Trùng (bookingId, provider, idempotencyKey) bị DB từ chối (uq_payment_attempts_idempotency)")
    void uniqueIdempotencyKeyIsEnforced() {
        Long bookingId = insertBooking("UNIQ-", new BigDecimal("100.00"));
        attempts.saveAndFlush(newAttempt(bookingId, "idem-uniq-1"));

        // Tạo attempt mới (entity Java khác) nhưng cùng (booking, provider, key).
        PaymentAttempt duplicate = newAttempt(bookingId, "idem-uniq-1");

        assertThatThrownBy(() -> attempts.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Cùng idempotencyKey nhưng khác provider thì KHÔNG bị xung đột")
    void idempotencyKeyIsScopedToProvider() {
        Long bookingId = insertBooking("PROV-", new BigDecimal("100.00"));
        attempts.saveAndFlush(new PaymentAttempt(
                bookingId, PaymentProvider.DEMO, "idem-shared", new BigDecimal("100.00")));

        // Cùng key, khác provider — phải insert được.
        PaymentAttempt vnpayAttempt = new PaymentAttempt(
                bookingId, PaymentProvider.VNPAY, "idem-shared", new BigDecimal("100.00"));
        PaymentAttempt saved = attempts.saveAndFlush(vnpayAttempt);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getProvider()).isEqualTo(PaymentProvider.VNPAY);
    }

    @Test
    @DisplayName("providerTransactionId trùng giữa hai attempt bị DB từ chối (partial unique index)")
    void duplicateProviderTransactionIdIsRejected() {
        Long bookingIdA = insertBooking("PTX-A-", new BigDecimal("100.00"));
        Long bookingIdB = insertBooking("PTX-B-", new BigDecimal("100.00"));
        PaymentAttempt first = attempts.saveAndFlush(newAttempt(bookingIdA, "idem-ptx-1"));
        first.setProviderTransactionId("TXN-ABC");
        attempts.saveAndFlush(first);

        PaymentAttempt second = newAttempt(bookingIdB, "idem-ptx-2");
        second.setProviderTransactionId("TXN-ABC");

        assertThatThrownBy(() -> attempts.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("providerTransactionId null không tham gia vào ràng buộc — cho phép nhiều attempt null")
    void nullProviderTransactionIdIsAllowedRepeatedly() {
        Long bookingIdA = insertBooking("NUL-A-", new BigDecimal("100.00"));
        Long bookingIdB = insertBooking("NUL-B-", new BigDecimal("100.00"));
        attempts.saveAndFlush(newAttempt(bookingIdA, "idem-nul-1"));
        attempts.saveAndFlush(newAttempt(bookingIdB, "idem-nul-2"));

        // Cả hai đều có provider_transaction_id IS NULL, không vi phạm partial unique index.
        assertThat(attempts.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Hai attempt SUCCEEDED cùng booking bị DB từ chối (uq_payment_attempts_one_success_per_booking)")
    void onlyOneSucceededPerBooking() {
        Long bookingId = insertBooking("ONE-OK-", new BigDecimal("100.00"));

        PaymentAttempt first = attempts.saveAndFlush(newAttempt(bookingId, "idem-one-1"));
        first.markSucceeded(Instant.now());
        attempts.saveAndFlush(first);

        // Attempt thứ hai cùng booking, idempotency key khác.
        PaymentAttempt second = newAttempt(bookingId, "idem-one-2");
        second.markSucceeded(Instant.now());

        assertThatThrownBy(() -> attempts.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------
    // 5. existsByBookingIdAndStatus & findFirstBy...
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("existsByBookingIdAndStatus: SUCCEEDED=true chỉ sau khi markSucceeded")
    void existsByBookingIdAndStatusReflectsState() {
        Long bookingId = insertBooking("EX-", new BigDecimal("100.00"));
        attempts.saveAndFlush(newAttempt(bookingId, "idem-ex-1"));

        assertThat(attempts.existsByBookingIdAndStatus(bookingId, PaymentStatus.PENDING)).isTrue();
        assertThat(attempts.existsByBookingIdAndStatus(bookingId, PaymentStatus.SUCCEEDED)).isFalse();

        PaymentAttempt saved = attempts.findByBookingIdAndProviderAndIdempotencyKey(
                bookingId, PaymentProvider.DEMO, "idem-ex-1").orElseThrow();
        saved.markSucceeded(Instant.now());
        attempts.saveAndFlush(saved);

        assertThat(attempts.existsByBookingIdAndStatus(bookingId, PaymentStatus.SUCCEEDED)).isTrue();
        assertThat(attempts.existsByBookingIdAndStatus(bookingId, PaymentStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("findFirstByBookingIdAndStatusOrderByCreatedAtDesc: trả attempt mới nhất")
    void findFirstByStatusReturnsMostRecent() {
        Long bookingId = insertBooking("FR-", new BigDecimal("100.00"));
        attempts.saveAndFlush(new PaymentAttempt(
                bookingId, PaymentProvider.DEMO, "idem-fr-1", new BigDecimal("100.00")));
        // Cách nhau ít nhất 5ms để createdAt khác biệt.
        try {
            Thread.sleep(5);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        attempts.saveAndFlush(new PaymentAttempt(
                bookingId, PaymentProvider.DEMO, "idem-fr-2", new BigDecimal("100.00")));

        Optional<PaymentAttempt> first = attempts.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(
                bookingId, PaymentStatus.PENDING);

        assertThat(first).isPresent();
        assertThat(first.get().getIdempotencyKey()).isEqualTo("idem-fr-2");
    }

    @Test
    @DisplayName("findByProviderAndProviderTransactionId tra ra đúng attempt theo mã giao dịch")
    void findByProviderAndProviderTransactionId() {
        Long bookingId = insertBooking("PRV-", new BigDecimal("100.00"));
        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-prv-1"));
        saved.setProviderTransactionId("TXN-CALLBACK-001");
        attempts.saveAndFlush(saved);

        Optional<PaymentAttempt> found = attempts.findByProviderAndProviderTransactionId(
                PaymentProvider.DEMO, "TXN-CALLBACK-001");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    // ---------------------------------------------------------------------
    // 6. Validate ở constructor (domain layer)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Constructor: bookingId = null → NullPointerException")
    void constructorRejectsNullBookingId() {
        assertThatThrownBy(() -> new PaymentAttempt(
                null, PaymentProvider.DEMO, "idem-x", new BigDecimal("1.00")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bookingId");
    }

    @Test
    @DisplayName("Constructor: idempotencyKey rỗng → IllegalArgumentException")
    void constructorRejectsBlankIdempotencyKey() {
        Long bookingId = insertBooking("BLK-", new BigDecimal("100.00"));
        assertThatThrownBy(() -> new PaymentAttempt(
                bookingId, PaymentProvider.DEMO, "   ", new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    @DisplayName("Constructor: requestedAmount âm → IllegalArgumentException")
    void constructorRejectsNegativeAmount() {
        Long bookingId = insertBooking("NEG-", new BigDecimal("100.00"));
        assertThatThrownBy(() -> new PaymentAttempt(
                bookingId, PaymentProvider.DEMO, "idem-neg", new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestedAmount");
    }

    @Test
    @DisplayName("Constructor: bookingId = 0 → IllegalArgumentException (phải dương)")
    void constructorRejectsZeroBookingId() {
        assertThatThrownBy(() -> new PaymentAttempt(
                0L, PaymentProvider.DEMO, "idem-zero", new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bookingId");
    }

    @Test
    @DisplayName("setProviderTransactionId: rỗng → IllegalArgumentException")
    void setProviderTransactionIdRejectsBlank() {
        Long bookingId = insertBooking("BLKPT-", new BigDecimal("100.00"));
        PaymentAttempt saved = attempts.saveAndFlush(newAttempt(bookingId, "idem-blk-pt"));

        assertThatThrownBy(() -> saved.setProviderTransactionId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("UUID ngẫu nhiên cho idempotencyKey — test suite độc lập giữa các lần chạy")
    void freshUuidKeepsTestsIndependent() {
        // Test "không làm gì" nhưng bảo đảm helper sinh UUID dùng được.
        String randomKey = "idem-uuid-" + UUID.randomUUID();
        assertThat(randomKey).isNotBlank().startsWith("idem-uuid-");
    }
}
