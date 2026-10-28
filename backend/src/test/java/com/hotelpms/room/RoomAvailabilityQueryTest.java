package com.hotelpms.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelpms.booking.service.RoomAllocationService;
import com.hotelpms.room.service.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M2-04 — Kiem tra truy van phong vat ly va khoa giu cho.
 *
 * Pham vi:
 * - findAvailableRoomTypeIds: phan biet booking overlap/het han/block/released.
 * - findAndLockAvailablePhysicalRoom: khoa row va kiem tra overlap chinh xac.
 * - GET /api/v1/rooms/available: endpoint public, validate param.
 * - Bien gioi [startAt, endAt): booking ket thuc dung startAt -> khong overlap.
 * - Concurrency: hai request dong thoi, chi mot thanh cong.
 *
 * Owner: Le Phuoc Sang (PhuocSang). Khong chinh sua fixture cua test khac.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = RoomAvailabilityQueryTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class RoomAvailabilityQueryTest {

    // ── Embedded postgres ──────────────────────────────────────────────────────
    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) throws IOException {
        String externalUrl = System.getenv("TEST_DB_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            postgres = EmbeddedPostgres.builder().setPort(0).start();
            props.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
            props.add("spring.datasource.username", () -> "postgres");
            props.add("spring.datasource.password", () -> "postgres");
        } else {
            props.add("spring.datasource.url", () -> externalUrl);
            props.add("spring.datasource.username", () -> System.getenv("TEST_DB_USER"));
            props.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        }
        props.add("app.jwt.secret", () -> "avail-test-signing-key-at-least-thirty-two-chars");
        props.add("app.jwt.access-token-ttl-seconds", () -> "3600");
        props.add("app.demo-seed.enabled", () -> "true");
        props.add("app.demo-seed.password", () -> "TestDemo123!");
    }

    public static class PostgresCleanupListener extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext ctx) throws IOException {
            if (postgres != null) postgres.close();
        }
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────
    // Dung ID cao de khong xung dot voi V2 seed (STANDARD 101-105, DELUXE 201-204...)
    private static final long RT_ID   = 9001L;  // RoomType
    private static final long PR_A    = 90011L; // PhysicalRoom A (trong fixture)
    private static final long PR_B    = 90012L; // PhysicalRoom B (trong fixture)
    private static final long STAFF_ID = 2L;    // admin@hotel.dev (id=2 trong V2 seed)

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoomAllocationService allocationService;
    @Autowired private RoomService roomService;
    @Autowired private ObjectMapper json;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void setUp() {
        // Xoa du lieu fixtures cu, giu V2 seed
        jdbc.update("DELETE FROM bookings WHERE room_type_id = ?", RT_ID);
        jdbc.update("DELETE FROM room_blocks WHERE physical_room_id IN (?, ?)", PR_A, PR_B);

        // Tao room type rieng cho test nay
        jdbc.update("""
            INSERT INTO room_types (id, code, name, description, max_occupancy, base_price_per_night, is_active)
            VALUES (?, 'AVAIL_TEST', 'Availability Test Room', 'Test desc', 2, 1000000.00, true)
            ON CONFLICT (id) DO UPDATE SET is_active = true, max_occupancy = 2
        """, RT_ID);

        // Tao 2 phong vat ly
        jdbc.update("""
            INSERT INTO physical_rooms (id, room_type_id, room_number, floor_number, is_active)
            VALUES (?, ?, 'AVAIL-A', 9, true)
            ON CONFLICT (id) DO UPDATE SET is_active = true
        """, PR_A, RT_ID);
        jdbc.update("""
            INSERT INTO physical_rooms (id, room_type_id, room_number, floor_number, is_active)
            VALUES (?, ?, 'AVAIL-B', 9, true)
            ON CONFLICT (id) DO UPDATE SET is_active = true
        """, PR_B, RT_ID);
    }

    // ── Test cases ─────────────────────────────────────────────────────────────

    /** (a) Phong khong co booking/block -> available. */
    @Test
    void roomWithNoBookingOrBlock_isAvailable() {
        Instant start = future(5);
        Instant end   = future(7);
        Instant now   = Instant.now();

        List<Long> available = allocationService.findAvailableRoomTypeIds(start, end, now);
        assertThat(available).contains(RT_ID);

        Optional<Long> locked = allocationService.findAndLockAvailablePhysicalRoom(RT_ID, start, end, now);
        assertThat(locked).isPresent();
    }

    /** (b) Phong co booking CONFIRMED overlap -> khong available. */
    @Test
    void roomWithConfirmedBookingOverlap_isNotAvailable() {
        Instant start = future(10);
        Instant end   = future(12);
        Instant now   = Instant.now();

        // Dat booking CONFIRMED tren ca 2 phong
        insertBooking(91L, PR_A, "BK-CONF-A", "CONFIRMED", start, end, now.minus(5, ChronoUnit.MINUTES));
        insertBooking(92L, PR_B, "BK-CONF-B", "CONFIRMED", start, end, now.minus(5, ChronoUnit.MINUTES));

        List<Long> available = allocationService.findAvailableRoomTypeIds(start, end, now);
        assertThat(available).doesNotContain(RT_ID);
    }

    /** (c) Phong co PENDING_PAYMENT con hold overlap -> khong available. */
    @Test
    void roomWithActiveHoldOverlap_isNotAvailable() {
        Instant start   = future(15);
        Instant end     = future(17);
        Instant now     = Instant.now();
        Instant holdExp = now.plus(15, ChronoUnit.MINUTES); // hold chua het han

        insertBooking(93L, PR_A, "BK-HOLD-A", "PENDING_PAYMENT", start, end, holdExp);
        insertBooking(94L, PR_B, "BK-HOLD-B", "PENDING_PAYMENT", start, end, holdExp);

        List<Long> available = allocationService.findAvailableRoomTypeIds(start, end, now);
        assertThat(available).doesNotContain(RT_ID);
    }

    /** (d) Phong co PENDING_PAYMENT da het hold (expired) -> van available. */
    @Test
    void roomWithExpiredHold_isAvailable() {
        Instant start   = future(20);
        Instant end     = future(22);
        Instant now     = Instant.now();
        Instant holdExp = now.minus(5, ChronoUnit.MINUTES); // hold da het han

        insertBooking(95L, PR_A, "BK-EXP-A", "PENDING_PAYMENT", start, end, holdExp);
        insertBooking(96L, PR_B, "BK-EXP-B", "PENDING_PAYMENT", start, end, holdExp);

        List<Long> available = allocationService.findAvailableRoomTypeIds(start, end, now);
        assertThat(available).contains(RT_ID);
    }

    /** (e) Phong co active block overlap -> khong available. */
    @Test
    void roomWithActiveBlockOverlap_isNotAvailable() {
        Instant start = future(25);
        Instant end   = future(27);
        Instant now   = Instant.now();

        // Block ca 2 phong (released_at IS NULL = active)
        insertBlock(901L, PR_A, start, end);
        insertBlock(902L, PR_B, start, end);

        List<Long> available = allocationService.findAvailableRoomTypeIds(start, end, now);
        assertThat(available).doesNotContain(RT_ID);
    }

    /** (f) Phong co released block overlap -> van available. */
    @Test
    void roomWithReleasedBlockOverlap_isAvailable() {
        Instant start    = future(30);
        Instant end      = future(32);
        Instant now      = Instant.now();
        Instant released = now.minus(1, ChronoUnit.HOURS);

        // Block da duoc giai phong (released_at IS NOT NULL)
        insertReleasedBlock(903L, PR_A, start, end, released);
        insertReleasedBlock(904L, PR_B, start, end, released);

        List<Long> available = allocationService.findAvailableRoomTypeIds(start, end, now);
        assertThat(available).contains(RT_ID);
    }

    /**
     * (g) Khoang [startAt, endAt): booking ket thuc dung luc startAt yeu cau -> KHONG overlap.
     * Hai ky ke nhau: [D, D+2) va [D+2, D+4) -> co the dat lien tiep.
     */
    @Test
    void adjacentPeriods_doNotOverlap() {
        Instant d    = future(35);
        Instant dP2  = d.plus(2, ChronoUnit.DAYS);
        Instant dP4  = d.plus(4, ChronoUnit.DAYS);
        Instant now  = Instant.now();
        Instant holdExp = now.plus(15, ChronoUnit.MINUTES);

        // Booking ky 1: [d, d+2)
        insertBooking(97L, PR_A, "BK-ADJ-A", "CONFIRMED", d, dP2, now.minus(1, ChronoUnit.HOURS));

        // Truy van ky 2: [d+2, d+4) -> khong overlap voi ky 1
        List<Long> available = allocationService.findAvailableRoomTypeIds(dP2, dP4, now);
        assertThat(available).contains(RT_ID);

        Optional<Long> locked = allocationService.findAndLockAvailablePhysicalRoom(RT_ID, dP2, dP4, now);
        assertThat(locked).isPresent();
        // Phong bi chon phai la PR_A hoac PR_B
        assertThat(locked.get()).isIn(PR_A, PR_B);
    }

    /** (h) Filter guests: maxOccupancy=2, guests=3 -> khong co phong nao phu hop. */
    @Test
    void guestFilter_excludesRoomTypeWhenOccupancyInsufficient() {
        Instant start = future(40);
        Instant end   = future(42);

        List<com.hotelpms.room.dto.RoomTypeResponse> result =
                roomService.listAvailableRoomTypes(start, end, 3, Instant.now());

        // RT_ID co maxOccupancy=2 < 3 nen khong xuat hien
        assertThat(result.stream().map(r -> r.id()).toList()).doesNotContain(RT_ID);
    }

    /** (h2) Filter guests: maxOccupancy=2, guests=2 -> co phong. */
    @Test
    void guestFilter_includesRoomTypeWhenOccupancySufficient() {
        Instant start = future(43);
        Instant end   = future(45);

        List<com.hotelpms.room.dto.RoomTypeResponse> result =
                roomService.listAvailableRoomTypes(start, end, 2, Instant.now());

        assertThat(result.stream().map(r -> r.id()).toList()).contains(RT_ID);
    }

    /** (i) API endpoint validate: endAt <= startAt -> 400. */
    @Test
    void availableEndpoint_rejectsBadDateRange() throws Exception {
        Instant start = future(50);
        Instant end   = start; // bang nhau -> invalid

        HttpResponse<String> res = get("/api/v1/rooms/available?startAt=" + start + "&endAt=" + end + "&guests=1");
        assertThat(res.statusCode()).isEqualTo(400);
    }

    /** (i2) API endpoint validate: guests=0 -> 400. */
    @Test
    void availableEndpoint_rejectsZeroGuests() throws Exception {
        Instant start = future(55);
        Instant end   = future(57);

        HttpResponse<String> res = get("/api/v1/rooms/available?startAt=" + start + "&endAt=" + end + "&guests=0");
        assertThat(res.statusCode()).isEqualTo(400);
    }

    /** (i3) API endpoint: hop le -> 200, tra danh sach phong trong. */
    @Test
    void availableEndpoint_returns200WithAvailableRooms() throws Exception {
        Instant start = future(60);
        Instant end   = future(62);

        HttpResponse<String> res = get("/api/v1/rooms/available?startAt=" + start + "&endAt=" + end + "&guests=1");
        assertThat(res.statusCode()).isEqualTo(200);

        JsonNode body = json.readTree(res.body());
        assertThat(body.isArray()).isTrue();
        // Phai co RT_ID trong ket qua
        boolean found = false;
        for (JsonNode node : body) {
            if (node.path("id").asLong() == RT_ID) { found = true; break; }
        }
        assertThat(found).as("RT_ID %d should be in available list", RT_ID).isTrue();
    }

    /**
     * (j) Concurrency: 2 request dong thoi cung 1 phong kha dung.
     * Khi ca 2 goi findAndLockAvailablePhysicalRoom, chi 1 lay duoc phong
     * (transaction 2 thay phong da bi khoa nen skip hoac thay khong con phong trong).
     * Chay trong cung 1 thread nen khong test thuc su lock -- kiem tra logic filter.
     */
    @Test
    void concurrentAllocation_bothCallsGetDistinctRooms_orSecondGetsEmpty() {
        // Chi 1 phong active
        jdbc.update("UPDATE physical_rooms SET is_active = false WHERE id = ?", PR_B);

        Instant start = future(65);
        Instant end   = future(67);
        Instant now   = Instant.now();

        // Goi thu nhat lay phong
        Optional<Long> first = allocationService.findAndLockAvailablePhysicalRoom(RT_ID, start, end, now);
        assertThat(first).isPresent();

        // Sau khi goi thu nhat, simulate da tao booking tren phong do
        Instant holdExp = now.plus(15, ChronoUnit.MINUTES);
        insertBooking(98L, first.get(), "BK-CONC-1", "PENDING_PAYMENT", start, end, holdExp);

        // Goi thu hai: phong duy nhat da bi giu -> khong con phong
        Optional<Long> second = allocationService.findAndLockAvailablePhysicalRoom(RT_ID, start, end, now);
        assertThat(second).isEmpty();

        // Khoi phuc PR_B
        jdbc.update("UPDATE physical_rooms SET is_active = true WHERE id = ?", PR_B);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Instant N ngay ke tu bay gio (truncated to hours de test on dinh). */
    private Instant future(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    }

    private void insertBooking(long id, long physicalRoomId, String code, String status,
                               Instant start, Instant end, Instant holdExpires) {
        // customer_id = 3 (customer@hotel-pms.test tu demo seed)
        jdbc.update("""
            INSERT INTO bookings
              (id, booking_code, customer_id, room_type_id, physical_room_id, guest_count,
               start_at, end_at, hold_expires_at, status,
               base_amount, discount_rate, discount_amount, payable_amount)
            VALUES (?, ?, 3, ?, ?, 1, ?, ?, ?, ?,
                    1000000.00, 0.00, 0.00, 1000000.00)
            ON CONFLICT (id) DO NOTHING
        """, id, code, RT_ID, physicalRoomId,
             java.sql.Timestamp.from(start), java.sql.Timestamp.from(end),
             java.sql.Timestamp.from(holdExpires), status);
    }

    private void insertBlock(long id, long physicalRoomId, Instant start, Instant end) {
        jdbc.update("""
            INSERT INTO room_blocks
              (id, physical_room_id, start_at, end_at, reason, created_by_user_id, created_at)
            VALUES (?, ?, ?, ?, 'Test block', ?, NOW())
            ON CONFLICT (id) DO NOTHING
        """, id, physicalRoomId,
             java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), STAFF_ID);
    }

    private void insertReleasedBlock(long id, long physicalRoomId,
                                     Instant start, Instant end, Instant releasedAt) {
        jdbc.update("""
            INSERT INTO room_blocks
              (id, physical_room_id, start_at, end_at, reason, created_by_user_id, created_at,
               released_by_user_id, released_at)
            VALUES (?, ?, ?, ?, 'Released block', ?, NOW(), ?, ?)
            ON CONFLICT (id) DO NOTHING
        """, id, physicalRoomId,
             java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), STAFF_ID,
             STAFF_ID, java.sql.Timestamp.from(releasedAt));
    }

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
