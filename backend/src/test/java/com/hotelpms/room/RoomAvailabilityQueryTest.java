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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M2-04 — Kiem tra truy van phong vat ly va endpoint /api/v1/rooms/available.
 * Owner: Le Phuoc Sang (PhuocSang).
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = RoomAvailabilityQueryTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class RoomAvailabilityQueryTest {

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
        props.add("app.jwt.secret", () -> "avail-test-signing-key-at-least-thirty-two-chars!!");
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

    private static final long RT_ID = 9001L;
    private static final long PR_A  = 90011L;
    private static final long PR_B  = 90012L;
    private static final long STAFF_ID = 2L;

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoomAllocationService allocationService;
    @Autowired private RoomService roomService;
    @Autowired private ObjectMapper json;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM bookings WHERE room_type_id = ?", RT_ID);
        jdbc.update("DELETE FROM room_blocks WHERE physical_room_id IN (?, ?)", PR_A, PR_B);
        jdbc.update("""
            INSERT INTO room_types (id,code,name,description,max_occupancy,base_price_per_night,is_active)
            VALUES (?,?,?,?,?,?,true)
            ON CONFLICT (id) DO UPDATE SET is_active=true, max_occupancy=2
            """, RT_ID, "AVAIL_TEST", "Availability Test Room", "Test", (short) 2, new java.math.BigDecimal("1000000.00"));
        jdbc.update("""
            INSERT INTO physical_rooms (id,room_type_id,room_number,floor_number,is_active)
            VALUES (?,?,?,9,true) ON CONFLICT (id) DO UPDATE SET is_active=true
            """, PR_A, RT_ID, "AVAIL-A");
        jdbc.update("""
            INSERT INTO physical_rooms (id,room_type_id,room_number,floor_number,is_active)
            VALUES (?,?,?,9,true) ON CONFLICT (id) DO UPDATE SET is_active=true
            """, PR_B, RT_ID, "AVAIL-B");
    }

    // (a) Phong khong co booking/block -> available
    @Test
    void noBookingOrBlock_roomIsAvailable() {
        Instant s = f(5), e = f(7), now = Instant.now();
        assertThat(allocationService.findAvailableRoomTypeIds(s, e, now)).contains(RT_ID);
        assertThat(allocationService.findAndLockAvailablePhysicalRoom(RT_ID, s, e, now)).isPresent();
    }

    // (b) Booking CONFIRMED overlap -> khong available
    @Test
    void confirmedBookingOverlap_roomNotAvailable() {
        Instant s = f(10), e = f(12), now = Instant.now();
        insertBooking(91L, PR_A, "BK-CB-A", "CONFIRMED", s, e, now.minus(5, ChronoUnit.MINUTES));
        insertBooking(92L, PR_B, "BK-CB-B", "CONFIRMED", s, e, now.minus(5, ChronoUnit.MINUTES));
        assertThat(allocationService.findAvailableRoomTypeIds(s, e, now)).doesNotContain(RT_ID);
    }

    // (c) PENDING_PAYMENT con hold -> khong available
    @Test
    void activeHoldOverlap_roomNotAvailable() {
        Instant s = f(15), e = f(17), now = Instant.now();
        Instant holdExp = now.plus(15, ChronoUnit.MINUTES);
        insertBooking(93L, PR_A, "BK-AH-A", "PENDING_PAYMENT", s, e, holdExp);
        insertBooking(94L, PR_B, "BK-AH-B", "PENDING_PAYMENT", s, e, holdExp);
        assertThat(allocationService.findAvailableRoomTypeIds(s, e, now)).doesNotContain(RT_ID);
    }

    // (d) PENDING_PAYMENT hold het han -> van available
    @Test
    void expiredHold_roomStillAvailable() {
        Instant s = f(20), e = f(22), now = Instant.now();
        Instant holdExp = now.minus(5, ChronoUnit.MINUTES);
        insertBooking(95L, PR_A, "BK-EH-A", "PENDING_PAYMENT", s, e, holdExp);
        insertBooking(96L, PR_B, "BK-EH-B", "PENDING_PAYMENT", s, e, holdExp);
        assertThat(allocationService.findAvailableRoomTypeIds(s, e, now)).contains(RT_ID);
    }

    // (e) Active block overlap -> khong available
    @Test
    void activeBlock_roomNotAvailable() {
        Instant s = f(25), e = f(27), now = Instant.now();
        insertBlock(901L, PR_A, s, e);
        insertBlock(902L, PR_B, s, e);
        assertThat(allocationService.findAvailableRoomTypeIds(s, e, now)).doesNotContain(RT_ID);
    }

    // (f) Released block overlap -> van available
    @Test
    void releasedBlock_roomStillAvailable() {
        Instant s = f(30), e = f(32), now = Instant.now();
        insertReleasedBlock(903L, PR_A, s, e, now.minus(1, ChronoUnit.HOURS));
        insertReleasedBlock(904L, PR_B, s, e, now.minus(1, ChronoUnit.HOURS));
        assertThat(allocationService.findAvailableRoomTypeIds(s, e, now)).contains(RT_ID);
    }

    // (g) Khoang ke nhau [D,D+2) va [D+2,D+4): KHONG overlap
    @Test
    void adjacentPeriods_doNotOverlap() {
        Instant d = f(35), dP2 = d.plus(2, ChronoUnit.DAYS), dP4 = d.plus(4, ChronoUnit.DAYS);
        Instant now = Instant.now();
        insertBooking(97L, PR_A, "BK-ADJ", "CONFIRMED", d, dP2, now.minus(1, ChronoUnit.HOURS));
        // Phong B con trong trong ca 2 ky, phong A chi bi chiem ky 1
        List<Long> avail = allocationService.findAvailableRoomTypeIds(dP2, dP4, now);
        assertThat(avail).contains(RT_ID);
    }

    // (h) guests filter: maxOccupancy=2, guests=3 -> khong co phong
    @Test
    void guestFilter_tooManyGuests_excluded() {
        Instant s = f(40), e = f(42);
        var result = roomService.listAvailableRoomTypes(s, e, 3, Instant.now());
        assertThat(result.stream().map(r -> r.id()).toList()).doesNotContain(RT_ID);
    }

    // (h2) guests=2 -> co phong
    @Test
    void guestFilter_exactCapacity_included() {
        Instant s = f(43), e = f(45);
        var result = roomService.listAvailableRoomTypes(s, e, 2, Instant.now());
        assertThat(result.stream().map(r -> r.id()).toList()).contains(RT_ID);
    }

    // (i) API: endAt == startAt -> 400
    @Test
    void availableEndpoint_equalDates_returns400() throws Exception {
        Instant s = f(50);
        var res = get("/api/v1/rooms/available?startAt=" + s + "&endAt=" + s + "&guests=1");
        assertThat(res.statusCode()).isEqualTo(400);
    }

    // (i2) guests=0 -> 400
    @Test
    void availableEndpoint_zeroGuests_returns400() throws Exception {
        Instant s = f(55), e = f(57);
        var res = get("/api/v1/rooms/available?startAt=" + s + "&endAt=" + e + "&guests=0");
        assertThat(res.statusCode()).isEqualTo(400);
    }

    // (i3) hop le -> 200 co RT_ID
    @Test
    void availableEndpoint_validParams_returns200WithRooms() throws Exception {
        Instant s = f(60), e = f(62);
        var res = get("/api/v1/rooms/available?startAt=" + s + "&endAt=" + e + "&guests=1");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(res.body());
        assertThat(body.isArray()).isTrue();
        boolean found = false;
        for (JsonNode node : body) {
            if (node.path("id").asLong() == RT_ID) { found = true; break; }
        }
        assertThat(found).as("RT_ID %d should appear in /available response", RT_ID).isTrue();
    }

    // (j) Sau khi lay phong + tao booking, khong con phong
    @Test
    void afterHoldCreated_roomUnavailable() {
        jdbc.update("UPDATE physical_rooms SET is_active=false WHERE id=?", PR_B);
        Instant s = f(65), e = f(67), now = Instant.now();

        Optional<Long> first = allocationService.findAndLockAvailablePhysicalRoom(RT_ID, s, e, now);
        assertThat(first).isPresent();

        insertBooking(98L, first.get(), "BK-CONC", "PENDING_PAYMENT", s, e,
                      now.plus(15, ChronoUnit.MINUTES));

        Optional<Long> second = allocationService.findAndLockAvailablePhysicalRoom(RT_ID, s, e, now);
        assertThat(second).isEmpty();

        jdbc.update("UPDATE physical_rooms SET is_active=true WHERE id=?", PR_B);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Instant f(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    }

    private void insertBooking(long id, long physRoomId, String code, String status,
                               Instant s, Instant e, Instant holdExp) {
        jdbc.update("""
            INSERT INTO bookings
              (id,booking_code,customer_id,room_type_id,physical_room_id,guest_count,
               start_at,end_at,hold_expires_at,status,
               base_amount,discount_rate,discount_amount,payable_amount)
            VALUES (?,?,3,?,?,1,?,?,?,?,
                    1000000.00,0.00,0.00,1000000.00)
            ON CONFLICT (id) DO NOTHING
            """, id, code, RT_ID, physRoomId,
             ts(s), ts(e), ts(holdExp), status);
    }

    private void insertBlock(long id, long physRoomId, Instant s, Instant e) {
        jdbc.update("""
            INSERT INTO room_blocks
              (id,physical_room_id,start_at,end_at,reason,created_by_user_id,created_at)
            VALUES (?,?,?,?,'Test block',?,NOW())
            ON CONFLICT (id) DO NOTHING
            """, id, physRoomId, ts(s), ts(e), STAFF_ID);
    }

    private void insertReleasedBlock(long id, long physRoomId, Instant s, Instant e, Instant rel) {
        jdbc.update("""
            INSERT INTO room_blocks
              (id,physical_room_id,start_at,end_at,reason,created_by_user_id,created_at,
               released_by_user_id,released_at)
            VALUES (?,?,?,?,'Released block',?,NOW(),?,?)
            ON CONFLICT (id) DO NOTHING
            """, id, physRoomId, ts(s), ts(e), STAFF_ID, STAFF_ID, ts(rel));
    }

    private java.sql.Timestamp ts(Instant i) { return java.sql.Timestamp.from(i); }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}