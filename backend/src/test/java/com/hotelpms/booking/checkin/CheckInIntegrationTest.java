package com.hotelpms.booking.checkin;

import static com.hotelpms.booking.service.BookingService.HOTEL_ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.service.JwtService;
import com.hotelpms.booking.service.BookingLifecycleTransitions;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.booking.scheduler.enabled=false", "app.loyalty.scheduler.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = CheckInIntegrationTest.PostgresCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class CheckInIntegrationTest {
    private static final long CUSTOMER = 88001, OTHER = 88002, STAFF = 88003, ADMIN = 88004, BOOKING_ID = 88001;
    private static final String BOOKING = "/api/v1/bookings/QA-ARRIVAL";
    private static final String QUEUE = "/api/v1/staff/check-in-requests";
    private static EmbeddedPostgres postgres;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    @Autowired BookingLifecycleTransitions lifecycle;

    private LocalDate stay;
    private OffsetDateTime checkout;

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
        registry.add("app.jwt.secret", () -> "check-in-integration-signing-key-at-least-32-characters");
    }

    public static class PostgresCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext context) throws IOException { if (postgres != null) postgres.close(); }
    }

    @BeforeEach
    void setup() {
        stay = LocalDate.now(HOTEL_ZONE).plusDays(1);
        checkout = stay.plusDays(2).atTime(10, 0).atZone(HOTEL_ZONE).toOffsetDateTime();
        user(CUSTOMER, "CUSTOMER");
        user(OTHER, "CUSTOMER");
        user(STAFF, "STAFF");
        user(ADMIN, "ADMIN");
        jdbc.update("INSERT INTO customer_profiles(user_id,tier_code) VALUES (?,'VIP')", CUSTOMER);
        jdbc.update("INSERT INTO room_types(id,code,name,max_occupancy,base_price_per_night) VALUES (88001,'QA_ARRIVAL','Arrival QA',2,9900000)");
        jdbc.update("INSERT INTO physical_rooms(id,room_type_id,room_number) VALUES (88001,88001,'QA-ARRIVAL')");
        jdbc.update("""
                INSERT INTO bookings(id,booking_code,customer_id,room_type_id,physical_room_id,guest_count,
                    start_at,end_at,hold_expires_at,base_amount,discount_amount,payable_amount)
                VALUES (?,'QA-ARRIVAL',?,88001,88001,2,?,?,now() + interval '15 minutes',2500000,250000,2250000)
                """, BOOKING_ID, CUSTOMER, stay.atTime(12, 0).atZone(HOTEL_ZONE).toOffsetDateTime(), checkout);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM check_in_requests WHERE booking_id=?", BOOKING_ID);
        jdbc.update("DELETE FROM loyalty_awards WHERE booking_id=?", BOOKING_ID);
        jdbc.update("DELETE FROM payment_attempts WHERE booking_id=?", BOOKING_ID);
        jdbc.update("DELETE FROM bookings WHERE id=?", BOOKING_ID);
        jdbc.update("DELETE FROM physical_rooms WHERE id=88001");
        jdbc.update("DELETE FROM room_types WHERE id=88001");
        jdbc.update("DELETE FROM customer_profiles WHERE user_id=?", CUSTOMER);
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (?,?,?,?)", CUSTOMER, OTHER, STAFF, ADMIN);
        jdbc.update("DELETE FROM app_users WHERE id IN (?,?,?,?)", CUSTOMER, OTHER, STAFF, ADMIN);
    }

    @Test
    void timelineProjectsPendingAndPaidSnapshotsAndOnlyOwnerCanView() throws Exception {
        mvc.perform(get(BOOKING + "/timeline")).andExpect(status().isUnauthorized());
        mvc.perform(get(BOOKING + "/timeline").header("Authorization", auth(OTHER))).andExpect(status().isNotFound());
        mvc.perform(get(BOOKING + "/timeline").header("Authorization", auth(STAFF))).andExpect(status().isForbidden());
        mvc.perform(get(BOOKING + "/timeline").header("Authorization", auth(CUSTOMER)))
                .andExpect(jsonPath("$.milestones[0].stage").value("CREATED"))
                .andExpect(jsonPath("$.milestones[1].stage").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.payment").isEmpty()).andExpect(jsonPath("$.confirmationPdfAvailable").value(false));
        pay();
        mvc.perform(get(BOOKING + "/timeline").header("Authorization", auth(CUSTOMER)))
                .andExpect(jsonPath("$.booking.baseAmount").value(2500000))
                .andExpect(jsonPath("$.booking.discountAmount").value(250000))
                .andExpect(jsonPath("$.booking.payableAmount").value(2250000))
                .andExpect(jsonPath("$.room.name").value("Arrival QA"))
                .andExpect(jsonPath("$.nights").value(2))
                .andExpect(jsonPath("$.payment.amount").value(2250000))
                .andExpect(jsonPath("$.milestones[1].stage").value("PAYMENT_SUCCEEDED"))
                .andExpect(jsonPath("$.milestones[2].stage").value("CONFIRMED"))
                .andExpect(jsonPath("$.milestones.length()").value(3))
                .andExpect(jsonPath("$.confirmationPdfAvailable").value(true));
    }

    @Test
    void approvalPersistsAuditWithoutChangingBookingOrPhysicalAssignment() throws Exception {
        pay();
        long id = create();
        mvc.perform(get(QUEUE).param("status", "PENDING").param("code", "qa-arr").header("Authorization", auth(STAFF)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].request.id").value(id))
                .andExpect(jsonPath("$.items[0].customerId").doesNotExist());
        assertThat(code(review(id, CUSTOMER, "APPROVED", ""))).isEqualTo(403);
        assertThat(code(review(id, STAFF, "APPROVED", ""))).isEqualTo(200);
        assertThat(code(review(id, ADMIN, "REJECTED", "Late"))).isEqualTo(409);
        assertThat(code(submit(CUSTOMER, stay.atTime(13, 0).toString()))).isEqualTo(409);
        mvc.perform(get(BOOKING + "/timeline").header("Authorization", auth(CUSTOMER)))
                .andExpect(jsonPath("$.booking.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.requests[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.requests[0].reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.milestones[4].stage").value("CHECK_IN_APPROVED"))
                .andExpect(jsonPath("$.milestones.length()").value(5));
        assertThat(jdbc.queryForObject("SELECT physical_room_id FROM bookings WHERE id=?", Long.class, BOOKING_ID)).isEqualTo(88001);
        assertThat(jdbc.queryForObject("SELECT reviewed_by FROM check_in_requests WHERE id=?", Long.class, id)).isEqualTo(STAFF);
    }

    @Test
    void rejectsInvalidSubmissionsAndUsesServerWindow() throws Exception {
        var arrival = stay.atTime(13, 0).toString();
        assertThat(code(post(BOOKING + "/check-in-requests"))).isEqualTo(401);
        assertThat(code(submit(OTHER, arrival))).isEqualTo(404);
        assertThat(code(submit(CUSTOMER, arrival))).isEqualTo(409);
        pay();
        assertThat(code(submit(CUSTOMER, stay.atTime(11, 0).toString()))).isEqualTo(400);
        assertThat(code(submit(CUSTOMER, checkout.toLocalDateTime().toString()))).isEqualTo(400);
        jdbc.update("UPDATE bookings SET start_at=start_at+interval '7 days',end_at=end_at+interval '7 days' WHERE id=?", BOOKING_ID);
        assertThat(code(submit(CUSTOMER, stay.plusDays(7).atTime(13, 0).toString()))).isEqualTo(409);
    }

    @Test
    void showsCancelledAndExpiredBookingsWithoutOfferingCheckIn() throws Exception {
        mvc.perform(post(BOOKING + "/cancel").header("Authorization", auth(CUSTOMER))).andExpect(status().isOk());
        assertTerminal("CANCELLED");
        assertThat(code(submit(CUSTOMER, stay.atTime(13, 0).toString()))).isEqualTo(409);
        jdbc.update("UPDATE bookings SET status='PENDING_PAYMENT',cancelled_at=NULL,hold_expires_at=now()-interval '1 second' WHERE id=?", BOOKING_ID);
        assertThat(lifecycle.expire(BOOKING_ID, Instant.now())).isTrue();
        assertTerminal("EXPIRED");
        assertThat(code(submit(CUSTOMER, stay.atTime(13, 0).toString()))).isEqualTo(409);
    }

    @Test
    void rejectionRequiresReasonAndAllowsAnotherRequestWhichOwnerCanWithdraw() throws Exception {
        pay();
        long first = create();
        assertThat(code(review(first, ADMIN, "REJECTED", "   "))).isEqualTo(400);
        mvc.perform(review(first, ADMIN, "REJECTED", "  Không thể đến sớm  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.request.rejectionReason").value("Không thể đến sớm"));
        long second = create();
        assertThat(code(cancel(second, OTHER))).isEqualTo(404);
        assertThat(code(cancel(second, CUSTOMER))).isEqualTo(200);
        assertThat(code(review(second, ADMIN, "APPROVED", ""))).isEqualTo(409);
        mvc.perform(get(QUEUE).param("status", "CANCELLED").header("Authorization", auth(ADMIN)))
                .andExpect(jsonPath("$.items[0].request.id").value(second));
    }

    @Test
    void locksDuplicateSubmissionsAndCompetingStaffDecisions() throws Exception {
        pay();
        var arrival = stay.atTime(13, 0).toString();
        assertRace(submit(CUSTOMER, arrival), submit(CUSTOMER, arrival), 201, 409);
        long id = jdbc.queryForObject("SELECT id FROM check_in_requests WHERE booking_id=?", Long.class, BOOKING_ID);
        assertRace(review(id, STAFF, "APPROVED", ""), review(id, ADMIN, "REJECTED", "Không thể duyệt"), 200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in_requests WHERE booking_id=?", Integer.class, BOOKING_ID)).isEqualTo(1);
    }

    @Test
    void serializesOwnerWithdrawalAndStaffApproval() throws Exception {
        pay();
        long id = create();
        assertRace(cancel(id, CUSTOMER), review(id, STAFF, "APPROVED", ""), 200, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM check_in_requests WHERE id=?", String.class, id)).isIn("APPROVED", "CANCELLED");
    }

    @Test
    void pendingRequestsInvalidateAtCheckoutAndCompletionDoesNotDependOnThem() throws Exception {
        pay();
        long id = create();
        assertThat(lifecycle.complete(BOOKING_ID, checkout.toInstant().minusMillis(1))).isFalse();
        assertThat(lifecycle.complete(BOOKING_ID, checkout.toInstant())).isTrue();
        assertThat(lifecycle.complete(BOOKING_ID, checkout.toInstant().plusSeconds(1))).isFalse();
        mvc.perform(get(BOOKING + "/timeline").header("Authorization", auth(CUSTOMER)))
                .andExpect(jsonPath("$.booking.status").value("COMPLETED"))
                .andExpect(jsonPath("$.requests[0].status").value("INVALIDATED"))
                .andExpect(jsonPath("$.milestones[4].stage").value("COMPLETED"));
        assertThat(code(review(id, STAFF, "APPROVED", ""))).isEqualTo(409);
        assertThat(code(submit(CUSTOMER, stay.atTime(13, 0).toString()))).isEqualTo(409);
    }

    @Test
    void cancellationInvalidatesPendingRequestWithoutRewritingHistory() throws Exception {
        pay();
        long id = create();
        // Existing customer cancellation applies to unpaid holds only. Cover an externally cancelled booking defensively.
        jdbc.update("UPDATE bookings SET status='CANCELLED', cancelled_at=now() WHERE id=?", BOOKING_ID);
        assertThat(code(review(id, STAFF, "APPROVED", ""))).isEqualTo(409);
        mvc.perform(get(QUEUE).param("status", "INVALIDATED").header("Authorization", auth(STAFF)))
                .andExpect(jsonPath("$.items[0].request.id").value(id));
        assertThat(jdbc.queryForObject("SELECT status FROM check_in_requests WHERE id=?", String.class, id)).isEqualTo("PENDING");
    }

    private void assertTerminal(String state) throws Exception {
        mvc.perform(get(BOOKING + "/timeline").header("Authorization", auth(CUSTOMER)))
                .andExpect(jsonPath("$.milestones[1].stage").value(state))
                .andExpect(jsonPath("$.eligibility.allowed").value(false));
    }

    private void assertRace(RequestBuilder first, RequestBuilder second, int success, int conflict) throws Exception {
        var left = CompletableFuture.supplyAsync(() -> code(first));
        var right = CompletableFuture.supplyAsync(() -> code(second));
        assertThat(new int[]{left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)}).containsExactlyInAnyOrder(success, conflict);
    }

    private long create() throws Exception {
        var response = mvc.perform(submit(CUSTOMER, stay.atTime(13, 0).toString())).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(201);
        return json.readTree(response.getContentAsString()).get("id").asLong();
    }

    private void pay() throws Exception {
        mvc.perform(post("/api/v1/payments/demo").header("Authorization", auth(CUSTOMER)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("bookingCode", "QA-ARRIVAL", "idempotencyKey", "arrivals-qa"))))
                .andExpect(status().isOk());
    }

    private RequestBuilder submit(long actor, String arrival) throws Exception {
        return post(BOOKING + "/check-in-requests").header("Authorization", auth(actor)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("estimatedArrival", arrival, "arrivalNote", "Đến cùng gia đình")));
    }

    private RequestBuilder review(long id, long actor, String decision, String reason) throws Exception {
        return post(QUEUE + "/" + id + "/review").header("Authorization", auth(actor)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("decision", decision, "rejectionReason", reason)));
    }

    private RequestBuilder cancel(long id, long actor) { return post(BOOKING + "/check-in-requests/" + id + "/cancel").header("Authorization", auth(actor)); }

    private int code(RequestBuilder request) {
        try { return mvc.perform(request).andReturn().getResponse().getStatus(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void user(long id, String role) {
        jdbc.update("INSERT INTO app_users(id,email,password_hash,full_name) VALUES (?,?,?,?)",
                id, "arrival-" + id + "@test.invalid", "unused", "Arrival fixture");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?", id, role);
    }

    private String auth(long id) { return "Bearer " + jwt.issue(users.findById(id).orElseThrow()).accessToken(); }
}
