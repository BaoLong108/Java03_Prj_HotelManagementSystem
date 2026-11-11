package com.hotelpms.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.service.JwtService;
import com.hotelpms.payment.mail.ConfirmationMailDelivery;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "app.booking.scheduler.enabled=false", "app.loyalty.scheduler.enabled=false",
        "app.vnpay.enabled=true", "app.vnpay.tmn-code=QA2026",
        "app.vnpay.hash-secret=public-test-only-not-a-merchant-secret",
        "app.confirmation-mail.enabled=true", "app.confirmation-mail.interval-ms=3600000"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = Sprint34IntegrationTest.PostgresCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class Sprint34IntegrationTest {
    private static final long CUSTOMER = 89001, ADMIN = 89002, STAFF = 89003, OTHER = 89004;
    private static final String CODE = "BK-S34QA";
    private static final String SECRET = "public-test-only-not-a-merchant-secret";
    private static EmbeddedPostgres postgres;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired ConfirmationMailDelivery mailDelivery;
    @MockitoBean JavaMailSender sender;
    private Instant from, to;

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
        registry.add("app.jwt.secret", () -> "sprint34-integration-signing-key-at-least-32-characters");
    }

    public static class PostgresCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext context) throws IOException { if (postgres != null) postgres.close(); }
    }

    @BeforeEach
    void setup() {
        from = Instant.now().plusSeconds(86400);
        to = from.plusSeconds(2 * 86400);
        for (long id : new long[]{CUSTOMER, ADMIN, STAFF, OTHER}) {
            jdbc.update("INSERT INTO app_users(id,email,password_hash,full_name) VALUES (?,?,?,?)", id,
                    "s34-" + id + "@test.invalid", "unused", "Sprint QA");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?", id,
                    id == ADMIN ? "ADMIN" : id == STAFF ? "STAFF" : "CUSTOMER");
        }
        jdbc.update("INSERT INTO customer_profiles(user_id) VALUES (?)", CUSTOMER);
        jdbc.update("INSERT INTO room_types(id,code,name,max_occupancy,base_price_per_night) VALUES (89001,'S34_QA','QA =room',2,800000)");
        jdbc.update("INSERT INTO physical_rooms(id,room_type_id,room_number) VALUES (89001,89001,'S34-1')");
        jdbc.update("""
                INSERT INTO bookings(id,booking_code,customer_id,room_type_id,physical_room_id,guest_count,
                    start_at,end_at,hold_expires_at,base_amount,discount_amount,payable_amount)
                VALUES (89001,?,?,89001,89001,2,?,?,now()+interval '15 minutes',2500000,250000,2250000)
                """, CODE, CUSTOMER, from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC));
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM payment_attempts WHERE booking_id=89001");
        jdbc.update("DELETE FROM bookings WHERE customer_id=?", CUSTOMER);
        jdbc.update("DELETE FROM room_blocks WHERE physical_room_id=89001");
        jdbc.update("DELETE FROM physical_rooms WHERE id=89001");
        jdbc.update("DELETE FROM room_types WHERE id=89001");
        jdbc.update("DELETE FROM customer_profiles WHERE user_id=?", CUSTOMER);
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (?,?,?,?)", CUSTOMER, ADMIN, STAFF, OTHER);
        jdbc.update("DELETE FROM app_users WHERE id IN (?,?,?,?)", CUSTOMER, ADMIN, STAFF, OTHER);
    }

    @Test
    void checkoutBindsSnapshotValidatesChecksumAndIsIdempotent() throws Exception {
        String url = checkout("qa-vnpay-key");
        assertThat(checkout("qa-vnpay-key")).isEqualTo(url);
        assertThat(checkout("qa-another-key")).isEqualTo(url);
        mvc.perform(post("/api/v1/payments/demo").header("Authorization", auth(CUSTOMER)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("bookingCode", CODE, "idempotencyKey", "parallel-demo"))))
                .andExpect(status().isConflict());
        var fields = query(url);
        assertThat(fields.get("vnp_Amount")).isEqualTo("225000000");
        assertThat(fields.get("vnp_Version")).isEqualTo("2.1.0");
        assertThat(VnpaySignature.valid(fields, SECRET)).isTrue();
        var callback = callback(fields);
        String signature = callback.get("vnp_SecureHash");
        callback.put("vnp_Amount", "1");
        ipn(callback, "97");
        callback.put("vnp_SecureHash", VnpaySignature.sign(callback, SECRET));
        ipn(callback, "04");
        callback.put("vnp_Amount", fields.get("vnp_Amount"));
        callback.put("vnp_SecureHash", signature);
        var left = CompletableFuture.supplyAsync(() -> ipnCode(callback));
        var right = CompletableFuture.supplyAsync(() -> ipnCode(callback));
        assertThat(List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("00", "02");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=89001", String.class)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts WHERE booking_id=89001 AND status='SUCCEEDED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_confirmation_mail WHERE booking_id=89001", Integer.class)).isEqualTo(1);
    }

    @Test
    void expiredOrChangedBookingIsRecordedForReviewWithoutConfirming() throws Exception {
        var fields = callback(query(checkout("late-vnpay-key")));
        jdbc.update("UPDATE bookings SET hold_expires_at=now()-interval '1 second' WHERE id=89001");
        ipn(fields, "00");
        ipn(fields, "02");
        assertThat(jdbc.queryForObject("SELECT outcome FROM vnpay_checkouts WHERE reference=?", String.class, fields.get("vnp_TxnRef"))).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=89001", String.class)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts WHERE booking_id=89001 AND status='SUCCEEDED'", Integer.class)).isZero();
    }

    @Test
    void rejectsWrongMerchantAndOwnershipAndFailureDoesNotConfirm() throws Exception {
        mvc.perform(post("/api/v1/payments/vnpay/checkout").contentType("application/json").content(draft("qa-key"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/vnpay/checkout").header("Authorization", auth(STAFF)).contentType("application/json").content(draft("qa-key"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/payments/vnpay/checkout").header("Authorization", auth(OTHER)).contentType("application/json").content(draft("qa-key"))).andExpect(status().isNotFound());
        var fields = callback(query(checkout("failed-vnpay-key")));
        fields.put("vnp_TmnCode", "WRONG");
        fields.put("vnp_SecureHash", VnpaySignature.sign(fields, SECRET));
        ipn(fields, "97");
        fields.put("vnp_TmnCode", "QA2026"); fields.put("vnp_ResponseCode", "24");
        fields.put("vnp_TransactionStatus", "02"); fields.put("vnp_SecureHash", VnpaySignature.sign(fields, SECRET));
        ipn(fields, "00");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=89001", String.class)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempts WHERE booking_id=89001", String.class)).isEqualTo("FAILED");
    }

    @Test
    void confirmationMailFailureRetriesAndDoesNotRollbackPaidBooking() throws Exception {
        mvc.perform(post("/api/v1/payments/demo").header("Authorization", auth(CUSTOMER)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("bookingCode", CODE, "idempotencyKey", "mail-qa")))).andExpect(status().isOk());
        doThrow(new MailSendException("QA SMTP unavailable")).when(sender).send(any(MimeMessage.class));
        assertThat(mailDelivery.sendNext()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM booking_confirmation_mail WHERE booking_id=89001", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=89001", String.class)).isEqualTo("CONFIRMED");
        jdbc.update("UPDATE booking_confirmation_mail SET next_attempt_at=now() WHERE booking_id=89001");
        doNothing().when(sender).send(any(MimeMessage.class));
        assertThat(mailDelivery.sendNext()).isTrue();
        assertThat(mailDelivery.sendNext()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM booking_confirmation_mail WHERE booking_id=89001", String.class)).isEqualTo("SENT");
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test
    void adminExportHasBookingAndBlockAuditButDeniesStaffAndCustomer() throws Exception {
        jdbc.update("""
                INSERT INTO room_blocks(physical_room_id,start_at,end_at,reason,created_by_user_id)
                VALUES (89001,?,?,?,?)
                """, to.atOffset(ZoneOffset.UTC), to.plusSeconds(3600).atOffset(ZoneOffset.UTC), "=SUM(1,2)", STAFF);
        String fromDay = from.atZone(com.hotelpms.booking.service.BookingService.HOTEL_ZONE).toLocalDate().toString();
        String toDay = to.atZone(com.hotelpms.booking.service.BookingService.HOTEL_ZONE).toLocalDate().toString();
        for (long role : new long[]{STAFF, CUSTOMER}) {
            mvc.perform(get("/api/v1/admin/bookings/export.csv").param("from", fromDay).param("to", toDay)
                    .header("Authorization", auth(role))).andExpect(status().isForbidden());
        }
        var csv = mvc.perform(get("/api/v1/admin/bookings/export.csv").param("from", fromDay).param("to", toDay)
                        .param("roomTypeId", "89001").header("Authorization", auth(ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"BOOKING\"", CODE, "\"BLOCK\"", "'=SUM(1,2)", "2250000.00").doesNotContain("@test.invalid");
        mvc.perform(get("/api/v1/admin/room-blocks").param("roomId", "89001").param("history", "true")
                .header("Authorization", auth(STAFF))).andExpect(status().isOk()).andExpect(jsonPath("$[0].createdByUserId").value(STAFF));
    }

    @Test
    void adminEditsAndHidesRoomsWithoutDiscardingBookings() throws Exception {
        mvc.perform(put("/api/v1/admin/rooms/physical/89001").header("Authorization", auth(ADMIN)).contentType("application/json")
                .content("{\"roomNumber\":\"S34-EDIT\",\"floorNumber\":3}")).andExpect(status().isOk()).andExpect(jsonPath("$.roomNumber").value("S34-EDIT"));
        mvc.perform(delete("/api/v1/admin/rooms/physical/89001").header("Authorization", auth(ADMIN))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/bookings/" + CODE + "/cancel").header("Authorization", auth(CUSTOMER))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/rooms/physical/89001").header("Authorization", auth(ADMIN))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=89001", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT is_active FROM physical_rooms WHERE id=89001", Boolean.class)).isFalse();
    }

    @Test
    void hidingAndCreatingABookingSerializeOnTheSameRoom() throws Exception {
        mvc.perform(post("/api/v1/bookings/" + CODE + "/cancel").header("Authorization", auth(CUSTOMER))).andExpect(status().isOk());
        var create = post("/api/v1/bookings").header("Authorization", auth(CUSTOMER)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("roomTypeId", 89001, "guestCount", 2, "startAt", from, "endAt", to)));
        var hide = delete("/api/v1/admin/rooms/physical/89001").header("Authorization", auth(ADMIN));
        var booking = CompletableFuture.supplyAsync(() -> httpStatus(create));
        var hidden = CompletableFuture.supplyAsync(() -> httpStatus(hide));
        var results = List.of(booking.get(15, TimeUnit.SECONDS), hidden.get(15, TimeUnit.SECONDS));
        assertThat(results).satisfiesAnyOf(
                values -> assertThat(values).containsExactly(201, 409),
                values -> assertThat(values).containsExactly(409, 204));
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_active FROM physical_rooms WHERE id=89001", Boolean.class));
        int holds = jdbc.queryForObject("SELECT count(*) FROM bookings WHERE physical_room_id=89001 AND status='PENDING_PAYMENT'", Integer.class);
        assertThat(active || holds == 0).isTrue();
    }

    private String checkout(String key) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/payments/vnpay/checkout").header("Authorization", auth(CUSTOMER))
                .contentType("application/json").content(draft(key))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("paymentUrl").asText();
    }
    private String draft(String key) { return json.writeValueAsString(Map.of("bookingCode", CODE, "idempotencyKey", key)); }
    private Map<String, String> query(String url) {
        var result = new TreeMap<String, String>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            var parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    private Map<String, String> callback(Map<String, String> query) {
        var fields = new TreeMap<String, String>();
        for (String name : new String[]{"vnp_TmnCode", "vnp_Amount", "vnp_TxnRef", "vnp_OrderInfo"}) fields.put(name, query.get(name));
        fields.put("vnp_ResponseCode", "00"); fields.put("vnp_TransactionStatus", "00"); fields.put("vnp_TransactionNo", "123456789");
        fields.put("vnp_SecureHash", VnpaySignature.sign(fields, SECRET));
        return fields;
    }
    private RequestBuilder ipnRequest(Map<String, String> fields) {
        var request = get("/api/v1/payments/vnpay/ipn");
        fields.forEach(request::param);
        return request;
    }
    private void ipn(Map<String, String> fields, String expected) throws Exception {
        mvc.perform(ipnRequest(fields)).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value(expected));
    }
    private String ipnCode(Map<String, String> fields) {
        try { return json.readTree(mvc.perform(ipnRequest(fields)).andReturn().getResponse().getContentAsString()).get("RspCode").asText(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private int httpStatus(RequestBuilder request) {
        try { return mvc.perform(request).andReturn().getResponse().getStatus(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private String auth(long id) { return "Bearer " + jwt.issue(users.findById(id).orElseThrow()).accessToken(); }
}
