package com.hotelpms.report;

import static org.assertj.core.api.Assertions.assertThat;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.demo-seed.enabled=true", "app.demo-seed.password=TestDemo123!", "app.booking.scheduler.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ReportIntegrationTest.Cleanup.class, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class ReportIntegrationTest {
    static EmbeddedPostgres postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) throws IOException {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || url.isBlank()) {
            postgres = EmbeddedPostgres.builder().setPort(0).start();
            p.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
            p.add("spring.datasource.username", () -> "postgres"); p.add("spring.datasource.password", () -> "postgres");
        } else {
            p.add("spring.datasource.url", () -> url); p.add("spring.datasource.username", () -> System.getenv("TEST_DB_USER"));
            p.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        }
        p.add("app.jwt.secret", () -> "report-integration-test-secret-at-least-thirty-two-characters");
    }
    public static class Cleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext context) throws IOException { if (postgres != null) postgres.close(); }
    }
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.header("Content-Type", "application/json");
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String method, String path, String token, Object body) throws Exception {
        var response = request(method, path, token, body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200, 299);
        return json.readTree(response.body());
    }
    String login(String role) throws Exception { return ok("POST", "/api/v1/auth/login", null, Map.of("email", role + "@hotel-pms.test", "password", "TestDemo123!")).path("accessToken").asText(); }

    @Test void dailyUsesHotelDayHalfOpenBoundsFiltersAndMaskedContacts() throws Exception {
        String admin = login("admin"), staff = login("staff");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String customer = ok("POST", "/api/v1/auth/register", null, Map.of("email", "report" + suffix + "@example.test", "password", "ReportTest123!", "fullName", "Private Customer Name", "phoneNumber", "0901234502")).path("accessToken").asText();
        long type = ok("POST", "/api/v1/admin/rooms/types", admin, Map.of("code", "R" + suffix, "name", "Report test", "maxOccupancy", 2, "basePricePerNight", 100000)).path("id").asLong();
        ok("POST", "/api/v1/admin/rooms/physical", admin, Map.of("roomTypeId", type, "roomNumber", "R" + suffix, "floorNumber", 1));
        String code = ok("POST", "/api/v1/bookings", customer, Map.of("roomTypeId", type, "guestCount", 2, "startAt", "2032-06-01T17:00:00Z", "endAt", "2032-06-03T17:00:00Z")).path("bookingCode").asText();
        String base = "/api/v1/staff/bookings/daily?roomTypeId=" + type;
        assertThat(ok("GET", base + "&date=2032-06-01", staff, null).path("items").size()).isZero();
        JsonNode found = ok("GET", base + "&date=2032-06-02&status=PENDING_PAYMENT", staff, null);
        assertThat(found.path("items").size()).isEqualTo(1);
        assertThat(found.path("items").get(0).path("bookingCode").asText()).isEqualTo(code);
        assertThat(found.path("items").get(0).path("customer").path("phoneMasked").asText()).isEqualTo("*******502");
        assertThat(found.toString()).doesNotContain("0901234502", "Private Customer", "customerId", "report" + suffix + "@");
        assertThat(found.path("items").get(0).path("payableAmount").isString()).isTrue();
        assertThat(ok("GET", base + "&date=2032-06-03", staff, null).path("items").size()).isEqualTo(1);
        assertThat(ok("GET", base + "&date=2032-06-04", staff, null).path("items").size()).isZero();
        assertThat(ok("GET", base + "&date=2032-06-02&status=CANCELLED", admin, null).path("items").size()).isZero();
        assertThat(request("GET", base + "&date=2032-06-02", customer, null).statusCode()).isEqualTo(403);
        assertThat(request("GET", base + "&date=2032-06-02", null, null).statusCode()).isEqualTo(401);
    }
    @Test void invalidFiltersAre400WithStableCode() throws Exception {
        String staff = login("staff");
        for (String query : new String[]{"", "?date=2026-02-30", "?date=2026-11-02&roomTypeId=-1", "?date=2026-11-02&roomTypeId=abc", "?date=2026-11-02&status=UNKNOWN"}) {
            var response = request("GET", "/api/v1/staff/bookings/daily" + query, staff, null);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_FILTER");
        }
    }

    @Test void summaryHistoryAndCsvUseRealApiDataAndEnforceRoles() throws Exception {
        String admin = login("admin"), staff = login("staff"), customer = login("customer");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String range = "?from=2081-06-02&to=2081-06-03";
        var before = ok("GET", "/api/v1/staff/reports/summary" + range, staff, null);
        long type = ok("POST", "/api/v1/admin/rooms/types", admin, Map.of("code", "S" + suffix, "name", "=Phòng, \"Việt\"", "maxOccupancy", 2, "basePricePerNight", 100000)).path("id").asLong();
        long room = ok("POST", "/api/v1/admin/rooms/physical", admin, Map.of("roomTypeId", type, "roomNumber", "S" + suffix, "floorNumber", 1)).path("id").asLong();
        long blockedRoom = ok("POST", "/api/v1/admin/rooms/physical", admin, Map.of("roomTypeId", type, "roomNumber", "B" + suffix, "floorNumber", 1)).path("id").asLong();
        long inactive = ok("POST", "/api/v1/admin/rooms/physical", admin, Map.of("roomTypeId", type, "roomNumber", "I" + suffix, "floorNumber", 1)).path("id").asLong();
        assertThat(request("DELETE", "/api/v1/admin/rooms/physical/" + inactive, admin, null).statusCode()).isEqualTo(204);
        String code = ok("POST", "/api/v1/bookings", customer, Map.of("roomTypeId", type, "guestCount", 1,
                "startAt", "2081-06-01T12:00:00+07:00", "endAt", "2081-06-03T10:00:00+07:00")).path("bookingCode").asText();
        var booking = ok("GET", "/api/v1/bookings/" + code, customer, null);
        long assigned = booking.path("physicalRoomId").asLong();
        long freeRoom = assigned == blockedRoom ? room : blockedRoom;
        var today = java.time.LocalDate.now(ReportFilters.ZONE);
        String todayRange = "?from=" + today + "&to=" + today.plusDays(1);
        var revenueBefore = new java.math.BigDecimal(ok("GET", "/api/v1/staff/reports/summary" + todayRange, staff, null).path("revenue").asText());
        ok("POST", "/api/v1/payments/demo", customer, Map.of("bookingCode", code, "idempotencyKey", "report-" + suffix));
        var revenueAfter = new java.math.BigDecimal(ok("GET", "/api/v1/staff/reports/summary" + todayRange, staff, null).path("revenue").asText());
        assertThat(revenueAfter.subtract(revenueBefore)).isEqualByComparingTo(booking.path("payableAmount").asText());
        long block = ok("POST", "/api/v1/admin/room-blocks", staff, Map.of("physicalRoomId", freeRoom,
                "startAt", "2081-06-02T12:00:00+07:00", "endAt", "2081-06-03T10:00:00+07:00", "reason", "@Bảo trì, \"điện\"\nTầng 1")).path("id").asLong();
        var summary = ok("GET", "/api/v1/staff/reports/summary" + range, staff, null);
        assertThat(summary.path("availableRoomNights").asLong()).isEqualTo(before.path("availableRoomNights").asLong() + 1);
        assertThat(summary.path("soldRoomNights").asLong()).isEqualTo(before.path("soldRoomNights").asLong() + 1);
        assertThat(summary.path("daily").size()).isEqualTo(1);
        assertThat(summary.path("revenue").asText()).isEqualTo("0");
        String historyPath = "/api/v1/staff/reports/room-block-history" + range + "&roomId=" + freeRoom;
        var history = ok("GET", historyPath, staff, null);
        assertThat(history.get(0).path("active").asBoolean()).isTrue();
        assertThat(history.get(0).path("createdByName").asText()).isNotBlank();
        assertThat(history.toString()).doesNotContain("email", "phoneNumber", "password");
        ok("DELETE", "/api/v1/admin/room-blocks/" + block, staff, null);
        var released = ok("GET", historyPath, staff, null).get(0);
        assertThat(released.path("active").asBoolean()).isFalse();
        assertThat(released.path("releasedAt").isNull()).isFalse();
        assertThat(ok("GET", "/api/v1/staff/reports/summary" + range, staff, null).path("availableRoomNights").asLong())
                .isEqualTo(before.path("availableRoomNights").asLong() + 2);
        var csv = request("GET", "/api/v1/admin/reports/bookings.csv" + range, admin, null);
        assertThat(csv.statusCode()).isEqualTo(200); assertThat(csv.body()).startsWith("\uFEFF");
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).contains("text/csv", "UTF-8");
        assertThat(csv.body()).contains(code, "ROOM_BLOCKS", "'@Bảo trì", "'=Phòng", "Đã mở", "02/06/2081 12:00");
        assertThat(request("GET", "/api/v1/admin/reports/bookings.csv" + range, staff, null).statusCode()).isEqualTo(403);
        for (String path : new String[]{"/api/v1/staff/reports/summary", "/api/v1/staff/reports/room-block-history", "/api/v1/admin/reports/bookings.csv"}) {
            assertThat(request("GET", path + range, customer, null).statusCode()).isEqualTo(403);
            assertThat(request("GET", path + "?from=2081-01-01&to=2082-01-01", admin, null).statusCode()).isEqualTo(400);
        }
    }
}
