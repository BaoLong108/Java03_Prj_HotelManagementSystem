package com.hotelpms.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelpms.auth.entity.RoleCode;
import com.hotelpms.auth.entity.User;
import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.booking.dto.CreateBookingRequest;
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
        HttpResponse<String> confirmRes = post("/api/v1/bookings/" + bookingCode + "/confirm", null, token);
        assertThat(confirmRes.statusCode()).isEqualTo(200);
        JsonNode confirmBody = tree(confirmRes);
        assertThat(confirmBody.path("status").asText()).isEqualTo(BookingStatus.CONFIRMED.name());
        assertThat(confirmBody.path("confirmedAt").asText()).isNotBlank();

        // Idempotent confirm
        HttpResponse<String> confirmAgainRes = post("/api/v1/bookings/" + bookingCode + "/confirm", null, token);
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
        post("/api/v1/bookings/" + bookingCode + "/confirm", null, token);

        // Customer tries to complete -> 403 Forbidden
        HttpResponse<String> custCompleteRes = post("/api/v1/bookings/" + bookingCode + "/complete", null, token);
        assertThat(custCompleteRes.statusCode()).isEqualTo(403);

        // Staff completes -> 200 OK
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

        // Before sweep: room is still blocked by pending hold
        HttpResponse<String> blockedRes = post("/api/v1/bookings", new CreateBookingRequest(roomTypeId, (short) 1, startAt, endAt), token);
        assertThat(blockedRes.statusCode()).isEqualTo(409);

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
                return post("/api/v1/bookings/" + bookingCode + "/confirm", null, token);
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
