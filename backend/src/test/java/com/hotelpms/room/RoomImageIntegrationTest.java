package com.hotelpms.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.service.JwtService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = RoomImageIntegrationTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class RoomImageIntegrationTest {
    private static final long TYPE = 82001L, OTHER_TYPE = 82002L, ADMIN = 82001L, STAFF = 82002L, CUSTOMER = 82003L;
    private static EmbeddedPostgres postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) throws IOException {
        String external = System.getenv("TEST_DB_URL");
        if (external == null || external.isBlank()) {
            postgres = EmbeddedPostgres.builder().setPort(0).start();
            p.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
            p.add("spring.datasource.username", () -> "postgres"); p.add("spring.datasource.password", () -> "postgres");
        } else {
            p.add("spring.datasource.url", () -> external);
            p.add("spring.datasource.username", () -> System.getenv("TEST_DB_USER"));
            p.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        }
        p.add("app.jwt.secret", () -> "room-gallery-test-secret-at-least-thirty-two-characters");
        p.add("app.booking.scheduler.enabled", () -> "false");
        p.add("app.loyalty.scheduler.enabled", () -> "false");
    }
    public static class PostgresCleanupListener extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext context) throws IOException { if (postgres != null) postgres.close(); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired com.hotelpms.room.service.RoomImageService images;
    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM bookings WHERE customer_id=?", CUSTOMER);
        jdbc.update("DELETE FROM room_type_images WHERE room_type_id IN (?,?)", TYPE, OTHER_TYPE);
        jdbc.update("DELETE FROM physical_rooms WHERE room_type_id IN (?,?)", TYPE, OTHER_TYPE);
        for (long id : new long[]{TYPE, OTHER_TYPE}) {
            jdbc.update("""
                INSERT INTO room_types(id,code,name,max_occupancy,base_price_per_night) VALUES(?,?,?,2,950000)
                ON CONFLICT(id) DO UPDATE SET is_active=true,base_price_per_night=950000
                """, id, "GALLERY_" + id, "Gallery Test " + id);
        }
        for (long id : new long[]{ADMIN, STAFF, CUSTOMER}) {
            jdbc.update("INSERT INTO app_users(id,email,password_hash,full_name) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                    id, "gallery-" + id + "@test.invalid", "unused", "Gallery Test");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=? ON CONFLICT DO NOTHING",
                    id, id == ADMIN ? "ADMIN" : id == STAFF ? "STAFF" : "CUSTOMER");
        }
        jdbc.update("INSERT INTO customer_profiles(user_id) VALUES(?) ON CONFLICT DO NOTHING", CUSTOMER);
    }
    private String token(long id) { return "Bearer " + jwt.issue(users.findById(id).orElseThrow()).accessToken(); }
    private String revision() {
        return images.metadata(List.of(TYPE)).getOrDefault(TYPE, List.of()).stream()
                .map(image -> image.id() + ":" + image.sortOrder() + ":" + image.cover()).collect(java.util.stream.Collectors.joining("|"));
    }
    private MockMultipartHttpServletRequestBuilder edit(java.util.Map<String,Object> body) throws IOException {
        return multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/admin/rooms/types/" + TYPE + "/images")
                .file(new MockMultipartFile("gallery", "gallery.json", "application/json", json.writeValueAsBytes(body)));
    }
    @Test void gallerySupportsRetainAppendReplaceDeleteCoverAndOrderWithoutChangingInventory() throws Exception {
        mvc.perform(request(TYPE, "MANAGE-1", 2, 0).header("Authorization", token(ADMIN))).andExpect(status().isCreated());
        var before = images.metadata(List.of(TYPE)).get(TYPE); long retained = before.get(1).id(); long removed = before.get(0).id();
        var body = java.util.Map.<String,Object>of("items", List.of(java.util.Map.of("imageId", retained), java.util.Map.of("uploadIndex",0)), "coverIndex",1,"expectedRevision",revision());
        mvc.perform(edit(body).file(new MockMultipartFile("images", "new.png", "image/png", png())).header("Authorization",token(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(retained)).andExpect(jsonPath("$[1].cover").value(true));
        var after = images.metadata(List.of(TYPE)).get(TYPE);
        assertThat(after).hasSize(2); assertThat(after.getFirst().id()).isEqualTo(retained);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM physical_rooms WHERE room_type_id=?",Integer.class,TYPE)).isEqualTo(1);
        mvc.perform(get("/api/v1/rooms/"+TYPE)).andExpect(jsonPath("$.coverImageUrl").value(after.get(1).url()));
        mvc.perform(get(after.get(1).url()).param("size","thumb")).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        mvc.perform(get("/api/v1/rooms/"+TYPE+"/images/"+removed)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/rooms/"+TYPE+"/images/"+retained)).andExpect(status().isOk());
        var reorder = java.util.Map.<String,Object>of("items", List.of(java.util.Map.of("imageId", after.get(1).id()), java.util.Map.of("imageId", retained)),"coverIndex",0,"expectedRevision",revision());
        mvc.perform(edit(reorder).header("Authorization",token(ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$[0].cover").value(true));
    }
    @Test void galleryRejectsStaleEditsInvalidOwnershipAndUnauthorisedWriters() throws Exception {
        mvc.perform(request(TYPE,"EDIT-ONE",1,0).header("Authorization",token(ADMIN))).andExpect(status().isCreated());
        long id=images.metadata(List.of(TYPE)).get(TYPE).getFirst().id();
        var valid=java.util.Map.<String,Object>of("items",List.of(java.util.Map.of("imageId",id)),"coverIndex",0,"expectedRevision",revision());
        for(long actor:new long[]{STAFF,CUSTOMER}) mvc.perform(edit(valid).header("Authorization",token(actor))).andExpect(status().isForbidden());
        mvc.perform(edit(valid)).andExpect(status().isUnauthorized());
        var stale=java.util.Map.<String,Object>of("items",List.of(java.util.Map.of("imageId",id)),"coverIndex",0,"expectedRevision","outdated");
        mvc.perform(edit(stale).header("Authorization",token(ADMIN))).andExpect(status().isConflict());
        var foreign=java.util.Map.<String,Object>of("items",List.of(java.util.Map.of("imageId",Long.MAX_VALUE)),"coverIndex",0,"expectedRevision",revision());
        mvc.perform(edit(foreign).header("Authorization",token(ADMIN))).andExpect(status().isBadRequest());
        assertThat(images.metadata(List.of(TYPE)).get(TYPE).getFirst().id()).isEqualTo(id);
    }

    @Test void simultaneousGalleryEditsAcceptExactlyOneRevision() throws Exception {
        mvc.perform(request(TYPE, "EDIT-RACE", 3, 0).header("Authorization", token(ADMIN))).andExpect(status().isCreated());
        var original = images.metadata(List.of(TYPE)).get(TYPE);
        var items = original.stream().map(image -> java.util.Map.of("imageId", image.id())).toList();
        String revision = revision();
        String auth = token(ADMIN);
        var start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> editCover(items, revision, 1, auth, start));
        var second = CompletableFuture.supplyAsync(() -> editCover(items, revision, 2, auth, start));
        start.countDown();
        assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        var gallery = images.metadata(List.of(TYPE)).get(TYPE);
        assertThat(gallery).extracting(com.hotelpms.room.dto.RoomImageResponse::id)
                .containsExactlyElementsOf(original.stream().map(com.hotelpms.room.dto.RoomImageResponse::id).toList());
        assertThat(gallery.stream().filter(com.hotelpms.room.dto.RoomImageResponse::cover).toList()).hasSize(1);
        assertThat(gallery.getFirst().cover()).isFalse();
    }

    private int editCover(List<java.util.Map<String, Long>> items, String revision, int coverIndex, String auth, CountDownLatch start) {
        try {
            start.await();
            var body = java.util.Map.<String,Object>of("items", items, "coverIndex", coverIndex, "expectedRevision", revision);
            return mvc.perform(edit(body).header("Authorization", auth)).andReturn().getResponse().getStatus();
        } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
    }
    @Test void invalidGalleryUploadRollsBackAndPreservesExistingCover() throws Exception {
        mvc.perform(request(TYPE,"EDIT-BAD",1,0).header("Authorization",token(ADMIN))).andExpect(status().isCreated());
        String original=revision();
        var body=java.util.Map.<String,Object>of("items",List.of(java.util.Map.of("uploadIndex",0)),"coverIndex",0,"expectedRevision",original);
        mvc.perform(edit(body).file(new MockMultipartFile("images","fake.png","image/png","<script>bad</script>".getBytes()))
                .header("Authorization",token(ADMIN))).andExpect(status().isBadRequest());
        assertThat(revision()).isEqualTo(original);
    }

    @Test void physicalRoomsCanBeCreatedWithoutImagesAndShareTheTypeGallery() throws Exception {
        String roomPath = "/api/v1/admin/rooms/physical";
        byte[] room = json.writeValueAsBytes(java.util.Map.of("roomTypeId", TYPE, "roomNumber", "NO-IMAGE", "floorNumber", 3));
        mvc.perform(post(roomPath).contentType("application/json").content(room).header("Authorization", token(STAFF)))
                .andExpect(status().isForbidden());
        mvc.perform(post(roomPath).contentType("application/json").content(room).header("Authorization", token(ADMIN)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true));
        mvc.perform(get("/api/v1/rooms/" + TYPE)).andExpect(jsonPath("$.images").isEmpty());

        var upload = java.util.Map.<String,Object>of("items", List.of(java.util.Map.of("uploadIndex", 0)), "coverIndex", 0, "expectedRevision", "");
        mvc.perform(edit(upload).file(new MockMultipartFile("images", "new.png", "image/png", png())).header("Authorization", token(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].cover").value(true));
        String revisionAfterUpload = revision();
        byte[] hidden = json.writeValueAsBytes(java.util.Map.of("roomTypeId", TYPE, "roomNumber", "SHARED-IMAGE", "floorNumber", 3, "active", false));
        mvc.perform(post(roomPath).contentType("application/json").content(hidden).header("Authorization", token(ADMIN)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(false));
        assertThat(revision()).isEqualTo(revisionAfterUpload);

        var clear = java.util.Map.<String,Object>of("items", List.of(), "coverIndex", -1, "expectedRevision", revisionAfterUpload);
        mvc.perform(edit(clear).header("Authorization", token(ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/rooms/" + TYPE)).andExpect(jsonPath("$.images").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM physical_rooms WHERE room_type_id=?", Integer.class, TYPE)).isEqualTo(2);
    }
    @Test void amenitiesRoundTripFromAdminToPublicWithoutInventingDefaults() throws Exception {
        mvc.perform(put("/api/v1/admin/rooms/types/"+TYPE).header("Authorization",token(ADMIN)).contentType("application/json")
                .content(json.writeValueAsBytes(java.util.Map.of("code","GALLERY_"+TYPE,"name","Gallery Test","maxOccupancy",2,
                        "basePricePerNight",950000,"amenities",List.of("Wi-Fi","Ban công")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amenities[0]").value("Wi-Fi"));
        mvc.perform(get("/api/v1/rooms/"+TYPE)).andExpect(status().isOk()).andExpect(jsonPath("$.amenities[1]").value("Ban công"));
        // Legacy price-only edits preserve stored amenities when the optional field is absent.
        mvc.perform(put("/api/v1/admin/rooms/types/"+TYPE).header("Authorization",token(ADMIN)).contentType("application/json")
                .content(json.writeValueAsBytes(java.util.Map.of("code","GALLERY_"+TYPE,"name","Gallery Test","maxOccupancy",2,"basePricePerNight",960000))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amenities.length()").value(2));
    }
    private static byte[] png() throws IOException {
        var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB), "png", output); return output.toByteArray();
    }
    private MockMultipartHttpServletRequestBuilder request(long type, String number, int count, int cover) throws IOException {
        var request = multipart("/api/v1/admin/rooms/physical")
                .file(new MockMultipartFile("room", "room.json", "application/json",
                        ("{\"roomTypeId\":" + type + ",\"roomNumber\":\"" + number + "\",\"floorNumber\":2}").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("coverIndex", String.valueOf(cover));
        for (int i = 0; i < count; i++) request.file(new MockMultipartFile("images", "photo" + i + ".png", "image/png", png()));
        return request;
    }
    @Test void adminCreatesRoomWithFiveImagesAndPublicCover() throws Exception {
        mvc.perform(request(TYPE, " IMG-101 ", 5, 3).header("Authorization", token(ADMIN)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.roomNumber").value("IMG-101"));
        var result = mvc.perform(get("/api/v1/rooms/" + TYPE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(5)).andExpect(jsonPath("$.images[3].cover").value(true))
                .andExpect(jsonPath("$.basePricePerNight").value(950000)).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        String url = body.get("coverImageUrl").asText();
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(png()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_type_images WHERE room_type_id=? AND is_cover", Long.class, TYPE)).isEqualTo(1);
        var available = mvc.perform(get("/api/v1/rooms/availability").param("startAt", "2035-04-01T05:00:00Z")
                .param("endAt", "2035-04-02T03:00:00Z").param("guestCount", "2"))
                .andExpect(status().isOk()).andReturn();
        var availableTypes = json.readTree(available.getResponse().getContentAsString());
        boolean found = false;
        for (var type : availableTypes) {
            if (type.get("id").asLong() == TYPE) { assertThat(type.get("coverImageUrl").asText()).isEqualTo(url); found = true; }
        }
        assertThat(found).as("Created type must be available: %s", availableTypes).isTrue();
    }
    @ParameterizedTest @ValueSource(longs = {STAFF, CUSTOMER})
    void nonAdminCannotCreateOrChangePrice(long actor) throws Exception {
        mvc.perform(request(TYPE, "DENIED", 1, 0).header("Authorization", token(actor))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/rooms/types/" + TYPE).header("Authorization", token(actor))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM physical_rooms WHERE room_type_id=?", Long.class, TYPE)).isZero();
    }
    @Test void guestCannotCreate() throws Exception { mvc.perform(request(TYPE, "GUEST", 1, 0)).andExpect(status().isUnauthorized()); }
    @ParameterizedTest @ValueSource(ints = {0, 6})
    void imageCountIsValidated(int count) throws Exception {
        mvc.perform(request(TYPE, "BAD-COUNT", count, 0).header("Authorization", token(ADMIN))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM physical_rooms WHERE room_type_id=?", Long.class, TYPE)).isZero();
    }
    @Test void invalidCoverRollsBackCreation() throws Exception {
        mvc.perform(request(TYPE, "BAD-COVER", 1, 1).header("Authorization", token(ADMIN))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM physical_rooms WHERE room_type_id=?", Long.class, TYPE)).isZero();
    }
    @Test void rejectsSpoofedImageAndOversizedImage() throws Exception {
        for (byte[] data : List.of("<svg onload='alert(1)'/>".getBytes(), new byte[5 * 1024 * 1024 + 1])) {
            mvc.perform(request(TYPE, "BAD-FILE", 0, 0).file(new MockMultipartFile("images", "fake.png", "image/png", data))
                    .header("Authorization", token(ADMIN))).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_type_images WHERE room_type_id=?", Long.class, TYPE)).isZero();
    }
    @Test void requiresExplicitConsentBeforeReplacingExistingGallery() throws Exception {
        mvc.perform(request(TYPE, "ORIGINAL", 2, 0).header("Authorization", token(ADMIN))).andExpect(status().isCreated());
        long old = jdbc.queryForObject("SELECT id FROM room_type_images WHERE room_type_id=? AND is_cover", Long.class, TYPE);
        mvc.perform(request(TYPE, "REPLACE", 1, 0).header("Authorization", token(ADMIN))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM physical_rooms WHERE room_type_id=?", Long.class, TYPE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_type_images WHERE id=?", Long.class, old)).isEqualTo(1);
        mvc.perform(request(TYPE, "REPLACE", 1, 0).param("replaceGallery", "true").header("Authorization", token(ADMIN))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_type_images WHERE room_type_id=?", Long.class, TYPE)).isEqualTo(1);
        mvc.perform(get("/api/v1/rooms/" + TYPE + "/images/" + old)).andExpect(status().isNotFound());
    }
    @Test void concurrentDuplicatesAcrossDifferentTypesAreRejectedByDatabase() throws Exception {
        String auth = token(ADMIN);
        var start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> race(TYPE, " CASE-ROOM ", auth, start));
        var second = CompletableFuture.supplyAsync(() -> race(OTHER_TYPE, "case-room", auth, start));
        start.countDown();
        assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM physical_rooms WHERE lower(btrim(room_number))='case-room'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_type_images WHERE room_type_id IN (?,?)", Long.class, TYPE, OTHER_TYPE)).isEqualTo(1);
    }
    private int race(long type, String number, String auth, CountDownLatch start) {
        try { start.await(); return mvc.perform(request(type, number, 1, 0).header("Authorization", auth)).andReturn().getResponse().getStatus(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    @Test void duplicateJsonAndUpdateAlsoRejectNormalizedNumbers() throws Exception {
        mvc.perform(request(TYPE, "DUP-01", 1, 0).header("Authorization", token(ADMIN))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/rooms/physical").header("Authorization", token(ADMIN)).contentType("application/json")
                .content("{\"roomTypeId\":" + TYPE + ",\"roomNumber\":\" dup-01 \"}"))
                .andExpect(status().isConflict());
        jdbc.update("INSERT INTO physical_rooms(room_type_id,room_number) VALUES(?,'OTHER-01')", TYPE);
        long id = jdbc.queryForObject("SELECT id FROM physical_rooms WHERE room_number='OTHER-01'", Long.class);
        mvc.perform(put("/api/v1/admin/rooms/physical/" + id).header("Authorization", token(ADMIN)).contentType("application/json")
                .content("{\"roomNumber\":\" dup-01 \"}")).andExpect(status().isConflict());
    }
    @Test void inactiveTypeAndCrossTypeImageCannotBeViewed() throws Exception {
        mvc.perform(request(TYPE, "HIDE", 1, 0).header("Authorization", token(ADMIN))).andExpect(status().isCreated());
        long image = jdbc.queryForObject("SELECT id FROM room_type_images WHERE room_type_id=?", Long.class, TYPE);
        mvc.perform(get("/api/v1/rooms/" + OTHER_TYPE + "/images/" + image)).andExpect(status().isNotFound());
        jdbc.update("UPDATE room_types SET is_active=false WHERE id=?", TYPE);
        mvc.perform(get("/api/v1/rooms/" + TYPE + "/images/" + image)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/rooms/" + TYPE)).andExpect(status().isNotFound());
        mvc.perform(request(TYPE, "HIDDEN-TYPE", 1, 0).header("Authorization", token(ADMIN))).andExpect(status().isNotFound());
    }
    @Test void pricingReadsAdminConfiguredRoomTypePrice() throws Exception {
        mvc.perform(put("/api/v1/admin/rooms/types/" + TYPE).header("Authorization", token(ADMIN)).contentType("application/json")
                .content("{\"code\":\"GALLERY_82001\",\"name\":\"Gallery Test\",\"maxOccupancy\":2,\"basePricePerNight\":1230000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.basePricePerNight").value(1230000));
        mvc.perform(get("/api/v1/pricing/quote").param("roomTypeId", "" + TYPE).param("guestCount", "2")
                .param("startAt", "2035-04-01T05:00:00Z").param("endAt", "2035-04-03T03:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.baseAmount").value(2460000));
    }

    @Test void existingBookingKeepsPriceSnapshotAfterAdminChangesRate() throws Exception {
        mvc.perform(request(TYPE, "SNAPSHOT", 1, 0).header("Authorization", token(ADMIN))).andExpect(status().isCreated());
        var created = mvc.perform(post("/api/v1/bookings").header("Authorization", token(CUSTOMER)).contentType("application/json")
                .content("{\"roomTypeId\":" + TYPE + ",\"guestCount\":2,\"startAt\":\"2035-06-01T05:00:00Z\",\"endAt\":\"2035-06-03T03:00:00Z\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.baseAmount").value(1900000)).andReturn();
        String code = json.readTree(created.getResponse().getContentAsString()).get("bookingCode").asText();
        mvc.perform(put("/api/v1/admin/rooms/types/" + TYPE).header("Authorization", token(ADMIN)).contentType("application/json")
                .content("{\"code\":\"GALLERY_82001\",\"name\":\"Gallery Test\",\"maxOccupancy\":2,\"basePricePerNight\":1500000}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/" + code).header("Authorization", token(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.baseAmount").value(1900000));
        mvc.perform(get("/api/v1/pricing/quote").param("roomTypeId", "" + TYPE).param("guestCount", "2")
                .param("startAt", "2035-06-01T05:00:00Z").param("endAt", "2035-06-03T03:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.baseAmount").value(3000000));
    }

    @Test void acceptsJpegPixelsRegardlessOfUntrustedFilename() throws Exception {
        var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "jpeg", output);
        mvc.perform(request(TYPE, "JPEG", 0, 0).file(new MockMultipartFile("images", "../../unsafe.html", "text/html", output.toByteArray()))
                .header("Authorization", token(ADMIN))).andExpect(status().isCreated());
        long id = jdbc.queryForObject("SELECT id FROM room_type_images WHERE room_type_id=?", Long.class, TYPE);
        mvc.perform(get("/api/v1/rooms/" + TYPE + "/images/" + id)).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
    }
}
