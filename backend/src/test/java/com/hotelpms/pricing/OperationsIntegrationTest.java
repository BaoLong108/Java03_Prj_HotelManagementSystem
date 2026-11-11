package com.hotelpms.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.service.JwtService;
import com.hotelpms.booking.dto.FloorOverview;
import com.hotelpms.booking.service.StaffReadService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = { "app.booking.scheduler.enabled=false", "app.loyalty.scheduler.enabled=false" })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = OperationsIntegrationTest.PostgresCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class OperationsIntegrationTest {
    private static final long TYPE = 86001, INACTIVE_TYPE = 86002, ADMIN = 86001, STAFF = 86002, CUSTOMER = 86003;
    private static final String RATES = "/api/v1/admin/rate-rules";
    private static final String CALENDAR = "/api/v1/admin/rate-calendar";
    private static final String FLOOR = "/api/v1/staff/floor-overview";
    private static final Instant AS_OF = Instant.parse("2032-02-28T10:00:00Z");
    private static EmbeddedPostgres postgres;

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
        registry.add("app.jwt.secret", () -> "operations-integration-signing-key-at-least-32-characters");
    }

    public static class PostgresCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext context) throws IOException { if (postgres != null) postgres.close(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired StaffReadService reads;

    @BeforeEach
    void seed() {
        for (long id : new long[]{ADMIN, STAFF, CUSTOMER}) {
            jdbc.update("INSERT INTO app_users(id,email,password_hash,full_name) VALUES (?,?,?,?)", id, "ops-" + id + "@test.invalid", "unused", "Private fixture name");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?", id,
                    id == ADMIN ? "ADMIN" : id == STAFF ? "STAFF" : "CUSTOMER");
        }
        jdbc.update("INSERT INTO customer_profiles(user_id,phone_number,tier_code) VALUES (?, '0912345678', 'VIP')", CUSTOMER);
        jdbc.update("INSERT INTO room_types(id,code,name,max_occupancy,base_price_per_night) VALUES (?, 'OPS_QA','Operational QA',2,800000)", TYPE);
        jdbc.update("INSERT INTO room_types(id,code,name,max_occupancy,base_price_per_night,is_active) VALUES (?, 'OPS_HIDDEN','Hidden QA',2,800000,false)", INACTIVE_TYPE);
        room(86011, TYPE, 1, true);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM bookings WHERE customer_id=?", CUSTOMER);
        jdbc.update("DELETE FROM room_blocks WHERE created_by_user_id=?", ADMIN);
        jdbc.update("DELETE FROM room_rate_rules WHERE room_type_id IN (?,?)", TYPE, INACTIVE_TYPE);
        jdbc.update("DELETE FROM physical_rooms WHERE room_type_id IN (?,?)", TYPE, INACTIVE_TYPE);
        jdbc.update("DELETE FROM room_types WHERE id IN (?,?)", TYPE, INACTIVE_TYPE);
        jdbc.update("DELETE FROM customer_profiles WHERE user_id=?", CUSTOMER);
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (?,?,?)", ADMIN, STAFF, CUSTOMER);
        jdbc.update("DELETE FROM app_users WHERE id IN (?,?,?)", ADMIN, STAFF, CUSTOMER);
    }

    @Test
    void overviewGroupsRealRoomsAndKeepsBlocksAndAssignmentsSeparate() {
        room(86012, TYPE, 1, true);
        room(86013, TYPE, 2, true);
        room(86014, TYPE, 2, false);
        room(86015, TYPE, null, true);
        room(86016, INACTIVE_TYPE, 2, true);
        room(86017, TYPE, 2, true);
        room(86018, TYPE, 2, true);
        room(86019, TYPE, 2, true);
        booking(86011, "CONFIRMED", AS_OF, AS_OF.plusSeconds(3600), AS_OF.minusSeconds(60));
        booking(86012, "PENDING_PAYMENT", AS_OF.minusSeconds(60), AS_OF.plusSeconds(3600), AS_OF.plusSeconds(60));
        booking(86015, "PENDING_PAYMENT", AS_OF.minusSeconds(60), AS_OF.plusSeconds(3600), AS_OF);
        booking(86017, "CONFIRMED", AS_OF.plusSeconds(1), AS_OF.plusSeconds(3600), AS_OF);
        booking(86018, "CONFIRMED", AS_OF.minusSeconds(3600), AS_OF, AS_OF);
        booking(86019, "COMPLETED", AS_OF.minusSeconds(60), AS_OF.plusSeconds(3600), AS_OF);
        block(86013, AS_OF, AS_OF.plusSeconds(120));
        block(86014, AS_OF.minusSeconds(60), AS_OF.plusSeconds(120));
        block(86015, AS_OF.minusSeconds(60), AS_OF);

        var overview = reads.floorOverview(AS_OF);
        assertThat(overview.asOf()).isEqualTo(AS_OF);
        var floors = overview.floors().stream().filter(floor -> floor.rooms().stream().anyMatch(room -> room.roomId() >= 86011 && room.roomId() <= 86019)).toList();
        assertThat(floors).extracting(FloorOverview.Floor::floorNumber).containsExactly(1, 2, null);
        var rooms = floors.stream().flatMap(floor -> floor.rooms().stream())
                .collect(Collectors.toMap(FloorOverview.Room::roomId, Function.identity()));
        assertThat(rooms.get(86011L).operationalStatus()).isEqualTo(FloorOverview.Status.ASSIGNED);
        assertThat(rooms.get(86011L).assignment().bookingCode()).isEqualTo("OPS-86011");
        assertThat(rooms.get(86011L).roomTypeName()).isEqualTo("Operational QA");
        assertThat(rooms.get(86012L).operationalStatus()).isEqualTo(FloorOverview.Status.HELD);
        assertThat(rooms.get(86013L).operationalStatus()).isEqualTo(FloorOverview.Status.BLOCKED);
        assertThat(rooms.get(86013L).assignment()).isNull();
        assertThat(rooms.get(86014L).operationalStatus()).isEqualTo(FloorOverview.Status.OUT_OF_SERVICE);
        assertThat(rooms.get(86014L).block().reason()).isEqualTo("QA maintenance");
        assertThat(rooms.get(86016L).operationalStatus()).isEqualTo(FloorOverview.Status.OUT_OF_SERVICE);
        for (long id : new long[]{86015, 86017, 86018, 86019}) {
            assertThat(rooms.get(id).operationalStatus()).isEqualTo(FloorOverview.Status.AVAILABLE);
            assertThat(rooms.get(id).assignment()).isNull();
        }
    }

    @Test
    void floorAccessUsesExistingRolesAndNoCustomerDetails() throws Exception {
        booking(86011, "CONFIRMED", Instant.now().minusSeconds(120), Instant.now().plusSeconds(3600), Instant.now());
        mvc.perform(get(FLOOR)).andExpect(status().isUnauthorized());
        mvc.perform(get(FLOOR).header("Authorization", auth(CUSTOMER))).andExpect(status().isForbidden());
        for (long role : new long[]{STAFF, ADMIN}) {
            var response = mvc.perform(get(FLOOR).header("Authorization", auth(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.asOf").exists()).andExpect(jsonPath("$.floors").isArray()).andReturn();
            assertThat(response.getResponse().getContentAsString()).contains("OPS-86011").doesNotContain(
                    "customerId", "customer", "email", "0912345678", "Private fixture name");
        }
    }

    @Test
    void rateCalendarAndPublicQuoteSharePrecedenceAndLeapDay() throws Exception {
        createRule("WEEKEND", "2032-02-25", "2032-03-02", 900000);
        createRule("SEASONAL", "2032-02-28", "2032-02-29", 1100000);
        createRule("HOLIDAY", "2032-02-29", "2032-03-01", 1400000);
        createRule("OVERRIDE", "2032-02-29", "2032-02-29", 1500000);
        mvc.perform(get(CALENDAR).param("roomTypeId", "" + TYPE).param("month", "2032-02").header("Authorization", auth(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.month").value("2032-02"))
                .andExpect(jsonPath("$.nights.length()").value(29))
                .andExpect(jsonPath("$.nights[26].nightlyRate").value(800000))
                .andExpect(jsonPath("$.nights[27].nightlyRate").value(1100000))
                .andExpect(jsonPath("$.nights[27].weekend").value(true))
                .andExpect(jsonPath("$.nights[28].nightlyRate").value(1500000))
                .andExpect(jsonPath("$.nights[28].kind").value("OVERRIDE"));
        mvc.perform(get("/api/v1/pricing/quote").param("roomTypeId", "" + TYPE).param("guestCount", "2")
                        .param("startAt", "2032-02-27T19:00:00Z").param("endAt", "2032-03-03T04:00:00Z")
                        .header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nights").value(4))
                .andExpect(jsonPath("$.basePricePerNight").value(800000))
                .andExpect(jsonPath("$.baseAmount").value(4800000)).andExpect(jsonPath("$.payableAmount").value(4320000));
    }

    @Test
    void previewEditAndDeactivateKeepExistingHoldPrice() throws Exception {
        long id = createRule("OVERRIDE", "2032-02-28", "2032-02-29", 1200000);
        String changed = draft(TYPE, "OVERRIDE", "2032-02-29", "2032-02-29", 1400000);
        mvc.perform(post(CALENDAR + "/preview").param("ruleId", "" + id).header("Authorization", auth(ADMIN))
                        .contentType("application/json").content(changed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].after.nightlyRate").value(800000)).andExpect(jsonPath("$[1].after.nightlyRate").value(1400000));
        String booking = json.writeValueAsString(Map.of("roomTypeId", TYPE, "guestCount", 2,
                "startAt", "2032-02-28T05:00:00Z", "endAt", "2032-03-01T03:00:00Z"));
        var response = mvc.perform(post("/api/v1/bookings").header("Authorization", auth(CUSTOMER)).contentType("application/json").content(booking))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.baseAmount").value(2400000)).andReturn();
        String code = json.readTree(response.getResponse().getContentAsString()).get("bookingCode").asText();
        mvc.perform(put(RATES + "/" + id).header("Authorization", auth(ADMIN)).contentType("application/json").content(changed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nightlyRate").value(1400000));
        mvc.perform(patch(CALENDAR + "/" + TYPE + "/base-rate").header("Authorization", auth(ADMIN)).contentType("application/json").content("{\"nightlyRate\":\"850000.25\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(patch(RATES + "/" + id + "/deactivate").param("roomTypeId", "" + TYPE).header("Authorization", auth(ADMIN)))
                .andExpect(status().isNoContent());
        mvc.perform(get(CALENDAR).param("roomTypeId", "" + TYPE).param("month", "2032-02").header("Authorization", auth(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nights[28].nightlyRate").value(850000.25));
        mvc.perform(get("/api/v1/bookings/" + code).header("Authorization", auth(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.baseAmount").value(2400000)).andExpect(jsonPath("$.payableAmount").value(2160000));
    }

    @Test
    void sameTypeOverlapConflictsAndConcurrentWritesSerialize() throws Exception {
        String request = draft(TYPE, "SEASONAL", "2032-02-28", "2032-03-01", 1200000);
        String token = auth(ADMIN);
        var first = CompletableFuture.supplyAsync(() -> postRate(request, token));
        var second = CompletableFuture.supplyAsync(() -> postRate(request, token));
        assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        mvc.perform(post(CALENDAR + "/preview").header("Authorization", token).contentType("application/json").content(request))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RATE_RULE_OVERLAP"));
    }

    @ParameterizedTest
    @ValueSource(longs = {STAFF, CUSTOMER})
    void nonAdminsCannotReadOrChangeRates(long actor) throws Exception {
        mvc.perform(get(CALENDAR).param("roomTypeId", "" + TYPE).param("month", "2032-02").header("Authorization", auth(actor)))
                .andExpect(status().isForbidden());
        mvc.perform(post(RATES).header("Authorization", auth(actor)).contentType("application/json")
                .content(draft(TYPE, "OVERRIDE", "2032-02-28", "2032-02-28", 1200000))).andExpect(status().isForbidden());
    }

    @Test
    void rejectGuestInactiveRoomInvalidDateRangePriceAndMonth() throws Exception {
        mvc.perform(get(CALENDAR)).andExpect(status().isUnauthorized());
        for (String request : new String[]{draft(TYPE, "OVERRIDE", "2032-02-28", "2032-02-28", -1),
                draft(TYPE, "SEASONAL", "2032-03-01", "2032-02-28", 800000),
                draft(TYPE, "SEASONAL", "2032-03-01", "2034-02-28", 800000),
                draft(TYPE, "WEEKEND", "2032-02-27", "2032-02-27", 800000)}) {
            assertThat(postRate(request, auth(ADMIN))).isEqualTo(400);
        }
        assertThat(postRate(draft(INACTIVE_TYPE, "OVERRIDE", "2032-02-28", "2032-02-28", 800000), auth(ADMIN))).isEqualTo(404);
        mvc.perform(get(CALENDAR).param("roomTypeId", "" + TYPE).param("month", "2032-13").header("Authorization", auth(ADMIN)))
                .andExpect(status().isBadRequest());
    }

    private int postRate(String request, String token) {
        try {
            return mvc.perform(post(RATES).header("Authorization", token).contentType("application/json").content(request))
                    .andReturn().getResponse().getStatus();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private long createRule(String kind, String from, String to, long rate) throws Exception {
        var result = mvc.perform(post(RATES).header("Authorization", auth(ADMIN)).contentType("application/json")
                .content(draft(TYPE, kind, from, to, rate))).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String draft(long type, String kind, String from, String to, long rate) {
        return json.writeValueAsString(Map.of("roomTypeId", type, "kind", kind, "startsOn", from, "endsOn", to, "nightlyRate", Long.toString(rate)));
    }

    private String auth(long id) { return "Bearer " + jwt.issue(users.findById(id).orElseThrow()).accessToken(); }

    private void room(long id, long type, Integer floor, boolean active) {
        jdbc.update("INSERT INTO physical_rooms(id,room_type_id,room_number,floor_number,is_active) VALUES (?,?,?,?,?)", id, type, "OPS-" + id, floor, active);
    }

    private void block(long room, Instant from, Instant to) {
        jdbc.update("INSERT INTO room_blocks(physical_room_id,start_at,end_at,reason,created_by_user_id) VALUES (?,?,?,?,?)",
                room, Timestamp.from(from), Timestamp.from(to), "QA maintenance", ADMIN);
    }

    private void booking(long room, String status, Instant from, Instant to, Instant expires) {
        jdbc.update("""
                INSERT INTO bookings(booking_code,customer_id,room_type_id,physical_room_id,guest_count,
                    start_at,end_at,hold_expires_at,status,base_amount,payable_amount)
                VALUES (?,?,?,?,2,?,?,?,?,800000,800000)
                """, "OPS-" + room, CUSTOMER, TYPE, room, Timestamp.from(from), Timestamp.from(to), Timestamp.from(expires), status);
    }
}
