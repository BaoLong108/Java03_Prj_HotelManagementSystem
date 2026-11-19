package com.hotelpms.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.service.JwtService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"app.booking.scheduler.enabled=false", "app.loyalty.scheduler.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext
@Transactional
class RevenueAnalyticsIntegrationTest {
    private static final String PATH = "/api/v1/admin/analytics";
    private static EmbeddedPostgres postgres;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws IOException {
        String external = System.getenv("TEST_DB_URL");
        if (external == null || external.isBlank()) {
            postgres = EmbeddedPostgres.builder().setPort(0).start();
            registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
            registry.add("spring.datasource.username", () -> "postgres");
            registry.add("spring.datasource.password", () -> "postgres");
        } else {
            registry.add("spring.datasource.url", () -> external);
            registry.add("spring.datasource.username", () -> System.getenv("TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        }
        registry.add("app.jwt.secret", () -> "analytics-integration-signing-key-at-least-32-characters");
    }

    @AfterAll
    static void close() throws IOException { if (postgres != null) postgres.close(); }

    @Test
    void aggregatesSnapshotsRoomNightsAndLocalDateEventsWithoutDuplicatingAttempts() throws Exception {
        user(87001, "ADMIN");
        user(87002, "CUSTOMER");
        jdbc.update("INSERT INTO customer_profiles(user_id,tier_code) VALUES (87002,'VIP')");
        jdbc.update("INSERT INTO room_types(id,code,name,max_occupancy,base_price_per_night) VALUES (87001,'QA_REV','Revenue QA',2,9900000)");
        for (long room : new long[] {87001, 87002, 87003}) {
            jdbc.update("""
                    INSERT INTO physical_rooms(id,room_type_id,room_number,is_active,created_at)
                    VALUES (?,87001,?,?,'2024-01-01T00:00:00Z')
                    """, room, "REV-" + room, room != 87003);
        }
        jdbc.update("""
                INSERT INTO room_blocks(physical_room_id,start_at,end_at,reason,created_by_user_id,created_at)
                VALUES (87002,'2025-01-01T05:00Z','2025-01-02T03:00Z','QA',87001,'2024-12-25T00:00Z')
                """);
        jdbc.update("""
                INSERT INTO loyalty_vouchers(code,customer_id,points_cost,discount_amount,idempotency_key)
                VALUES ('REV401',87002,50,400000,'analytics-fixture'),('REV402',87002,50,400000,'analytics-zero')
                """);
        booking(87011, 87001, "CONFIRMED", "2024-12-31T05:00Z", "2025-01-02T03:00Z", "2024-12-30T17:00Z",
                2500000, 250000, "REV401", 400000, 1850000);
        booking(87012, 87002, "PENDING_PAYMENT", "2024-12-31T05:00Z", "2025-01-01T03:00Z", "2024-12-31T12:00Z",
                800000, 0, null, 0, 800000);
        booking(87013, 87003, "CANCELLED", "2025-01-01T05:00Z", "2025-01-02T03:00Z", "2024-12-31T12:00Z",
                800000, 0, null, 0, 800000);
        jdbc.update("UPDATE bookings SET cancelled_at='2024-12-31T17:00Z' WHERE id=87013");
        booking(87014, 87001, "COMPLETED", "2024-12-29T05:00Z", "2024-12-30T03:00Z", "2024-12-29T00:00Z",
                400000, 0, "REV402", 400000, 0);
        booking(87015, 87003, "COMPLETED", "2024-12-31T05:00Z", "2025-01-01T03:00Z", "2024-12-31T17:00Z",
                400000, 0, null, 0, 400000);
        payment(87011, "FAILED", "try", 1850000, null);
        payment(87011, "SUCCEEDED", "ok", 1850000, "2024-12-31T17:00Z");
        payment(87014, "SUCCEEDED", "zero", 0, "2024-12-29T03:00Z");
        payment(87015, "SUCCEEDED", "after", 400000, "2025-01-01T17:00Z");

        mvc.perform(get(PATH).param("from", "2024-12-31").param("to", "2025-01-01").param("roomTypeId", "87001")
                        .header("Authorization", auth(87001)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.reservations").value(4))
                .andExpect(jsonPath("$.totals.pendingPayments").value(1))
                .andExpect(jsonPath("$.totals.cancellationRate").value(25.0))
                .andExpect(jsonPath("$.totals.demoSettlements").value(1))
                .andExpect(jsonPath("$.totals.demoPaymentVolume").value(1850000))
                .andExpect(jsonPath("$.totals.confirmedBookingValue").value(1850000))
                .andExpect(jsonPath("$.totals.membershipDiscount").value(250000))
                .andExpect(jsonPath("$.totals.voucherDiscount").value(400000))
                .andExpect(jsonPath("$.inventory.estimatedAvailableNights").value(3))
                .andExpect(jsonPath("$.inventory.bookedNights").value(2))
                .andExpect(jsonPath("$.inventory.bookedUtilization").value(66.67))
                .andExpect(jsonPath("$.days[0].demoPaymentVolume").value(0))
                .andExpect(jsonPath("$.days[1].demoPaymentVolume").value(1850000))
                .andExpect(jsonPath("$.days[1].cancellations").value(1))
                .andExpect(jsonPath("$.roomTypes[0].id").value(87001))
                .andExpect(jsonPath("$.membershipDistribution.VIP").value(1))
                .andExpect(jsonPath("$.comparison.demoPaymentVolume").value(0));
        mvc.perform(get(PATH + "/export.csv").param("from", "2024-12-31").param("to", "2025-01-01")
                        .param("roomTypeId", "87001").header("Authorization", auth(87001)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2025-01-01,87001,1,1,1850000.00")));
        mvc.perform(get(PATH).param("from", "2024-12-31").param("to", "2025-01-01")
                        .header("Authorization", auth(87001)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.demoPaymentVolume").value(1850000))
                .andExpect(jsonPath("$.hotelZone").value("Asia/Ho_Chi_Minh"));
        mvc.perform(get(PATH).param("from", "2024-12-29").param("to", "2024-12-29").param("roomTypeId", "87001")
                        .header("Authorization", auth(87001)))
                .andExpect(jsonPath("$.totals.demoSettlements").value(1))
                .andExpect(jsonPath("$.totals.averageDemoBookingValue").value(0))
                .andExpect(jsonPath("$.totals.voucherDiscount").value(400000));
    }

    @Test
    void enforcesAdminAccessAndReportsUnknownDenominators() throws Exception {
        user(87001, "ADMIN");
        user(87002, "CUSTOMER");
        user(87003, "STAFF");
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (long id : new long[]{87002, 87003}) {
            mvc.perform(get(PATH).header("Authorization", auth(id))).andExpect(status().isForbidden());
            mvc.perform(get(PATH + "/export.csv").header("Authorization", auth(id))).andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH).param("from", "2025-01-01").param("to", "2025-01-01").param("roomTypeId", "999999")
                        .header("Authorization", auth(87001)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.reservations").value(0))
                .andExpect(jsonPath("$.totals.averageDemoBookingValue").isEmpty())
                .andExpect(jsonPath("$.totals.cancellationRate").isEmpty())
                .andExpect(jsonPath("$.inventory.bookedUtilization").isEmpty());
        mvc.perform(get(PATH).param("from", "2024-01-01").param("to", "2025-01-01")
                        .header("Authorization", auth(87001))).andExpect(status().isBadRequest());
    }

    private void booking(long id, long room, String status, String from, String to, String created,
            long base, long member, String voucher, long voucherDiscount, long payable) {
        jdbc.update("""
                INSERT INTO bookings(id,booking_code,customer_id,room_type_id,physical_room_id,guest_count,
                    start_at,end_at,hold_expires_at,status,created_at,base_amount,discount_amount,
                    voucher_code,voucher_discount_amount,payable_amount)
                VALUES (?, ?, 87002, 87001, ?, 2, ?::timestamptz, ?::timestamptz, ?::timestamptz,
                    ?, ?::timestamptz, ?, ?, ?, ?, ?)
                """, id, "REV-" + id, room, from, to, created, status, created, base, member, voucher, voucherDiscount, payable);
    }

    private void payment(long bookingId, String status, String key, long amount, String paidAt) {
        jdbc.update("""
                INSERT INTO payment_attempts(booking_id,provider,idempotency_key,status,requested_amount,succeeded_at)
                VALUES (?,'DEMO',?,?,?,?::timestamptz)
                """, bookingId, key, status, amount, paidAt);
    }

    private void user(long id, String role) {
        jdbc.update("INSERT INTO app_users(id,email,password_hash,full_name) VALUES (?,?,?,?)",
                id, "analytics-" + id + "@test.invalid", "unused", "Analytics fixture");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?", id, role);
    }

    private String auth(long id) { return "Bearer " + jwt.issue(users.findById(id).orElseThrow()).accessToken(); }
}
