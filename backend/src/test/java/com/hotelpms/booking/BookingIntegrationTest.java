package com.hotelpms.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelpms.auth.entity.RoleCode;
import com.hotelpms.auth.entity.User;
import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.booking.dto.CreateBookingRequest;
import com.hotelpms.booking.entity.Booking;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.booking.repository.BookingRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = BookingIntegrationTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class BookingIntegrationTest {

    private static final String PASSWORD = "TestDemo123!";
    // Fixed hotel dates: November 1 at 12:00 through November 3 at 10:00 (+07:00).
    private static final Instant TWO_NIGHT_CHECK_IN = Instant.parse("2030-11-01T05:00:00Z");
    private static final Instant TWO_NIGHT_CHECK_OUT = Instant.parse("2030-11-03T03:00:00Z");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
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
        properties.add("app.jwt.secret", () -> "integration-test-signing-key-at-least-thirty-two-characters");
        properties.add("app.jwt.access-token-ttl-seconds", () -> "3600");
        properties.add("app.demo-seed.enabled", () -> "true");
        properties.add("app.demo-seed.password", () -> PASSWORD);
    }

    public static class PostgresCleanupListener extends AbstractTestExecutionListener {
        @Override
        public int getOrder() { return 1000; }

        @Override
        public void afterTestClass(TestContext context) throws IOException {
            if (postgres != null) postgres.close();
        }
    }

    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CustomerProfileRepository profileRepository;
    @Autowired private com.hotelpms.booking.service.BookingService bookingService;

    private Long roomTypeId;
    private Long physicalRoomId101;
    private Long physicalRoomId102;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM payment_attempts");
        bookingRepository.deleteAll();

        // Keep the two-room fixture separate from V2 seeds and repository-test rooms.
        roomTypeId = 1001L;
        physicalRoomId101 = 10101L;
        physicalRoomId102 = 10102L;

        jdbc.update("""
            INSERT INTO room_types (id, code, name, description, max_occupancy, base_price_per_night, is_active)
            VALUES (?, 'BOOKING_TEST', 'Booking Test Room', 'Room description', 2, 1200000.00, true)
            ON CONFLICT (id) DO UPDATE SET base_price_per_night = 1200000.00, is_active = true
        """, roomTypeId);

        jdbc.update("""
            INSERT INTO physical_rooms (id, room_type_id, room_number, floor_number, is_active)
            VALUES (?, ?, 'BOOKING_TEST_101', 1, true)
            ON CONFLICT (id) DO UPDATE SET is_active = true
        """, physicalRoomId101, roomTypeId);

        jdbc.update("""
            INSERT INTO physical_rooms (id, room_type_id, room_number, floor_number, is_active)
            VALUES (?, ?, 'BOOKING_TEST_102', 1, true)
            ON CONFLICT (id) DO UPDATE SET is_active = true
        """, physicalRoomId102, roomTypeId);

        // Clear any room blocks
        jdbc.update("DELETE FROM room_blocks");
    }

    @Test
    void testCreateBooking_SuccessAndRetrieve() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);

        Instant startAt = TWO_NIGHT_CHECK_IN;
        Instant endAt = TWO_NIGHT_CHECK_OUT;

        CreateBookingRequest request = new CreateBookingRequest(roomTypeId, (short) 2, startAt, endAt);

        HttpResponse<String> response = post("/api/v1/bookings", request, token);
        assertThat(response.statusCode()).isEqualTo(201);

        JsonNode body = tree(response);
        String bookingCode = body.path("bookingCode").asText();
        assertThat(bookingCode).startsWith("BK");
        assertThat(body.path("status").asText()).isEqualTo(BookingStatus.PENDING_PAYMENT.name());
        assertThat(body.path("baseAmount").asDouble()).isEqualTo(2400000.00);
        assertThat(body.path("payableAmount").asDouble()).isEqualTo(2400000.00);
        assertThat(body.path("discountRate").asDouble()).isEqualTo(0.0);

        // Verify hold expires in ~15 minutes
        Instant holdExpiresAt = Instant.parse(body.path("holdExpiresAt").asText());
        assertThat(Duration.between(Instant.now(), holdExpiresAt).toMinutes()).isBetween(14L, 16L);

        // Verify physical_room_id is NOT exposed in response
        assertThat(body.has("physicalRoomId")).isFalse();
        assertThat(body.has("physical_room_id")).isFalse();

        // Retrieve by bookingCode
        HttpResponse<String> getResponse = get("/api/v1/bookings/" + bookingCode, token);
        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(tree(getResponse).path("bookingCode").asText()).isEqualTo(bookingCode);

        // Retrieve customer bookings list
        HttpResponse<String> listResponse = get("/api/v1/bookings", token);
        assertThat(listResponse.statusCode()).isEqualTo(200);
        JsonNode listNode = tree(listResponse);
        assertThat(listNode.isArray()).isTrue();
        assertThat(listNode.size()).isEqualTo(1);
        assertThat(listNode.get(0).path("bookingCode").asText()).isEqualTo(bookingCode);
    }

    @Test
    void testCreateBooking_VIPTierDiscount() throws Exception {
        User user = userRepository.findByEmailIgnoreCase("customer@hotel-pms.test").orElseThrow();
        jdbc.update("UPDATE customer_profiles SET tier_code = 'VIP' WHERE user_id = ?", user.getId());

        String token = login("customer@hotel-pms.test", PASSWORD);

        Instant startAt = TWO_NIGHT_CHECK_IN;
        Instant endAt = TWO_NIGHT_CHECK_OUT;

        CreateBookingRequest request = new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt);

        HttpResponse<String> response = post("/api/v1/bookings", request, token);
        assertThat(response.statusCode()).isEqualTo(201);

        JsonNode body = tree(response);
        assertThat(body.path("baseAmount").asDouble()).isEqualTo(2400000.00);
        assertThat(body.path("discountRate").asDouble()).isEqualTo(0.1000);
        assertThat(body.path("discountAmount").asDouble()).isEqualTo(240000.00);
        assertThat(body.path("payableAmount").asDouble()).isEqualTo(2160000.00);

        // Reset tier back to STANDARD
        jdbc.update("UPDATE customer_profiles SET tier_code = 'STANDARD' WHERE user_id = ?", user.getId());
    }

    @ParameterizedTest(name = "{0} to {1}: {2} VND")
    @CsvSource({
            "2030-11-01T05:00:00Z, 2030-11-03T03:00:00Z, 2400000.00",
            "2030-11-01T17:00:00Z, 2030-11-03T15:00:00Z, 1200000.00",
            "2030-11-01T18:00:00Z, 2030-11-03T16:00:00Z, 1200000.00",
            "2030-11-01T16:00:00Z, 2030-11-03T14:00:00Z, 2400000.00"
    })
    void testCreateBooking_PriceUsesHotelCalendarDates(
            String startAt, String endAt, String expectedAmount) throws Exception {
        // Cover hotel check-in at noon, midnight, 01:00 and 23:00 via the real API.
        String token = login("customer@hotel-pms.test", PASSWORD);
        CreateBookingRequest request = new CreateBookingRequest(
                roomTypeId, (short) 1, Instant.parse(startAt), Instant.parse(endAt));

        HttpResponse<String> response = post("/api/v1/bookings", request, token);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = tree(response);
        assertThat(body.path("status").asText()).isEqualTo(BookingStatus.PENDING_PAYMENT.name());
        assertThat(new BigDecimal(body.path("baseAmount").asText()))
                .isEqualByComparingTo(expectedAmount);
        assertThat(new BigDecimal(body.path("payableAmount").asText()))
                .isEqualByComparingTo(expectedAmount);
    }

    @Test
    void testCreateBooking_RoomNotAvailable_WhenAllRoomsOccupied() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);

        Instant startAt = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        CreateBookingRequest request = new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt);

        // 1st booking -> room 101 allocated
        HttpResponse<String> res1 = post("/api/v1/bookings", request, token);
        assertThat(res1.statusCode()).isEqualTo(201);

        // 2nd booking -> room 102 allocated
        HttpResponse<String> res2 = post("/api/v1/bookings", request, token);
        assertThat(res2.statusCode()).isEqualTo(201);

        // 3rd booking -> No physical room left!
        HttpResponse<String> res3 = post("/api/v1/bookings", request, token);
        assertThat(res3.statusCode()).isEqualTo(409);
        assertThat(tree(res3).path("code").asText()).isEqualTo("ROOM_NOT_AVAILABLE");
    }

    @Test
    void testCreateBooking_RoomNotAvailable_WhenRoomBlocked() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);
        User staff = userRepository.findByEmailIgnoreCase("staff@hotel-pms.test").orElseThrow();

        Instant startAt = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        // Block room 102 during this period
        jdbc.update("""
            INSERT INTO room_blocks (id, physical_room_id, start_at, end_at, reason, created_by_user_id)
            VALUES (9001, ?, ?, ?, 'Maintenance', ?)
        """, physicalRoomId102, java.sql.Timestamp.from(startAt), java.sql.Timestamp.from(endAt), staff.getId());

        CreateBookingRequest request = new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt);

        // 1st booking -> gets room 101
        HttpResponse<String> res1 = post("/api/v1/bookings", request, token);
        assertThat(res1.statusCode()).isEqualTo(201);

        // 2nd booking -> room 102 is blocked, none left -> 409
        HttpResponse<String> res2 = post("/api/v1/bookings", request, token);
        assertThat(res2.statusCode()).isEqualTo(409);
        assertThat(tree(res2).path("code").asText()).isEqualTo("ROOM_NOT_AVAILABLE");
    }

    @Test
    void testAccessControl_OtherCustomerCannotViewBooking() throws Exception {
        // Register customer 2
        String customer2Email = "customer2-" + UUID.randomUUID() + "@hotel-pms.test";
        HttpResponse<String> regRes = post("/api/v1/auth/register", Map.of(
                "email", customer2Email,
                "password", PASSWORD,
                "fullName", "Second Customer"
        ), null);
        assertThat(regRes.statusCode()).isEqualTo(201);
        String token2 = tree(regRes).path("accessToken").asText();

        // Customer 1 books
        String token1 = login("customer@hotel-pms.test", PASSWORD);
        Instant startAt = Instant.now().plus(15, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        HttpResponse<String> bookRes = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token1);
        assertThat(bookRes.statusCode()).isEqualTo(201);
        String bookingCode = tree(bookRes).path("bookingCode").asText();

        // Customer 2 attempts to view Customer 1's booking -> 403 Forbidden
        HttpResponse<String> forbiddenRes = get("/api/v1/bookings/" + bookingCode, token2);
        assertThat(forbiddenRes.statusCode()).isEqualTo(403);

        // Staff views Customer 1's booking -> 200 OK
        String staffToken = login("staff@hotel-pms.test", PASSWORD);
        HttpResponse<String> staffRes = get("/api/v1/bookings/" + bookingCode, staffToken);
        assertThat(staffRes.statusCode()).isEqualTo(200);
    }

    @Test
    void testConcurrentBooking_OnlyOneSucceedsWhenSingleRoomAvailable() throws Exception {
        // Make room 102 inactive, so ONLY room 101 is available
        jdbc.update("UPDATE physical_rooms SET is_active = false WHERE id = ?", physicalRoomId102);

        String token = login("customer@hotel-pms.test", PASSWORD);
        Instant startAt = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        CreateBookingRequest request = new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt);

        // Fire 2 concurrent requests
        CompletableFuture<HttpResponse<String>> call1 = CompletableFuture.supplyAsync(() -> {
            try {
                return post("/api/v1/bookings", request, token);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<HttpResponse<String>> call2 = CompletableFuture.supplyAsync(() -> {
            try {
                return post("/api/v1/bookings", request, token);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        HttpResponse<String> r1 = call1.join();
        HttpResponse<String> r2 = call2.join();

        List<Integer> statuses = List.of(r1.statusCode(), r2.statusCode());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        // Ensure database has exactly 1 booking
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    @Test
    void testCancelBooking_CustomerAndIdempotency() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);
        Instant startAt = Instant.now().plus(25, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        HttpResponse<String> createRes = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token);
        assertThat(createRes.statusCode()).isEqualTo(201);
        String bookingCode = tree(createRes).path("bookingCode").asText();

        // Cancel
        HttpResponse<String> cancelRes = post("/api/v1/bookings/" + bookingCode + "/cancel", null, token);
        assertThat(cancelRes.statusCode()).isEqualTo(200);
        JsonNode cancelBody = tree(cancelRes);
        assertThat(cancelBody.path("status").asText()).isEqualTo(BookingStatus.CANCELLED.name());
        assertThat(cancelBody.path("cancelledAt").asText()).isNotBlank();

        // Idempotent cancel
        HttpResponse<String> cancelAgainRes = post("/api/v1/bookings/" + bookingCode + "/cancel", null, token);
        assertThat(cancelAgainRes.statusCode()).isEqualTo(200);
        assertThat(tree(cancelAgainRes).path("status").asText()).isEqualTo(BookingStatus.CANCELLED.name());
    }

    @Test
    void testConfirmBooking_SuccessAndIdempotency() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);
        Instant startAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        HttpResponse<String> createRes = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token);
        assertThat(createRes.statusCode()).isEqualTo(201);
        String bookingCode = tree(createRes).path("bookingCode").asText();

        // Confirm
        pay(bookingCode, token);
        HttpResponse<String> confirmRes = get("/api/v1/bookings/" + bookingCode, token);
        assertThat(confirmRes.statusCode()).isEqualTo(200);
        JsonNode confirmBody = tree(confirmRes);
        assertThat(confirmBody.path("status").asText()).isEqualTo(BookingStatus.CONFIRMED.name());
        assertThat(confirmBody.path("confirmedAt").asText()).isNotBlank();

        // Idempotent confirm
        pay(bookingCode, token);
        HttpResponse<String> confirmAgainRes = get("/api/v1/bookings/" + bookingCode, token);
        assertThat(confirmAgainRes.statusCode()).isEqualTo(200);
        assertThat(tree(confirmAgainRes).path("status").asText()).isEqualTo(BookingStatus.CONFIRMED.name());
    }

    @Test
    void testConfirmBooking_FailsWhenHoldExpired() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);
        Instant startAt = Instant.now().plus(35, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        HttpResponse<String> createRes = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token);
        assertThat(createRes.statusCode()).isEqualTo(201);
        String bookingCode = tree(createRes).path("bookingCode").asText();

        // Simulate expired hold by moving hold_expires_at 5 minutes into the past
        jdbc.update("UPDATE bookings SET hold_expires_at = ? WHERE booking_code = ?",
                java.sql.Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)), bookingCode);

        // Confirming must fail with 409 BOOKING_EXPIRED
        HttpResponse<String> confirmRes = post("/api/v1/bookings/" + bookingCode + "/confirm", null, token);
        assertThat(confirmRes.statusCode()).isEqualTo(409);
        assertThat(tree(confirmRes).path("code").asText()).isEqualTo("BOOKING_EXPIRED");

        // Verify status in DB became EXPIRED
        var booking = bookingRepository.findByBookingCode(bookingCode).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(booking.getExpiredAt()).isNotNull();
    }

    @Test
    void testCompleteBooking_StaffOnly() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);
        Instant startAt = Instant.now().plus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        HttpResponse<String> createRes = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token);
        assertThat(createRes.statusCode()).isEqualTo(201);
        String bookingCode = tree(createRes).path("bookingCode").asText();

        // Customer confirms booking
        pay(bookingCode, token);

        // Customer tries to complete -> 403 Forbidden
        HttpResponse<String> custCompleteRes = post("/api/v1/bookings/" + bookingCode + "/complete", null, token);
        assertThat(custCompleteRes.statusCode()).isEqualTo(403);

        jdbc.update("UPDATE bookings SET start_at=?,end_at=? WHERE booking_code=?",
            java.sql.Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS)),
            java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), bookingCode);

        // Staff completes a paid, departed stay -> 200 OK
        String staffToken = login("staff@hotel-pms.test", PASSWORD);
        HttpResponse<String> staffCompleteRes = post("/api/v1/bookings/" + bookingCode + "/complete", null, staffToken);
        assertThat(staffCompleteRes.statusCode()).isEqualTo(200);
        JsonNode completeBody = tree(staffCompleteRes);
        assertThat(completeBody.path("status").asText()).isEqualTo(BookingStatus.COMPLETED.name());
        assertThat(completeBody.path("completedAt").asText()).isNotBlank();
    }

    @Test
    void testSweepExpiredHolds_ReleasesRoom() throws Exception {
        // Only 1 room available
        jdbc.update("UPDATE physical_rooms SET is_active = false WHERE id = ?", physicalRoomId102);

        String token = login("customer@hotel-pms.test", PASSWORD);
        Instant startAt = Instant.now().plus(45, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        HttpResponse<String> res1 = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token);
        assertThat(res1.statusCode()).isEqualTo(201);
        String bookingCode = tree(res1).path("bookingCode").asText();

        // Update hold_expires_at to the past
        jdbc.update("UPDATE bookings SET hold_expires_at = ? WHERE booking_code = ?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), bookingCode);


        // Run sweep
        int swept = bookingService.sweepExpiredHolds(Instant.now());
        assertThat(swept).isGreaterThanOrEqualTo(1);

        // After sweep: room is now available for new bookings!
        HttpResponse<String> availableRes = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token);
        assertThat(availableRes.statusCode()).isEqualTo(201);
    }

    @Test
    void testHoldRace_ConfirmVsSweepRaceCondition() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);
        Instant startAt = Instant.now().plus(50, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        HttpResponse<String> createRes = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token);
        assertThat(createRes.statusCode()).isEqualTo(201);
        String bookingCode = tree(createRes).path("bookingCode").asText();

        // Set hold to expired
        jdbc.update("UPDATE bookings SET hold_expires_at = ? WHERE booking_code = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.SECONDS)), bookingCode);

        // Run concurrent confirm vs sweep
        CompletableFuture<HttpResponse<String>> confirmCall = CompletableFuture.supplyAsync(() -> {
            try {
                return pay(bookingCode, token);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<Integer> sweepCall = CompletableFuture.supplyAsync(() ->
                bookingService.sweepExpiredHolds(Instant.now())
        );

        HttpResponse<String> confirmResponse = confirmCall.join();
        sweepCall.join();

        // The booking MUST end up in either CONFIRMED or EXPIRED, never corrupted
        var finalBooking = bookingRepository.findByBookingCode(bookingCode).orElseThrow();
        assertThat(finalBooking.getStatus()).isIn(BookingStatus.CONFIRMED, BookingStatus.EXPIRED);

        if (finalBooking.getStatus() == BookingStatus.EXPIRED) {
            assertThat(confirmResponse.statusCode()).isEqualTo(409);
        } else {
            assertThat(confirmResponse.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void testAutoCompleteDepartedBookings_CompletesConfirmedPastEndAt() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);
        Instant startAt = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant endAt = startAt.plus(2, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        // 1. Create booking
        HttpResponse<String> createRes = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token);
        assertThat(createRes.statusCode()).isEqualTo(201);
        String bookingCode = tree(createRes).path("bookingCode").asText();

        // 2. Confirm booking
        pay(bookingCode, token);
        HttpResponse<String> confirmRes = get("/api/v1/bookings/" + bookingCode, token);
        assertThat(confirmRes.statusCode()).isEqualTo(200);

        // 3. Update start_at and end_at to past (stay completed)
        Instant pastStart = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant pastEnd = Instant.now().minus(2, ChronoUnit.HOURS);
        jdbc.update("UPDATE bookings SET start_at = ?, end_at = ? WHERE booking_code = ?",
                java.sql.Timestamp.from(pastStart), java.sql.Timestamp.from(pastEnd), bookingCode);

        // 4. Run auto-complete
        int completedCount = bookingService.autoCompleteDepartedBookings(Instant.now());
        assertThat(completedCount).isGreaterThanOrEqualTo(1);

        // Verify status in DB is COMPLETED with completed_at set
        Booking completed = bookingRepository.findByBookingCode(bookingCode).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();

        // 5. Idempotency: run auto-complete again, should complete 0 and not fail
        int secondRun = bookingService.autoCompleteDepartedBookings(Instant.now());
        assertThat(secondRun).isEqualTo(0);
        Booking unchanged = bookingRepository.findByBookingCode(bookingCode).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }


    private String createSprintBooking(String token) throws Exception {
        var result = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1,
                Instant.parse("2035-11-01T05:00:00Z"), Instant.parse("2035-11-03T03:00:00Z")), token);
        assertThat(result.statusCode()).isEqualTo(201);
        return tree(result).path("bookingCode").asText();
    }

    private Object blockRequest() {
        return Map.of("physicalRoomId", physicalRoomId101, "startAt", "2035-11-01T05:00:00Z",
                "endAt", "2035-11-03T03:00:00Z", "reason", "Maintenance");
    }

    @Test
    void paymentIsIdempotentAndCannotBeBypassedOrCancelledAfterSuccess() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD);
        String code = createSprintBooking(token);
        var unpaid = post("/api/v1/bookings/" + code + "/confirm", null, token);
        assertThat(unpaid.statusCode()).isEqualTo(409);
        assertThat(tree(unpaid).path("code").asText()).isEqualTo("PAYMENT_REQUIRED");
        var first = post("/api/v1/payments/demo", Map.of("bookingCode",code,"idempotencyKey","test-"+code,
                "amount",0,"userId",999999,"status","SUCCEEDED"),token);
        var again = pay(code, token);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(tree(first).path("paymentAttemptId").asLong()).isEqualTo(tree(again).path("paymentAttemptId").asLong());
        assertThat(tree(first).path("paymentStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(tree(first).path("requestedAmount").asDouble()).isEqualTo(2400000.0);
        assertThat(post("/api/v1/payments/demo",Map.of("bookingCode",code,"idempotencyKey","another"),token).statusCode()).isEqualTo(409);
        assertThat(post("/api/v1/bookings/"+code+"/cancel",null,token).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE status='SUCCEEDED'",Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void concurrentPaymentReplayCreatesOneAttempt(boolean sameKey) throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD), code = createSprintBooking(token);
        var a = CompletableFuture.supplyAsync(() -> { try { return pay(code,token); } catch(Exception e){throw new RuntimeException(e);} });
        var b = CompletableFuture.supplyAsync(() -> { try { return post("/api/v1/payments/demo",
                Map.of("bookingCode",code,"idempotencyKey",sameKey?"test-"+code:"different-key"),token);
                } catch(Exception e){throw new RuntimeException(e);} });
        var first=a.join();var second=b.join();
        if (sameKey) {
            assertThat(first.statusCode()).isEqualTo(200);assertThat(second.statusCode()).isEqualTo(200);
            assertThat(tree(first).path("paymentAttemptId").asLong()).isEqualTo(tree(second).path("paymentAttemptId").asLong());
        } else assertThat(List.of(first.statusCode(),second.statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts",Long.class)).isEqualTo(1);
    }

    @Test
    void paymentRejectsOtherCustomersAndExpiredHolds() throws Exception {
        String token = login("customer@hotel-pms.test", PASSWORD), code=createSprintBooking(token);
        var other=post("/api/v1/auth/register",Map.of("email",UUID.randomUUID()+"@hotel-pms.test","password",PASSWORD,"fullName","Other"),null);
        String otherToken=tree(other).path("accessToken").asText();
        assertThat(pay(code,otherToken).statusCode()).isEqualTo(403);
        assertThat(pay(code,null).statusCode()).isEqualTo(401);
        assertThat(pay(code,login("staff@hotel-pms.test",PASSWORD)).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE bookings SET hold_expires_at=? WHERE booking_code=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),code);
        assertThat(pay(code,token).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts",Long.class)).isZero();
        assertThat(tree(get("/api/v1/bookings/"+code,token)).path("status").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void blockRejectsLiveHoldAndConfirmedBooking() throws Exception {
        String token=login("customer@hotel-pms.test",PASSWORD),staff=login("staff@hotel-pms.test",PASSWORD);
        String code=createSprintBooking(token);
        assertThat(post("/api/v1/admin/room-blocks",blockRequest(),staff).statusCode()).isEqualTo(409);
        assertThat(pay(code,token).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/admin/room-blocks",blockRequest(),staff).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_blocks",Long.class)).isZero();
    }

    @Test
    void blockAndHoldCompeteForSamePhysicalRoom() throws Exception {
        jdbc.update("UPDATE physical_rooms SET is_active=false WHERE id=?",physicalRoomId102);
        String token=login("customer@hotel-pms.test",PASSWORD),staff=login("staff@hotel-pms.test",PASSWORD);
        var a=CompletableFuture.supplyAsync(()->{try{return post("/api/v1/bookings",new CreateBookingRequest(roomTypeId,(short)1,
            Instant.parse("2035-11-01T05:00:00Z"),Instant.parse("2035-11-03T03:00:00Z")),token);}catch(Exception e){throw new RuntimeException(e);}});
        var b=CompletableFuture.supplyAsync(()->{try{return post("/api/v1/admin/room-blocks",blockRequest(),staff);}catch(Exception e){throw new RuntimeException(e);}});
        assertThat(List.of(a.join().statusCode(),b.join().statusCode())).containsExactlyInAnyOrder(201,409);
    }

    @Test
    void releasedBlockRestoresOnlyItsRoomInAvailability() throws Exception {
        String staff=login("staff@hotel-pms.test",PASSWORD);
        var created=post("/api/v1/admin/room-blocks",blockRequest(),staff);
        assertThat(created.statusCode()).isEqualTo(201);
        String query="/api/v1/rooms/availability?startAt=2035-11-01T05:00:00Z&endAt=2035-11-03T03:00:00Z&guestCount=1";
        JsonNode available=tree(get(query,null));
        long count=0;for(var item:available){if(item.path("id").asLong()==roomTypeId)count=item.path("availableRoomCount").asLong();}
        assertThat(count).isEqualTo(1);
        var request=HttpRequest.newBuilder(uri("/api/v1/admin/room-blocks/"+tree(created).path("id").asLong()))
            .header("Authorization","Bearer "+staff).DELETE().build();
        assertThat(HTTP.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        available=tree(get(query,null));
        for(var item:available){if(item.path("id").asLong()==roomTypeId)count=item.path("availableRoomCount").asLong();}
        assertThat(count).isEqualTo(2);
    }

    @Test
    void adjacentHotelStaysReuseSameRoomAndExpiredHoldIsReclaimable() throws Exception {
        jdbc.update("UPDATE physical_rooms SET is_active=false WHERE id=?",physicalRoomId102);
        String token=login("customer@hotel-pms.test",PASSWORD), code=createSprintBooking(token);
        var next=post("/api/v1/bookings",new CreateBookingRequest(roomTypeId,(short)1,
            Instant.parse("2035-11-03T05:00:00Z"),Instant.parse("2035-11-04T03:00:00Z")),token);
        assertThat(next.statusCode()).isEqualTo(201);
        jdbc.update("UPDATE bookings SET hold_expires_at=? WHERE booking_code=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),code);
        assertThat(createSprintBooking(token)).isNotBlank();
        assertThat(tree(get("/api/v1/bookings/"+code,token)).path("status").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void staffCalendarAndLookupUseRealDataAndProtectCustomerPrivacy() throws Exception {
        String token=login("customer@hotel-pms.test",PASSWORD),staff=login("staff@hotel-pms.test",PASSWORD);
        var user=userRepository.findByEmailIgnoreCase("customer@hotel-pms.test").orElseThrow();
        jdbc.update("UPDATE customer_profiles SET phone_number='0901234567' WHERE user_id=?",user.getId());
        String code=createSprintBooking(token);
        var block=post("/api/v1/admin/room-blocks",Map.of("physicalRoomId",physicalRoomId102,"startAt","2035-11-01T05:00:00Z",
            "endAt","2035-11-03T03:00:00Z","reason","Maintenance"),staff);
        assertThat(block.statusCode()).isEqualTo(201);
        String path="/api/v1/staff/room-calendar?from=2035-11-01T00:00:00Z&to=2035-11-05T00:00:00Z";
        assertThat(get(path,null).statusCode()).isEqualTo(401);assertThat(get(path,token).statusCode()).isEqualTo(403);
        var calendar=get(path,staff);assertThat(calendar.statusCode()).isEqualTo(200);
        assertThat(calendar.body()).contains("BOOKING","BLOCK",code).doesNotContain("phone","email","customerId");
        var lookup=get("/api/v1/staff/bookings/search?phone=0901234567",staff);
        assertThat(lookup.statusCode()).isEqualTo(200);
        assertThat(lookup.body()).contains(code,"phoneMasked","emailMasked").doesNotContain("0901234567","customer@hotel-pms.test");
        assertThat(get("/api/v1/staff/bookings/search",staff).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/staff/bookings/search?phone=0901234567",token).statusCode()).isEqualTo(403);
    }

    @Test
    void paidConfirmationPdfContainsSnapshotAndRequiresOwnership() throws Exception {
        String token=login("customer@hotel-pms.test",PASSWORD), code=createSprintBooking(token);
        assertThat(get("/api/v1/bookings/"+code+"/confirmation.pdf",token).statusCode()).isEqualTo(409);
        assertThat(pay(code,token).statusCode()).isEqualTo(200);
        var request=HttpRequest.newBuilder(uri("/api/v1/bookings/"+code+"/confirmation.pdf"))
            .header("Authorization","Bearer "+token).GET().build();
        var response=HTTP.send(request,HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        try(var pdf=org.apache.pdfbox.Loader.loadPDF(response.body())){
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).contains(code,"2400000","DEMO","not a tax invoice");
        }
        assertThat(get("/api/v1/bookings/"+code+"/confirmation.pdf",null).statusCode()).isEqualTo(401);
    }

    @Test
    void quoteMatchesBookingSnapshotAcrossOffsetsAndPriceChanges() throws Exception {
        String token=login("customer@hotel-pms.test",PASSWORD);
        var quote=get("/api/v1/pricing/quote?roomTypeId="+roomTypeId+"&startAt=2035-11-01T05:00:00Z&endAt=2035-11-03T03:00:00Z&guestCount=1",token);
        assertThat(quote.statusCode()).isEqualTo(200);
        String code=createSprintBooking(token);
        var snapshot=tree(get("/api/v1/bookings/"+code,token));
        assertThat(snapshot.path("payableAmount").asDouble()).isEqualTo(tree(quote).path("payableAmount").asDouble());
        jdbc.update("UPDATE room_types SET base_price_per_night=9000000 WHERE id=?",roomTypeId);
        var paid=pay(code,token);assertThat(paid.statusCode()).isEqualTo(200);
        assertThat(tree(paid).path("requestedAmount").asDouble()).isEqualTo(2400000.0);
        assertThat(post("/api/v1/bookings/"+code+"/complete",null,login("staff@hotel-pms.test",PASSWORD)).statusCode()).isEqualTo(409);
    }

    @Test
    void failedDemoPaymentNeverConfirmsAndRetryRequiresANewKey() throws Exception {
        String token=login("customer@hotel-pms.test",PASSWORD),code=createSprintBooking(token);
        var body=Map.of("bookingCode",code,"idempotencyKey","failed-key","simulateFailure",true);
        var failed=post("/api/v1/payments/demo",body,token);
        assertThat(failed.statusCode()).isEqualTo(200);
        assertThat(tree(failed).path("paymentStatus").asText()).isEqualTo("FAILED");
        assertThat(tree(get("/api/v1/bookings/"+code,token)).path("status").asText()).isEqualTo("PENDING_PAYMENT");
        var replay=post("/api/v1/payments/demo",Map.of("bookingCode",code,"idempotencyKey","failed-key"),token);
        assertThat(tree(replay).path("paymentStatus").asText()).isEqualTo("FAILED");
        assertThat(pay(code,token).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE status='SUCCEEDED'",Long.class)).isEqualTo(1);
    }

    @Test
    void completionJobDoesNotCompleteUnpaidLegacyConfirmedBookings() throws Exception {
        String token=login("customer@hotel-pms.test",PASSWORD),code=createSprintBooking(token);
        jdbc.update("UPDATE bookings SET status='CONFIRMED',start_at=?,end_at=? WHERE booking_code=?",
            java.sql.Timestamp.from(Instant.now().minus(3,ChronoUnit.DAYS)),
            java.sql.Timestamp.from(Instant.now().minus(1,ChronoUnit.DAYS)),code);
        assertThat(bookingService.autoCompleteDepartedBookings(Instant.now())).isZero();
        assertThat(tree(get("/api/v1/bookings/"+code,token)).path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void paymentAndExpiryAtTheBoundaryNeverLeaveAPaidExpiredBooking() throws Exception {
        String token=login("customer@hotel-pms.test",PASSWORD),code=createSprintBooking(token);
        Instant deadline=Instant.now().plusSeconds(30);
        jdbc.update("UPDATE bookings SET hold_expires_at=? WHERE booking_code=?",java.sql.Timestamp.from(deadline),code);
        var payment=CompletableFuture.supplyAsync(()->{try{return pay(code,token);}catch(Exception e){throw new RuntimeException(e);}});
        var expiry=CompletableFuture.supplyAsync(()->bookingService.sweepExpiredHolds(deadline.plusMillis(1)));
        var result=payment.join();expiry.join();
        String status=tree(get("/api/v1/bookings/"+code,token)).path("status").asText();
        long successes=jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE status='SUCCEEDED'",Long.class);
        if(result.statusCode()==200){assertThat(status).isEqualTo("CONFIRMED");assertThat(successes).isEqualTo(1);}
        else {assertThat(result.statusCode()).isEqualTo(409);assertThat(status).isEqualTo("EXPIRED");assertThat(successes).isZero();}
    }

    @Test
    void releasingBlockDoesNotChangeAnUnrelatedExpiredHold() throws Exception {
        String token=login("customer@hotel-pms.test",PASSWORD),staff=login("staff@hotel-pms.test",PASSWORD);
        var created=post("/api/v1/admin/room-blocks",blockRequest(),staff);
        assertThat(created.statusCode()).isEqualTo(201);
        String code=createSprintBooking(token);
        jdbc.update("UPDATE bookings SET hold_expires_at=? WHERE booking_code=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),code);
        var request=HttpRequest.newBuilder(uri("/api/v1/admin/room-blocks/"+tree(created).path("id").asLong()))
            .header("Authorization","Bearer "+staff).DELETE().build();
        assertThat(HTTP.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(tree(get("/api/v1/bookings/"+code,token)).path("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    private HttpResponse<String> pay(String code, String token) throws Exception {
        return post("/api/v1/payments/demo", Map.of("bookingCode", code, "idempotencyKey", "test-" + code), token);
    }

    // Helper methods

    private String login(String email, String password) throws Exception {
        HttpResponse<String> res = post("/api/v1/auth/login", Map.of("email", email, "password", password), null);
        assertThat(res.statusCode()).isEqualTo(200);
        return tree(res).path("accessToken").asText();
    }

    private JsonNode tree(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> post(String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
