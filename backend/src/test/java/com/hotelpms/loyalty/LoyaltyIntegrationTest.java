package com.hotelpms.loyalty;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.doReturn;

import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.service.JwtService;
import com.hotelpms.booking.service.BookingService;
import com.hotelpms.booking.dto.CreateBookingRequest;
import com.hotelpms.loyalty.dto.RedeemVoucherRequest;
import com.hotelpms.payment.dto.DemoPaymentRequest;
import com.hotelpms.payment.service.DemoPaymentService;
import com.hotelpms.payment.service.BookingConfirmationService;
import com.hotelpms.shared.api.ApiException;
import com.hotelpms.loyalty.repository.LoyaltyAwardRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = LoyaltyIntegrationTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class LoyaltyIntegrationTest {
    private static final long CUSTOMER = 81001L, OTHER = 81002L, STAFF = 81003L, ADMIN = 81004L;
    private static final long ROOM_TYPE = 81001L, ROOM = 81001L;
    private static final Instant NOW = Instant.parse("2032-01-10T05:00:00Z");
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
        properties.add("app.jwt.secret", () -> "loyalty-test-signing-key-at-least-thirty-two-characters");
        properties.add("app.booking.scheduler.enabled", () -> "false");
        properties.add("app.loyalty.scheduler.enabled", () -> "false");
    }

    public static class PostgresCleanupListener extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext context) throws IOException {
            if (postgres != null) postgres.close();
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LoyaltyAwardService awards;
    @Autowired LoyaltyAwardRepository ledger;
    @Autowired MembershipAccountService accounts;
    @Autowired BookingService bookings;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired VoucherService vouchers;
    @Autowired VoucherReservationMaintenance voucherMaintenance;
    @Autowired DemoPaymentService demoPayments;
    @Autowired BookingConfirmationService confirmations;
    @MockitoSpyBean VoucherCodeGenerator codes;

    @BeforeEach
    void prepare() {
        clean();
        for (long id : new long[]{CUSTOMER, OTHER, STAFF, ADMIN}) {
            jdbc.update("""
                insert into app_users(id,email,password_hash,full_name) values(?,?,?,?) on conflict(id) do nothing
                """, id, "loyalty-" + id + "@test.invalid", "unused", "Loyalty Test");
            String role = id == STAFF ? "STAFF" : id == ADMIN ? "ADMIN" : "CUSTOMER";
            jdbc.update("insert into user_roles(user_id,role_id) select ?,id from roles where code=? on conflict do nothing", id, role);
        }
        for (long id : new long[]{CUSTOMER, OTHER}) {
            jdbc.update("""
                insert into customer_profiles(user_id) values(?) on conflict(user_id) do update
                set eligible_spend_total=0,points_balance=0,tier_code='STANDARD'
                """, id);
        }
        jdbc.update("""
            insert into room_types(id,code,name,max_occupancy,base_price_per_night)
            values(?,'LOYALTY_E2E','Loyalty E2E',2,1000000) on conflict(id) do nothing
            """, ROOM_TYPE);
        jdbc.update("""
            insert into physical_rooms(id,room_type_id,room_number) values(?,?,'LOYALTY_E2E_1') on conflict(id) do nothing
            """, ROOM, ROOM_TYPE);
    }

    @AfterEach void clean() {
        jdbc.update("update bookings set voucher_code=null,voucher_discount_amount=0,payable_amount=base_amount-discount_amount where customer_id in (?,?)", CUSTOMER, OTHER);
        jdbc.update("delete from loyalty_vouchers where customer_id in (?,?)", CUSTOMER, OTHER);
        jdbc.update("delete from loyalty_awards where customer_id in (?,?)", CUSTOMER, OTHER);
        jdbc.update("delete from payment_attempts where booking_id in (select id from bookings where customer_id in (?,?))", CUSTOMER, OTHER);
        jdbc.update("delete from bookings where customer_id in (?,?)", CUSTOMER, OTHER);
        jdbc.update("delete from room_blocks where physical_room_id=?", ROOM);
    }

    @Test void realHttpFlowHoldPaymentCompletionLoyaltyAndAdjacentResale() throws Exception {
        String token = token(CUSTOMER);
        String start = "2032-01-01T05:00:00Z", end = "2032-01-02T03:00:00Z";
        String search = "/api/v1/rooms/availability?startAt=" + start + "&endAt=" + end + "&guestCount=2";
        mvc.perform(get(search)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == 81001)]").isNotEmpty());
        String body = """
            {"roomTypeId":81001,"guestCount":2,"startAt":"2032-01-01T05:00:00Z","endAt":"2032-01-02T03:00:00Z"}
            """;
        var created = json.readTree(mvc.perform(post("/api/v1/bookings").header("Authorization", token)
                .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String code = created.get("bookingCode").asText();
        mvc.perform(get(search)).andExpect(jsonPath("$[?(@.id == 81001)]").isEmpty());
        mvc.perform(post("/api/v1/bookings").header("Authorization", token).contentType("application/json").content(body))
                .andExpect(status().isConflict());
        String payment = "{\"bookingCode\":\"" + code + "\",\"idempotencyKey\":\"loyalty-e2e\"}";
        mvc.perform(post("/api/v1/payments/demo").header("Authorization", token).contentType("application/json").content(payment))
                .andExpect(status().isOk());
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isZero();
        assertThat(bookings.autoCompleteDepartedBookings(NOW)).isEqualTo(1);
        assertThat(bookings.autoCompleteDepartedBookings(NOW)).isZero();
        mvc.perform(get("/api/v1/customers/me/membership").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pointsBalance").value(100))
                .andExpect(jsonPath("$.eligibleSpendTotal").value(1000000));
        mvc.perform(get("/api/v1/customers/me/loyalty-awards").header("Authorization", token))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].bookingCode").value(code));
        mvc.perform(get("/api/v1/bookings").header("Authorization", token))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
        // Same historical interval remains occupied; next stay has the specified two-hour turnaround.
        mvc.perform(get(search)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == 81001)]").isEmpty());
        String adjacent = body.replace("2032-01-02T03:00:00Z", "2032-01-03T03:00:00Z")
                .replace("2032-01-01T05:00:00Z", "2032-01-02T05:00:00Z");
        mvc.perform(post("/api/v1/bookings").header("Authorization", token).contentType("application/json").content(adjacent))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/bookings/" + code + "/confirmation.pdf").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
    }

    @Test void concurrentRetriesAwardExactlyOnce() throws Exception {
        long id = booking(CUSTOMER, "COMPLETED", "123456.78", 0, true);
        var gate = new CountDownLatch(1);
        var a = concurrentAward(id, gate);
        var b = concurrentAward(id, gate);
        gate.countDown();
        assertThat(a.get(15, TimeUnit.SECONDS) ^ b.get(15, TimeUnit.SECONDS)).isTrue();
        assertThat(ledger.findHistory(CUSTOMER, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isEqualTo(12);
        assertThat(accounts.summary(CUSTOMER).eligibleSpendTotal()).isEqualByComparingTo("123456.78");
    }

    @Test void concurrentDifferentStaysDoNotLoseCustomerTotals() throws Exception {
        long first = booking(CUSTOMER, "COMPLETED", "5000000", 0, true);
        long second = booking(CUSTOMER, "COMPLETED", "5000000", 1, true);
        var gate = new CountDownLatch(1);
        var a = concurrentAward(first, gate);
        var b = concurrentAward(second, gate);
        gate.countDown();
        assertThat(a.get(15, TimeUnit.SECONDS)).isTrue();
        assertThat(b.get(15, TimeUnit.SECONDS)).isTrue();
        var summary = accounts.summary(CUSTOMER);
        assertThat(summary.eligibleSpendTotal()).isEqualByComparingTo("10000000");
        assertThat(summary.pointsBalance()).isEqualTo(1000);
        assertThat(summary.tierCode()).isEqualTo(LoyaltyTier.VIP);
    }

    @Test void tierUpgradeUsesPaidSnapshotAndDoesNotRepriceOldBooking() {
        long id = booking(CUSTOMER, "COMPLETED", "30000000", 0, true);
        assertThat(awards.awardCompletedBooking(id, NOW)).isTrue();
        assertThat(accounts.summary(CUSTOMER).tierCode()).isEqualTo(LoyaltyTier.DIAMOND);
        assertThat(accounts.summary(CUSTOMER).discountRate()).isEqualByComparingTo("0.15");
        assertThat(accounts.summary(CUSTOMER).nextTier()).isNull();
        assertThat(jdbc.queryForObject("select payable_amount from bookings where id=?", BigDecimal.class, id))
                .isEqualByComparingTo("30000000");
        assertThat(jdbc.queryForObject("select discount_rate from bookings where id=?", BigDecimal.class, id)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"PENDING_PAYMENT", "CONFIRMED", "CANCELLED", "EXPIRED"})
    void nonCompletedBookingNeverEarnsPoints(String state) {
        assertThat(awards.awardCompletedBooking(booking(CUSTOMER, state, "1000000", 0, true), NOW)).isFalse();
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isZero();
    }

    @Test void unpaidFailedOrMismatchedPaymentNeverEarnsPoints() {
        long unpaid = booking(CUSTOMER, "COMPLETED", "1000000", 0, false);
        assertThat(awards.awardCompletedBooking(unpaid, NOW)).isFalse();
        jdbc.update("""
            insert into payment_attempts(booking_id,provider,idempotency_key,status,requested_amount)
            values(?,'DEMO','failed','FAILED',1000000)
            """, unpaid);
        assertThat(awards.awardCompletedBooking(unpaid, NOW)).isFalse();
        long mismatch = booking(CUSTOMER, "COMPLETED", "1000000", 1, true);
        jdbc.update("update payment_attempts set requested_amount=1 where booking_id=?", mismatch);
        assertThat(awards.awardCompletedBooking(mismatch, NOW)).isFalse();
    }

    @Test void futureStayOrCompletionCannotEarnEarly() {
        long id = booking(CUSTOMER, "COMPLETED", "1000000", 0, true);
        assertThat(awards.awardCompletedBooking(id, Instant.parse("2030-01-01T00:00:00Z"))).isFalse();
        assertThat(awards.awardCompletedBooking(id, NOW.minusSeconds(1))).isFalse();
    }

    @Test void awardAndProfileUpdateRollBackTogether() {
        long id = booking(CUSTOMER, "COMPLETED", "1000000", 0, true);
        jdbc.update("update customer_profiles set points_balance=? where user_id=?", Long.MAX_VALUE, CUSTOMER);
        assertThatThrownBy(() -> awards.awardCompletedBooking(id, NOW)).isInstanceOf(ArithmeticException.class);
        assertThat(ledger.existsByBookingId(id)).isFalse();
        assertThat(accounts.summary(CUSTOMER).eligibleSpendTotal()).isZero();
    }

    @Test void pointsUseNetPaymentAfterDiscountRatherThanBaseAmount() {
        long id = booking(CUSTOMER, "COMPLETED", "999999", 0, true);
        jdbc.update("update bookings set base_amount=1111110,discount_rate=0.1,discount_amount=111111 where id=?", id);
        assertThat(awards.awardCompletedBooking(id, NOW)).isTrue();
        assertThat(accounts.summary(CUSTOMER).eligibleSpendTotal()).isEqualByComparingTo("999999");
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isEqualTo(99);
    }

    @Test void failedAwardRollsBackTheBookingCompletionToo() {
        long id = booking(CUSTOMER, "CONFIRMED", "1000000", 0, true);
        jdbc.update("update customer_profiles set points_balance=? where user_id=?", Long.MAX_VALUE, CUSTOMER);
        assertThatThrownBy(() -> bookings.completeBookingIfConfirmed(id, NOW)).isInstanceOf(ArithmeticException.class);
        assertThat(jdbc.queryForObject("select status from bookings where id=?", String.class, id)).isEqualTo("CONFIRMED");
        assertThat(ledger.existsByBookingId(id)).isFalse();
    }

    @Test void ledgerRecoveryFindsPreviouslyCompletedPaidStay() {
        long id = booking(CUSTOMER, "COMPLETED", "1000000", 0, true);
        assertThat(ledger.findUnawardedCompletedBookings(PageRequest.of(0, 100))).contains(id);
        assertThat(awards.awardCompletedBooking(id, NOW)).isTrue();
        assertThat(ledger.findUnawardedCompletedBookings(PageRequest.of(0, 100))).doesNotContain(id);
    }

    @Test void selfEndpointsEnforceJwtOwnershipAndOperationalRolesCannotReadThem() throws Exception {
        long id = booking(CUSTOMER, "COMPLETED", "1000000", 0, true);
        awards.awardCompletedBooking(id, NOW);
        for (String path : new String[]{"/membership", "/loyalty-awards"}) {
            mvc.perform(get("/api/v1/customers/me" + path)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/customers/me" + path).header("Authorization", token(STAFF))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/customers/me" + path).header("Authorization", token(ADMIN))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/customers/me/membership?customerId=" + CUSTOMER).header("Authorization", token(OTHER)))
                .andExpect(jsonPath("$.pointsBalance").value(0));
        mvc.perform(get("/api/v1/customers/me/loyalty-awards?customerId=" + CUSTOMER).header("Authorization", token(OTHER)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/bookings").header("Authorization", token(OTHER))).andExpect(content().json("[]"));
    }

    @Test void historyPaginationIsBoundedAndDeterministic() throws Exception {
        awards.awardCompletedBooking(booking(CUSTOMER, "COMPLETED", "10000", 0, true), NOW);
        awards.awardCompletedBooking(booking(CUSTOMER, "COMPLETED", "20000", 1, true), NOW);
        mvc.perform(get("/api/v1/customers/me/loyalty-awards?page=1&size=1").header("Authorization", token(CUSTOMER)))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].pointsAwarded").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/customers/me/loyalty-awards?page=-1").header("Authorization", token(CUSTOMER)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers/me/loyalty-awards?size=51").header("Authorization", token(CUSTOMER)))
                .andExpect(status().isBadRequest());
    }

    @Test void staffBlockRejectsBookingThenReleaseAllowsHold() throws Exception {
        String staff = token(STAFF), customer = token(CUSTOMER);
        String body = """
            {"physicalRoomId":81001,"startAt":"2032-01-01T05:00:00Z","endAt":"2032-01-02T03:00:00Z","reason":"E2E maintenance"}
            """;
        var blocked = mvc.perform(post("/api/v1/admin/room-blocks").header("Authorization", staff)
                .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
        long blockId = json.readTree(blocked.getResponse().getContentAsString()).get("id").asLong();
        String bookingBody = body.replace("\"physicalRoomId\"", "\"roomTypeId\"")
                .replace("\"reason\":\"E2E maintenance\"", "\"guestCount\":2");
        mvc.perform(post("/api/v1/bookings").header("Authorization", customer).contentType("application/json").content(bookingBody))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/admin/room-blocks/" + blockId).header("Authorization", staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAt").isNotEmpty());
        mvc.perform(post("/api/v1/bookings").header("Authorization", customer).contentType("application/json").content(bookingBody))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest @CsvSource({"50,400000", "100,1000000", "200,2200000"})
    void redeemExactOfferDeductsOnlyPoints(int points, String amount) {
        setPoints(500);
        var voucher = vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(points, "redeem-offer-" + points));
        assertThat(voucher.code()).matches("(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{6}");
        assertThat(voucher.discountAmount()).isEqualByComparingTo(amount);
        assertThat(voucher.status()).isEqualTo("AVAILABLE");
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isEqualTo(500 - points);
        assertThat(accounts.summary(CUSTOMER).eligibleSpendTotal()).isZero();
        assertThat(accounts.summary(CUSTOMER).tierCode()).isEqualTo(LoyaltyTier.STANDARD);
    }

    @ParameterizedTest @ValueSource(ints={-1, 0, 49, 75, 201})
    void unknownOfferCannotSpendPoints(int points) {
        setPoints(500);
        assertThatThrownBy(() -> vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(points, "invalid-offer")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_VOUCHER_OFFER"));
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isEqualTo(500);
        assertThat(vouchers.wallet(CUSTOMER, 0).totalElements()).isZero();
    }

    @Test void insufficientPointsAndIdempotentRedemption() {
        setPoints(50);
        var request = new RedeemVoucherRequest(50, "redeem-idempotent");
        var created = vouchers.redeem(CUSTOMER, request);
        assertThat(vouchers.redeem(CUSTOMER, request).code()).isEqualTo(created.code());
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isZero();
        assertThat(vouchers.wallet(CUSTOMER, 0).totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(50, "another-request")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("INSUFFICIENT_POINTS"));
        assertThatThrownBy(() -> vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(100, "redeem-idempotent")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test void concurrentRedemptionRetriesHaveOneCodeAndOneDebit() throws Exception {
        setPoints(50);
        var gate = new CountDownLatch(1);
        var request = new RedeemVoucherRequest(50, "concurrent-retry");
        var first = concurrently(gate, () -> vouchers.redeem(CUSTOMER, request));
        var second = concurrently(gate, () -> vouchers.redeem(CUSTOMER, request));
        gate.countDown();
        assertThat(first.get(15, TimeUnit.SECONDS).code()).isEqualTo(second.get(15, TimeUnit.SECONDS).code());
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isZero();
        assertThat(vouchers.wallet(CUSTOMER, 0).totalElements()).isEqualTo(1);
    }

    @Test void concurrentDifferentRedemptionsNeverOverspendBalance() throws Exception {
        setPoints(150);
        var gate = new CountDownLatch(1);
        var first = concurrently(gate, () -> tryRedeem("different-redemption-1"));
        var second = concurrently(gate, () -> tryRedeem("different-redemption-2"));
        gate.countDown();
        assertThat(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS)).isTrue();
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isEqualTo(50);
        assertThat(vouchers.wallet(CUSTOMER, 0).totalElements()).isEqualTo(1);
    }

    @Test void randomCodeCollisionRetriesWithoutLosingPoints() {
        setPoints(200);
        doReturn("A1B2C3").when(codes).generate();
        vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(50, "collision-first"));
        doReturn("A1B2C3", "Z9Y8X7").when(codes).generate();
        var second = vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(50, "collision-second"));
        assertThat(second.code()).isEqualTo("Z9Y8X7");
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isEqualTo(100);
        doReturn("A1B2C3").when(codes).generate();
        assertThatThrownBy(() -> vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(50, "collision-exhausted")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("VOUCHER_CODE_UNAVAILABLE"));
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isEqualTo(100);
        assertThat(vouchers.wallet(CUSTOMER, 0).totalElements()).isEqualTo(2);
    }

    @Test void voucherDiscountComesAfterVipAndOnlyNetPaymentEarnsPoints() throws Exception {
        setPoints(50);
        jdbc.update("update customer_profiles set eligible_spend_total=10000000,tier_code='VIP' where user_id=?", CUSTOMER);
        var voucher = vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(50, "vip-net-payment"));
        var hold = createHold(0);
        assertThat(hold.discountAmount()).isEqualByComparingTo("100000");
        var applied = vouchers.apply(hold.bookingCode(), voucher.code(), CUSTOMER);
        assertThat(applied.baseAmount()).isEqualByComparingTo("1000000");
        assertThat(applied.discountAmount()).isEqualByComparingTo("100000");
        assertThat(applied.afterMembershipAmount()).isEqualByComparingTo("900000");
        assertThat(applied.voucherDiscountAmount()).isEqualByComparingTo("400000");
        assertThat(applied.payableAmount()).isEqualByComparingTo("500000");
        var request = new DemoPaymentRequest(hold.bookingCode(), "voucher-paid-once", false);
        var payment = demoPayments.pay(request, CUSTOMER);
        assertThat(payment.requestedAmount()).isEqualByComparingTo("500000");
        assertThat(demoPayments.pay(request, CUSTOMER).paymentAttemptId()).isEqualTo(payment.paymentAttemptId());
        assertThat(vouchers.wallet(CUSTOMER, 0).content().getFirst().status()).isEqualTo("USED");
        assertThat(bookings.completeBookingIfConfirmed(hold.id(), NOW)).isTrue();
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isEqualTo(50);
        assertThat(accounts.summary(CUSTOMER).eligibleSpendTotal()).isEqualByComparingTo("10500000");
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(confirmations.pdf(hold.bookingCode(), CUSTOMER, false))) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf))
                    .contains("Voucher: " + voucher.code(), "Voucher discount: 400000.00 VND", "Paid: 500000.00 VND");
        }
        assertThatThrownBy(() -> vouchers.remove(hold.bookingCode(), CUSTOMER)).isInstanceOf(ApiException.class);
        var next = createHold(1);
        assertThatThrownBy(() -> vouchers.apply(next.bookingCode(), voucher.code(), CUSTOMER))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("VOUCHER_ALREADY_USED"));
    }

    @Test void excessiveVoucherCapsAtZeroAndDoesNotEarnPointsForTheFreeStay() {
        setPoints(200);
        var voucher = vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(200, "zero-total-booking"));
        var hold = createHold(0);
        var applied = vouchers.apply(hold.bookingCode(), voucher.code(), CUSTOMER);
        assertThat(applied.voucherDiscountAmount()).isEqualByComparingTo("1000000");
        assertThat(applied.payableAmount()).isZero();
        assertThat(demoPayments.pay(new DemoPaymentRequest(hold.bookingCode(), "zero-paid-once", false), CUSTOMER).requestedAmount()).isZero();
        assertThat(bookings.completeBookingIfConfirmed(hold.id(), NOW)).isTrue();
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isZero();
        assertThat(accounts.summary(CUSTOMER).eligibleSpendTotal()).isZero();
        assertThat(ledger.existsByBookingId(hold.id())).isTrue();
        assertThat(vouchers.wallet(CUSTOMER, 0).content().getFirst().status()).isEqualTo("USED");
    }

    @Test void voucherIsReservedOnceAndCanBeRemovedBeforePayment() throws Exception {
        setPoints(150);
        var voucher = vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(50, "reserve-one-code"));
        var replacement = vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(100, "replace-one-code"));
        var first = createHold(0);
        var second = createHold(1);
        var gate = new CountDownLatch(1);
        var a = concurrently(gate, () -> tryApply(first.bookingCode(), voucher.code()));
        var b = concurrently(gate, () -> tryApply(second.bookingCode(), voucher.code()));
        gate.countDown();
        assertThat(a.get(15, TimeUnit.SECONDS) ^ b.get(15, TimeUnit.SECONDS)).isTrue();
        var reservation = vouchers.wallet(CUSTOMER, 0).content().stream().filter(item -> item.code().equals(voucher.code())).findFirst().orElseThrow();
        String winner = reservation.bookingId().equals(first.id()) ? first.bookingCode() : second.bookingCode();
        assertThatThrownBy(() -> vouchers.apply(winner, replacement.code(), CUSTOMER))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("REMOVE_CURRENT_VOUCHER"));
        assertThat(vouchers.remove(winner, CUSTOMER).payableAmount()).isEqualByComparingTo("1000000");
        assertThat(vouchers.apply(winner, replacement.code(), CUSTOMER).payableAmount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"CANCEL", "SCHEDULER", "PAYMENT", "CONFIRM", "ROOM_ALLOCATION"})
    void abandonedHoldReturnsItsCodeWithoutRefundingOrSpendingPointsAgain(String path) {
        setPoints(50);
        var voucher = vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(50, "abandoned-hold-code"));
        var hold = createHold(0);
        vouchers.apply(hold.bookingCode(), voucher.code(), CUSTOMER);
        if (path.equals("CANCEL")) bookings.cancelBooking(hold.bookingCode(), CUSTOMER, false);
        else {
            jdbc.update("update bookings set hold_expires_at=? where id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(60)), hold.id());
            switch (path) {
                case "SCHEDULER" -> assertThat(bookings.expireHoldIfPending(hold.id(), Instant.now())).isTrue();
                case "PAYMENT" -> assertThatThrownBy(() -> demoPayments.pay(new DemoPaymentRequest(hold.bookingCode(), "expired-payment", false), CUSTOMER))
                        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("BOOKING_EXPIRED"));
                case "CONFIRM" -> assertThatThrownBy(() -> bookings.confirmBooking(hold.bookingCode()))
                        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("BOOKING_EXPIRED"));
                case "ROOM_ALLOCATION" -> { createHold(0); voucherMaintenance.releaseExpired(CUSTOMER, Instant.now()); }
                default -> throw new IllegalArgumentException(path);
            }
        }
        assertThat(vouchers.wallet(CUSTOMER, 0).content().getFirst().status()).isEqualTo("AVAILABLE");
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isZero();
        assertThat(vouchers.apply(createHold(1).bookingCode(), voucher.code(), CUSTOMER).payableAmount()).isEqualByComparingTo("600000");
    }

    @Test void failedPaymentKeepsCodeForRetryAndSuccessConsumesItOnce() {
        setPoints(50);
        var voucher = vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(50, "failed-payment-code"));
        var hold = createHold(0);
        vouchers.apply(hold.bookingCode(), voucher.code(), CUSTOMER);
        assertThat(demoPayments.pay(new DemoPaymentRequest(hold.bookingCode(), "attempt-failed", true), CUSTOMER).paymentStatus()).isEqualTo("FAILED");
        assertThat(vouchers.wallet(CUSTOMER, 0).content().getFirst().status()).isEqualTo("RESERVED");
        assertThat(demoPayments.pay(new DemoPaymentRequest(hold.bookingCode(), "attempt-succeeded", false), CUSTOMER).paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(vouchers.wallet(CUSTOMER, 0).content().getFirst().status()).isEqualTo("USED");
        assertThat(jdbc.queryForObject("select count(*) from payment_attempts where booking_id=? and status='SUCCEEDED'", Integer.class, hold.id())).isEqualTo(1);
    }

    @Test void voucherHttpOwnershipAndRbacRejectOtherAccountsAndMalformedCodes() throws Exception {
        setPoints(100);
        var result = mvc.perform(post("/api/v1/customers/me/vouchers/redeem").header("Authorization", token(CUSTOMER))
                .contentType("application/json").content("{\"pointsCost\":50,\"idempotencyKey\":\"http-redeem-key\",\"discountAmount\":999999999}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.discountAmount").value(400000)).andReturn();
        String code = json.readTree(result.getResponse().getContentAsString()).get("code").asText();
        var hold = createHold(0);
        mvc.perform(get("/api/v1/customers/me/vouchers").header("Authorization", token(OTHER)))
                .andExpect(jsonPath("$.totalElements").value(0));
        String body = "{\"code\":\"" + code + "\"}";
        mvc.perform(post("/api/v1/bookings/" + hold.bookingCode() + "/voucher").header("Authorization", token(OTHER))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/bookings/" + hold.bookingCode() + "/voucher").header("Authorization", token(OTHER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings/" + hold.bookingCode() + "/voucher").header("Authorization", token(CUSTOMER))
                .contentType("application/json").content("{\"code\":\"abc123\"}")).andExpect(status().isBadRequest());
        for (String url : new String[]{"/api/v1/customers/me/vouchers", "/api/v1/customers/me/vouchers/offers"}) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", token(STAFF))).andExpect(status().isForbidden());
            mvc.perform(get(url).header("Authorization", token(ADMIN))).andExpect(status().isForbidden());
        }
        for (long actor : new long[]{STAFF, ADMIN}) {
            mvc.perform(post("/api/v1/customers/me/vouchers/redeem").header("Authorization", token(actor))
                    .contentType("application/json").content("{\"pointsCost\":50,\"idempotencyKey\":\"blocked-role-key\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/bookings/" + hold.bookingCode() + "/voucher").header("Authorization", token(actor))
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
        }
        assertThat(accounts.summary(CUSTOMER).pointsBalance()).isEqualTo(50);
    }

    @Test void expiredBookingCannotReserveCodeAndOtherCustomerCannotUseItOnTheirOwnBooking() {
        setPoints(50);
        var voucher = vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(50, "owner-only-voucher"));
        var hold = createHold(0);
        jdbc.update("update bookings set customer_id=? where id=?", OTHER, hold.id());
        assertThatThrownBy(() -> vouchers.apply(hold.bookingCode(), voucher.code(), OTHER))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("VOUCHER_NOT_AVAILABLE"));
        jdbc.update("update bookings set customer_id=?,hold_expires_at=? where id=?", CUSTOMER,
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), hold.id());
        assertThatThrownBy(() -> vouchers.apply(hold.bookingCode(), voucher.code(), CUSTOMER))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("BOOKING_EXPIRED"));
        assertThat(vouchers.wallet(CUSTOMER, 0).content().getFirst().status()).isEqualTo("AVAILABLE");
    }

    private void setPoints(long points) { jdbc.update("update customer_profiles set points_balance=? where user_id=?", points, CUSTOMER); }

    private com.hotelpms.booking.dto.BookingResponse createHold(int slot) {
        Instant start = Instant.parse("2032-01-01T05:00:00Z").plus(slot * 3L, ChronoUnit.DAYS);
        return bookings.createBooking(CUSTOMER, new CreateBookingRequest(ROOM_TYPE, (short) 2, start, start.plus(22, ChronoUnit.HOURS)));
    }

    private boolean tryRedeem(String key) {
        try { vouchers.redeem(CUSTOMER, new RedeemVoucherRequest(100, key)); return true; }
        catch (ApiException error) { assertThat(error.getCode()).isEqualTo("INSUFFICIENT_POINTS"); return false; }
    }

    private boolean tryApply(String bookingCode, String code) {
        try { vouchers.apply(bookingCode, code, CUSTOMER); return true; }
        catch (ApiException error) { assertThat(error.getCode()).isEqualTo("VOUCHER_RESERVED"); return false; }
    }

    private <T> CompletableFuture<T> concurrently(CountDownLatch gate, java.util.function.Supplier<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try { if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Gate timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return work.get();
        });
    }

    private CompletableFuture<Boolean> concurrentAward(long id, CountDownLatch gate) {
        return CompletableFuture.supplyAsync(() -> {
            try { if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Gate timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return awards.awardCompletedBooking(id, NOW);
        });
    }

    private String token(long userId) {
        return "Bearer " + jwt.issue(users.findById(userId).orElseThrow()).accessToken();
    }

    private long booking(long customer, String status, String amount, int slot, boolean paid) {
        Instant start = Instant.parse("2031-01-01T05:00:00Z").plus(slot * 3L, ChronoUnit.DAYS);
        long id = jdbc.queryForObject("""
            insert into bookings(booking_code,customer_id,room_type_id,physical_room_id,guest_count,start_at,end_at,
              hold_expires_at,status,base_amount,payable_amount,completed_at)
            values(?,?,?,?,2,?,?,?, ?,?,?,?) returning id
            """, Long.class, "LOYALTY-" + customer + "-" + slot, customer, ROOM_TYPE, ROOM,
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plus(22, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(NOW.plusSeconds(900)), status, new BigDecimal(amount), new BigDecimal(amount),
                status.equals("COMPLETED") ? java.sql.Timestamp.from(NOW) : null);
        if (paid) jdbc.update("""
            insert into payment_attempts(booking_id,provider,idempotency_key,status,requested_amount,succeeded_at)
            values(?,'DEMO',?,'SUCCEEDED',?,?)
            """, id, "award-" + id, new BigDecimal(amount), java.sql.Timestamp.from(NOW.minusSeconds(1)));
        return id;
    }
}
