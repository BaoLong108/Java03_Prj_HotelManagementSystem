package com.hotelpms.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.service.JwtService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.booking.scheduler.enabled=false", "app.payment.demo-enabled=false",
        "app.vnpay.enabled=true", "app.vnpay.tmn-code=QATEST01", "app.vnpay.hash-secret=local-fixture-signing-key",
        "app.vnpay.ipn-url=https://payment-fixture.example.test/api/v1/payments/vnpay/ipn"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = VnpayCheckoutIntegrationTest.Cleanup.class, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class VnpayCheckoutIntegrationTest {
    private static final long BOOKING = 89301, CUSTOMER = 89301, STAFF = 89302, OTHER = 89303;
    private static final long OTHER_BOOKING = 89302;
    private static final String CODE = "BK-VNPAYQA";
    private static final String PATH = "/api/v1/payments/vnpay";
    private static final String HISTORY = "/api/v1/customers/me/transactions";
    private static final String CRM = "/api/v1/admin/customers";
    private static final String RECEIPT = "/api/v1/bookings/" + CODE + "/confirmation.pdf";
    private static final String SECRET = "local-fixture-signing-key";
    private static final String GATEWAY = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private static EmbeddedPostgres postgres;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws IOException {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        properties.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        properties.add("spring.datasource.username", () -> "postgres");
        properties.add("spring.datasource.password", () -> "postgres");
        properties.add("app.jwt.secret", () -> "vnpay-test-signing-key-at-least-32-characters");
    }
    public static class Cleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext context) throws IOException { if (postgres != null) postgres.close(); }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;

    @BeforeEach void fixture() {
        for (long id : new long[]{CUSTOMER, STAFF, OTHER}) {
            jdbc.update("INSERT INTO app_users(id,email,password_hash,full_name) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                    id, "vnpay-fixture-" + id + "@test.invalid", "unused", "Payment QA");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=? ON CONFLICT DO NOTHING", id,
                    id == STAFF ? "STAFF" : "CUSTOMER");
        }
        jdbc.update("INSERT INTO customer_profiles(user_id) VALUES (?) ON CONFLICT DO NOTHING", CUSTOMER);
        jdbc.update("INSERT INTO customer_profiles(user_id) VALUES (?) ON CONFLICT DO NOTHING", OTHER);
        jdbc.update("INSERT INTO room_types(id,code,name,max_occupancy,base_price_per_night) VALUES (89301,'VNPAY_QA','Payment QA',2,1250000) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO physical_rooms(id,room_type_id,room_number) VALUES (89301,89301,'VNPAY-QA-1') ON CONFLICT DO NOTHING");
        jdbc.update("""
                INSERT INTO bookings(id,booking_code,customer_id,room_type_id,physical_room_id,guest_count,
                   start_at,end_at,hold_expires_at,base_amount,discount_amount,payable_amount)
                VALUES (?,?,?,89301,89301,2,'2060-01-10T05:00:00Z','2060-01-12T03:00:00Z',now()+interval '15 minutes',2500000,250000,2250000)
                """, BOOKING, CODE, CUSTOMER);
    }

    @AfterEach void cleanFixture() {
        jdbc.update("DELETE FROM payment_attempts WHERE booking_id IN (?,?)", BOOKING, OTHER_BOOKING);
        jdbc.update("UPDATE bookings SET voucher_code=NULL,voucher_discount_amount=0,payable_amount=base_amount-discount_amount WHERE id=?", BOOKING);
        jdbc.update("DELETE FROM loyalty_vouchers WHERE customer_id=?", CUSTOMER);
        jdbc.update("DELETE FROM bookings WHERE id IN (?,?)", BOOKING, OTHER_BOOKING);
    }

    private String auth(long actor) { return "Bearer " + jwt.issue(users.findById(actor).orElseThrow()).accessToken(); }
    private String draft(String key) { return json.writeValueAsString(Map.of("bookingCode", CODE, "idempotencyKey", key)); }
    private JsonNode checkout(String key) throws Exception {
        return json.readTree(mvc.perform(post(PATH + "/checkout").header("Authorization", auth(CUSTOMER))
                .contentType("application/json").content(draft(key))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private Map<String,String> query(JsonNode checkout) {
        Map<String,String> result = new TreeMap<>();
        for (String pair : URI.create(checkout.get("paymentUrl").asText()).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    private Map<String,String> callback(JsonNode checkout, String response, String transaction) {
        var fields = query(checkout);
        var callback = new TreeMap<>(Map.of("vnp_TmnCode", "QATEST01", "vnp_TxnRef", fields.get("vnp_TxnRef"),
                "vnp_Amount", fields.get("vnp_Amount"), "vnp_ResponseCode", response,
                "vnp_TransactionStatus", response.equals("00") ? "00" : "02", "vnp_TransactionNo", transaction));
        sign(callback);
        return callback;
    }
    private void sign(Map<String,String> fields) { fields.put("vnp_SecureHash", VnpaySignature.sign(fields, SECRET)); }
    private String ipn(Map<String,String> fields) {
        try {
            var request = get(PATH + "/ipn"); fields.forEach(request::param);
            return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("RspCode").asText();
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private JsonNode state(String reference, long actor, int expected) throws Exception {
        return json.readTree(mvc.perform(get(PATH + "/status").param("reference", reference).header("Authorization", auth(actor)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private void voucher(int points, int amount) throws Exception {
        jdbc.update("INSERT INTO loyalty_vouchers(code,customer_id,points_cost,discount_amount,idempotency_key) VALUES ('QA1234',?,?,?,'vnpay-fixture')",
                CUSTOMER, points, amount);
        mvc.perform(post("/api/v1/bookings/" + CODE + "/voucher").header("Authorization", auth(CUSTOMER))
                .contentType("application/json").content("{\"code\":\"QA1234\"}")).andExpect(status().isOk());
    }
    private int attempts() { return jdbc.queryForObject("SELECT count(*) FROM payment_attempts WHERE booking_id=?", Integer.class, BOOKING); }

    @Test void signedCheckoutUsesSnapshotAndConcurrentRequestsShareOneAttempt() throws Exception {
        var left = CompletableFuture.supplyAsync(() -> checkedCheckout("left"));
        var right = CompletableFuture.supplyAsync(() -> checkedCheckout("right"));
        JsonNode first = left.get(15, TimeUnit.SECONDS);
        assertThat(right.get(15, TimeUnit.SECONDS)).isEqualTo(first);
        assertThat(checkout("left")).isEqualTo(first);
        var fields = query(first);
        assertThat(first.get("paymentUrl").asText()).startsWith(GATEWAY + "?");
        assertThat(fields).containsEntry("vnp_Amount", "225000000").containsEntry("vnp_Version", "2.1.0")
                .containsEntry("vnp_ReturnUrl", "http://localhost:5173/payment-return?bookingCode=" + CODE);
        assertThat(VnpaySignature.valid(fields, SECRET)).isTrue();
        assertThat(attempts()).isEqualTo(1);
        long id = first.get("paymentAttemptId").asLong();
        // Return URL parameters are never evidence for the read API or receipt.
        mvc.perform(get(HISTORY + "/" + id).param("vnp_ResponseCode", "00").param("vnp_TransactionStatus", "00")
                .header("Authorization", auth(CUSTOMER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.status").value("PROCESSING"))
                .andExpect(jsonPath("$.transaction.receiptAvailable").value(false))
                .andExpect(jsonPath("$.settledAt").isEmpty());
        mvc.perform(get(RECEIPT).param("transactionId", Long.toString(id)).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isConflict());
    }
    private JsonNode checkedCheckout(String key) {
        try { return checkout(key); } catch (Exception error) { throw new AssertionError(error); }
    }

    @Test void verifiedConcurrentIpnConsumesVoucherOnceAndUnblocksPdf() throws Exception {
        Long assignedRoom = jdbc.queryForObject("SELECT physical_room_id FROM bookings WHERE id=?", Long.class, BOOKING);
        voucher(200, 2200000);
        var fields = callback(checkout("with-voucher"), "00", "893011");
        assertThat(fields.get("vnp_Amount")).isEqualTo("5000000");
        String ref = fields.get("vnp_TxnRef");
        assertThat(state(ref, CUSTOMER, 200).get("bookingStatus").asText()).isEqualTo("PENDING_PAYMENT");
        mvc.perform(delete("/api/v1/bookings/" + CODE + "/voucher").header("Authorization", auth(CUSTOMER))).andExpect(status().isConflict());
        var first = CompletableFuture.supplyAsync(() -> ipn(fields)); var second = CompletableFuture.supplyAsync(() -> ipn(fields));
        assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("00", "02");
        var paid = state(ref, CUSTOMER, 200);
        assertThat(paid.get("bookingStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(paid.get("paymentStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT physical_room_id FROM bookings WHERE id=?", Long.class, BOOKING)).isEqualTo(assignedRoom);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_vouchers WHERE booking_id=? AND used_at IS NOT NULL", Integer.class, BOOKING)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_awards WHERE booking_id=?", Integer.class, BOOKING)).isZero();
        mvc.perform(get("/api/v1/bookings/" + CODE + "/confirmation.pdf").header("Authorization", auth(CUSTOMER))).andExpect(status().isOk());
        var history = mvc.perform(get(HISTORY).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].amount").value(50000))
                .andExpect(jsonPath("$.items[0].receiptAvailable").value(true))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(history).get("items").get(0).get("id").asLong();
        var detail = mvc.perform(get(HISTORY + "/" + id).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.baseAmount").value(2500000))
                .andExpect(jsonPath("$.membershipDiscount").value(250000))
                .andExpect(jsonPath("$.voucherDiscount").value(2200000))
                .andExpect(jsonPath("$.payableAmount").value(50000))
                .andExpect(jsonPath("$.settledAt").isNotEmpty())
                .andExpect(jsonPath("$.providerTransactionId").value("893011"))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("idempotencyKey", "checkoutUrl", "checkout_url", "vnp_SecureHash",
                "password", "hashSecret", "cardNumber", "email", SECRET);
        mvc.perform(get(RECEIPT).param("transactionId", Long.toString(id)).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
    }

    @Test void mismatchedSignatureMerchantAmountAndUnknownReferenceCannotConfirm() throws Exception {
        var fields = callback(checkout("validation"), "00", "893012");
        fields.put("vnp_Amount", "100");
        assertThat(ipn(fields)).isEqualTo("97");
        sign(fields); assertThat(ipn(fields)).isEqualTo("04");
        fields.put("vnp_CurrCode", "USD"); sign(fields); assertThat(ipn(fields)).isEqualTo("99");
        fields.remove("vnp_CurrCode");
        fields.put("vnp_TmnCode", "WRONG123"); sign(fields); assertThat(ipn(fields)).isEqualTo("97");
        fields.put("vnp_TmnCode", "QATEST01"); fields.put("vnp_TxnRef", "PMSUnknown"); sign(fields);
        assertThat(ipn(fields)).isEqualTo("01");
        mvc.perform(get(PATH + "/ipn").param("vnp_Amount", "1", "2")).andExpect(jsonPath("$.RspCode").value("97"));
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, BOOKING)).isEqualTo("PENDING_PAYMENT");
    }

    @Test void ownershipAndRolesProtectCheckoutAndStatusAndLegacyDemoIsOff() throws Exception {
        mvc.perform(post(PATH + "/checkout").contentType("application/json").content(draft("guest"))).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/checkout").header("Authorization", auth(STAFF)).contentType("application/json").content(draft("staff"))).andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/checkout").header("Authorization", auth(OTHER)).contentType("application/json").content(draft("other"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments/demo").header("Authorization", auth(CUSTOMER)).contentType("application/json").content(draft("legacy"))).andExpect(status().isGone());
        mvc.perform(post("/api/v1/bookings/" + CODE + "/confirm").header("Authorization", auth(CUSTOMER))).andExpect(status().isConflict());
        String ref = query(checkout("owner")).get("vnp_TxnRef");
        state(ref, OTHER, 404); state(ref, STAFF, 403);
        mvc.perform(get(PATH + "/status").param("reference", ref)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/status").param("reference", ref).param("bookingCode", "BK-WRONG")
                .header("Authorization", auth(CUSTOMER))).andExpect(status().isNotFound());
    }

    @Test void cancellationCanRetryAndOriginalReferenceRemainsFailed() throws Exception {
        var first = callback(checkout("old-attempt"), "24", "0");
        assertThat(ipn(first)).isEqualTo("00");
        assertThat(ipn(first)).isEqualTo("02");
        mvc.perform(post(PATH + "/checkout").header("Authorization", auth(CUSTOMER)).contentType("application/json")
                .content(draft("old-attempt"))).andExpect(status().isConflict());
        var second = callback(checkout("new-attempt"), "00", "893013");
        assertThat(second.get("vnp_TxnRef")).isNotEqualTo(first.get("vnp_TxnRef"));
        assertThat(ipn(second)).isEqualTo("00");
        assertThat(state(first.get("vnp_TxnRef"), CUSTOMER, 200).get("outcome").asText()).isEqualTo("FAILED");
        assertThat(state(second.get("vnp_TxnRef"), CUSTOMER, 200).get("outcome").asText()).isEqualTo("SUCCEEDED");
        mvc.perform(get(HISTORY).param("search", CODE).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].bookingCode").value(CODE))
                .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[1].bookingCode").value(CODE))
                .andExpect(jsonPath("$.items[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[1].receiptAvailable").value(false));
    }

    @Test void lateSuccessRetainsProviderReferenceForReviewWithoutTakingTheRoom() throws Exception {
        voucher(200, 2200000);
        var callback = callback(checkout("late"), "00", "893014");
        jdbc.update("UPDATE bookings SET hold_expires_at=now()-interval '1 second', status='EXPIRED', expired_at=now() WHERE id=?", BOOKING);
        mvc.perform(get(HISTORY).param("status", "EXPIRED").header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        assertThat(ipn(callback)).isEqualTo("00"); assertThat(ipn(callback)).isEqualTo("02");
        var actual = state(callback.get("vnp_TxnRef"), CUSTOMER, 200);
        assertThat(actual.get("outcome").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(actual.get("bookingStatus").asText()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT provider_transaction_id FROM vnpay_checkouts WHERE reference=?", String.class, callback.get("vnp_TxnRef"))).isEqualTo("893014");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_vouchers WHERE booking_id=? AND used_at IS NOT NULL", Integer.class, BOOKING)).isZero();
        mvc.perform(get(HISTORY).param("status", "REVIEW_REQUIRED").header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.items[0].bookingStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.items[0].receiptAvailable").value(false));
    }

    @Test void onlyZeroSnapshotCanSettleAndVoucherIsConsumedOnceWithoutGateway() throws Exception {
        var body = json.writeValueAsString(Map.of("bookingCode", CODE));
        mvc.perform(post("/api/v1/payments/zero").header("Authorization", auth(CUSTOMER)).contentType("application/json").content(body)).andExpect(status().isConflict());
        jdbc.update("UPDATE bookings SET base_amount=900000,discount_amount=0,payable_amount=900000 WHERE id=?", BOOKING);
        voucher(100, 1000000);
        mvc.perform(post("/api/v1/payments/zero").header("Authorization", auth(OTHER)).contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(post(PATH + "/checkout").header("Authorization", auth(CUSTOMER)).contentType("application/json").content(draft("zero-vnpay"))).andExpect(status().isConflict());
        String settled = mvc.perform(post("/api/v1/payments/zero").header("Authorization", auth(CUSTOMER)).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("ZERO_PAYABLE")).andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/v1/payments/zero").header("Authorization", auth(CUSTOMER)).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replay)).isEqualTo(json.readTree(settled));
        assertThat(attempts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vnpay_checkouts c JOIN payment_attempts p ON p.id=c.payment_attempt_id WHERE p.booking_id=?", Integer.class, BOOKING)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_vouchers WHERE booking_id=? AND used_at IS NOT NULL", Integer.class, BOOKING)).isEqualTo(1);
        long id = json.readTree(settled).get("paymentAttemptId").asLong();
        mvc.perform(get(HISTORY).param("provider", "ZERO_PAYABLE").header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].method").value("VOUCHER"))
                .andExpect(jsonPath("$.items[0].amount").value(0));
        mvc.perform(get(HISTORY + "/" + id).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.voucherDiscount").value(900000))
                .andExpect(jsonPath("$.transaction.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.providerTransactionId").isEmpty());
        mvc.perform(get(RECEIPT).param("transactionId", Long.toString(id)).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        grantRole(STAFF, "ADMIN");
        try {
            mvc.perform(get(CRM + "/" + CUSTOMER + "/transactions").header("Authorization", auth(STAFF)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].method").value("VOUCHER"))
                    .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.items[0].settledAt").isNotEmpty());
            mvc.perform(get(CRM + "/" + CUSTOMER + "/vouchers").header("Authorization", auth(STAFF)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("USED"))
                    .andExpect(jsonPath("$.items[0].usedAt").isNotEmpty());
        } finally {
            removeRole(STAFF, "ADMIN");
        }
    }

    @Test void transactionHistoryAndReceiptAreScopedToTheJwtCustomerEvenWithStaffRole() throws Exception {
        var checkout = checkout("owned-attempt");
        long id = checkout.get("paymentAttemptId").asLong();
        assertThat(ipn(callback(checkout, "00", "893022"))).isEqualTo("00");
        jdbc.update("""
                INSERT INTO bookings(id,booking_code,customer_id,room_type_id,physical_room_id,guest_count,
                    start_at,end_at,hold_expires_at,base_amount,payable_amount)
                VALUES (?,'BK-OTHER',?,89301,89301,2,'2061-01-10T05:00:00Z','2061-01-12T03:00:00Z',
                    now()+interval '15 minutes',200000,200000)
                """, OTHER_BOOKING, OTHER);
        jdbc.update("""
                INSERT INTO payment_attempts(booking_id,provider,idempotency_key,requested_amount)
                VALUES (?,'DEMO','other-customer',200000)
                """, OTHER_BOOKING);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='STAFF'", OTHER);
        try {
            mvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());
            mvc.perform(get(HISTORY + "/" + id)).andExpect(status().isUnauthorized());
            mvc.perform(get(HISTORY).header("Authorization", auth(STAFF))).andExpect(status().isForbidden());
            mvc.perform(get(HISTORY).param("customerId", Long.toString(OTHER)).header("Authorization", auth(CUSTOMER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].bookingCode").value(CODE));
            mvc.perform(get(HISTORY).header("Authorization", auth(OTHER))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].bookingCode").value("BK-OTHER"));
            mvc.perform(get(HISTORY).param("search", CODE).header("Authorization", auth(OTHER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
            mvc.perform(get(HISTORY + "/" + id).header("Authorization", auth(OTHER))).andExpect(status().isNotFound());
            mvc.perform(get(RECEIPT).param("transactionId", Long.toString(id)).header("Authorization", auth(OTHER)))
                    .andExpect(status().isNotFound());
            mvc.perform(get(RECEIPT).param("transactionId", Long.toString(id)).header("Authorization", auth(STAFF)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/bookings/BK-OTHER/confirmation.pdf").param("transactionId", Long.toString(id))
                    .header("Authorization", auth(CUSTOMER))).andExpect(status().isNotFound());
        } finally {
            jdbc.update("DELETE FROM user_roles WHERE user_id=? AND role_id IN (SELECT id FROM roles WHERE code='STAFF')", OTHER);
        }
    }

    @Test void transactionHistoryFiltersAndPaginatesWithStableTiesAndHotelDates() throws Exception {
        var failed = checkout("failed-attempt");
        assertThat(ipn(callback(failed, "51", "0"))).isEqualTo("00");
        var expired = checkout("expired-attempt");
        assertThat(ipn(callback(expired, "11", "0"))).isEqualTo("00");
        long failedId = failed.get("paymentAttemptId").asLong();
        long expiredId = expired.get("paymentAttemptId").asLong();
        jdbc.update("UPDATE payment_attempts SET created_at='2026-01-03T17:00:00Z' WHERE booking_id=?", BOOKING);
        jdbc.update("""
                INSERT INTO payment_attempts(booking_id,provider,idempotency_key,requested_amount,created_at)
                VALUES (?,'DEMO','older-demo',2250000,'2026-01-03T16:59:59Z')
                """, BOOKING);
        mvc.perform(get(HISTORY).param("provider", "VNPAY").param("from", "2026-01-04").param("to", "2026-01-04")
                .param("size", "1").header("Authorization", auth(CUSTOMER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").value(expiredId)).andExpect(jsonPath("$.items[0].status").value("EXPIRED"));
        mvc.perform(get(HISTORY).param("provider", "VNPAY").param("size", "1").param("page", "1")
                .header("Authorization", auth(CUSTOMER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(failedId));
        mvc.perform(get(HISTORY).param("provider", "VNPAY").param("size", "1").param("sort", "OLDEST")
                .header("Authorization", auth(CUSTOMER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(failedId));
        mvc.perform(get(HISTORY).param("status", "FAILED").param("search", query(failed).get("vnp_TxnRef").toLowerCase(java.util.Locale.ROOT))
                .header("Authorization", auth(CUSTOMER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(failedId));
        mvc.perform(get(HISTORY).param("status", "PENDING").param("provider", "DEMO").param("to", "2026-01-03")
                .header("Authorization", auth(CUSTOMER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(HISTORY).param("search", "%").header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(HISTORY).param("size", "51").header("Authorization", auth(CUSTOMER))).andExpect(status().isBadRequest());
        mvc.perform(get(HISTORY).param("from", "2026-01-04").param("to", "2026-01-03").header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isBadRequest());
    }

    @Test void transactionHistoryKeepsOriginalVoucherAmountWhenTheBookingVoucherChanges() throws Exception {
        voucher(100, 1000000);
        var original = checkout("voucher-attempt");
        assertThat(ipn(callback(original, "51", "0"))).isEqualTo("00");
        mvc.perform(delete("/api/v1/bookings/" + CODE + "/voucher").header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk());
        var retry = checkout("without-voucher");
        mvc.perform(get(HISTORY + "/" + original.get("paymentAttemptId").asLong()).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.voucherDiscount").value(1000000))
                .andExpect(jsonPath("$.payableAmount").value(1250000));
        mvc.perform(get(HISTORY + "/" + retry.get("paymentAttemptId").asLong()).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.voucherDiscount").value(0))
                .andExpect(jsonPath("$.payableAmount").value(2250000));
    }

    @Test void sandboxConfigurationRejectsMissingMerchantCallbackAndNonSandboxGateway() {
        String returning = "http://localhost:5173/payment-return";
        String ipn = "https://payment-fixture.example.test/api/v1/payments/vnpay/ipn";
        assertThat(new VnpayProperties.Settings(true, "QATEST01", SECRET, GATEWAY, returning, ipn).available()).isTrue();
        assertThat(new VnpayProperties.Settings(true, "QATEST01", "", GATEWAY, returning, ipn).available()).isFalse();
        assertThat(new VnpayProperties.Settings(true, "QATEST01", SECRET, GATEWAY, returning, "").available()).isFalse();
        assertThat(new VnpayProperties.Settings(true, "QATEST01", SECRET, "https://pay.vnpay.vn/vpcpay.html", returning, ipn).available()).isFalse();
        assertThat(new VnpayProperties.Settings(true, "QATEST01", SECRET, GATEWAY, returning, "http://localhost:8080/api/v1/payments/vnpay/ipn").available()).isFalse();
    }

    @Test void adminCustomerDirectoryUsesRealRolesStatisticsFiltersAndPagination() throws Exception {
        mvc.perform(get(CRM + "/" + CUSTOMER).header("Authorization", auth(STAFF))).andExpect(status().isForbidden());
        grantRole(STAFF, "ADMIN");
        grantRole(OTHER, "STAFF");
        try {
            jdbc.update("UPDATE customer_profiles SET tier_code='VIP',eligible_spend_total=12000000,phone_number='0900999888' WHERE user_id=?", CUSTOMER);
            jdbc.update("UPDATE app_users SET is_active=false WHERE id=?", OTHER);
            mvc.perform(get(CRM)).andExpect(status().isUnauthorized());
            mvc.perform(get(CRM).header("Authorization", auth(CUSTOMER))).andExpect(status().isForbidden());
            mvc.perform(get(CRM + "/" + CUSTOMER).header("Authorization", auth(CUSTOMER))).andExpect(status().isForbidden());
            mvc.perform(get(CRM).param("size", "1").param("sort", "SPENDING").header("Authorization", auth(STAFF)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.statistics.total").value(2)).andExpect(jsonPath("$.statistics.vip").value(1))
                    .andExpect(jsonPath("$.statistics.standard").value(1)).andExpect(jsonPath("$.customers.totalPages").value(2))
                    .andExpect(jsonPath("$.customers.items[0].id").value(CUSTOMER))
                    .andExpect(jsonPath("$.customers.items[0].bookingCount").value(1))
                    .andExpect(jsonPath("$.customers.items[0].passwordHash").doesNotExist());
            mvc.perform(get(CRM).param("size", "1").param("page", "1").param("sort", "SPENDING")
                    .header("Authorization", auth(STAFF))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.customers.items[0].id").value(OTHER));
            for (String term : List.of("payment qa", "vnpay-fixture-" + CUSTOMER, "0900999888", Long.toString(CUSTOMER))) {
                mvc.perform(get(CRM).param("search", term).param("tier", "VIP").param("active", "true")
                        .header("Authorization", auth(STAFF))).andExpect(status().isOk())
                        .andExpect(jsonPath("$.customers.totalElements").value(1));
            }
            mvc.perform(get(CRM).param("active", "false").header("Authorization", auth(STAFF))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.customers.items[0].id").value(OTHER));
            mvc.perform(get(CRM).param("size", "51").header("Authorization", auth(STAFF))).andExpect(status().isBadRequest());
            mvc.perform(get(CRM + "/" + STAFF).header("Authorization", auth(STAFF))).andExpect(status().isNotFound());
        } finally {
            jdbc.update("UPDATE app_users SET is_active=true WHERE id=?", OTHER);
            jdbc.update("UPDATE customer_profiles SET tier_code='STANDARD',eligible_spend_total=0,phone_number=NULL WHERE user_id=?", CUSTOMER);
            removeRole(OTHER, "STAFF");
            removeRole(STAFF, "ADMIN");
        }
    }

    @Test void adminProfileUsesScopedPersistedHistoriesWithoutChangingBalancesOrBooking() throws Exception {
        grantRole(STAFF, "ADMIN");
        try {
            jdbc.update("UPDATE customer_profiles SET eligible_spend_total=800000,points_balance=40 WHERE user_id=?", CUSTOMER);
            voucher(100, 1000000);
            var pending = checkout("crm-unverified");
            long attempt = pending.get("paymentAttemptId").asLong();
            jdbc.update("""
                    INSERT INTO payment_attempts(booking_id,provider,idempotency_key,requested_amount,status)
                    VALUES (?,'DEMO','crm-failed',2250000,'FAILED')
                    """, BOOKING);
            jdbc.update("""
                    INSERT INTO check_in_requests(booking_id,estimated_arrival_at,early_check_in_requested,created_at,updated_at)
                    VALUES (?,'2060-01-10T03:00:00Z',true,now(),now())
                    """, BOOKING);
            String owner = CRM + "/" + CUSTOMER;
            var admin = auth(STAFF);
            mvc.perform(get(owner).header("Authorization", admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.membership.remainingSpend").value(9200000))
                    .andExpect(jsonPath("$.membership.pointsBalance").value(40))
                    .andExpect(jsonPath("$.pointsRedeemed").value(100)).andExpect(jsonPath("$.verifiedPayments").value(0));
            mvc.perform(get(owner + "/bookings").param("from", "2060-01-10").param("to", "2060-01-10")
                    .param("status", "PENDING_PAYMENT").header("Authorization", admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].bookingCode").value(CODE))
                    .andExpect(jsonPath("$.items[0].voucherDiscount").value(1000000));
            mvc.perform(get(owner + "/transactions").header("Authorization", admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2));
            var detail = mvc.perform(get(owner + "/transactions/" + attempt).header("Authorization", admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.transaction.status").value("PROCESSING"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(detail).doesNotContain("checkoutUrl", "hashSecret", "idempotencyKey", "vnp_SecureHash");
            mvc.perform(get(CRM + "/" + OTHER + "/transactions/" + attempt).header("Authorization", admin))
                    .andExpect(status().isNotFound());
            mvc.perform(get(CRM + "/" + OTHER + "/bookings").header("Authorization", admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
            mvc.perform(get(owner + "/loyalty").header("Authorization", admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].type").value("REDEEMED")).andExpect(jsonPath("$.items[0].points").value(100));
            mvc.perform(get(owner + "/vouchers").header("Authorization", admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].status").value("RESERVED")).andExpect(jsonPath("$.items[0].code").value("••••34"));
            mvc.perform(get(owner + "/check-in-requests").header("Authorization", admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].bookingCode").value(CODE)).andExpect(jsonPath("$.items[0].early").value(true));
            assertThat(jdbc.queryForObject("SELECT points_balance FROM customer_profiles WHERE user_id=?", Long.class, CUSTOMER)).isEqualTo(40L);
            assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, BOOKING)).isEqualTo("PENDING_PAYMENT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_vouchers WHERE customer_id=? AND used_at IS NULL", Long.class, CUSTOMER)).isEqualTo(1L);
        } finally {
            jdbc.update("DELETE FROM check_in_requests WHERE booking_id=?", BOOKING);
            jdbc.update("UPDATE customer_profiles SET eligible_spend_total=0,points_balance=0 WHERE user_id=?", CUSTOMER);
            removeRole(STAFF, "ADMIN");
        }
    }

    private void grantRole(long user, String role) {
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?", user, role);
    }

    private void removeRole(long user, String role) {
        jdbc.update("DELETE FROM user_roles WHERE user_id=? AND role_id IN (SELECT id FROM roles WHERE code=?)", user, role);
    }
}
