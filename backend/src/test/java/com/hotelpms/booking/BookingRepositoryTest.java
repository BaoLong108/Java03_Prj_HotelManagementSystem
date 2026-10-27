package com.hotelpms.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.booking.entity.Booking;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.booking.repository.BookingRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = BookingRepositoryTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class BookingRepositoryTest {

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
        properties.add("app.demo-seed.password", () -> "TestDemo123!");
    }

    public static class PostgresCleanupListener extends AbstractTestExecutionListener {
        @Override
        public int getOrder() { return 1000; }

        @Override
        public void afterTestClass(TestContext context) throws IOException {
            if (postgres != null) postgres.close();
        }
    }

    @Autowired private BookingRepository bookingRepository;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long customerId;
    private Long roomTypeId;
    private Long physicalRoomId101;
    private Long physicalRoomId102;

    @BeforeEach
    void setUpTestData() {
        // Clean bookings created in previous tests
        bookingRepository.deleteAll();

        // Customer from seeder
        var profile = customerProfileRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Demo customer profile not found"));
        this.customerId = profile.getUserId();

        // Seed room_types and physical_rooms for test
        this.roomTypeId = 1L;
        this.physicalRoomId101 = 101L;
        this.physicalRoomId102 = 102L;

        jdbcTemplate.update("""
            INSERT INTO room_types (id, code, name, description, max_occupancy, base_price_per_night)
            VALUES (?, 'TEST_DLX', 'Test Deluxe', 'Room for integration test', 2, 1200000.00)
            ON CONFLICT (id) DO UPDATE SET base_price_per_night = 1200000.00
        """, roomTypeId);

        jdbcTemplate.update("""
            INSERT INTO physical_rooms (id, room_type_id, room_number, floor_number)
            VALUES (?, ?, 'P101', 1)
            ON CONFLICT (id) DO UPDATE SET room_number = 'P101'
        """, physicalRoomId101, roomTypeId);

        jdbcTemplate.update("""
            INSERT INTO physical_rooms (id, room_type_id, room_number, floor_number)
            VALUES (?, ?, 'P102', 1)
            ON CONFLICT (id) DO UPDATE SET room_number = 'P102'
        """, physicalRoomId102, roomTypeId);
    }

    @Test
    void testSaveAndFindBookingByCode() {
        Instant now = Instant.now();
        Instant startAt = now.plus(1, ChronoUnit.DAYS);
        Instant endAt = now.plus(3, ChronoUnit.DAYS);
        Instant holdExpiresAt = now.plus(15, ChronoUnit.MINUTES);

        Booking booking = new Booking(
                "BK-TEST-001",
                customerId,
                roomTypeId,
                physicalRoomId101,
                (short) 2,
                startAt,
                endAt,
                holdExpiresAt,
                new BigDecimal("2400000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("2400000.00")
        );

        Booking saved = bookingRepository.save(booking);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);

        Optional<Booking> found = bookingRepository.findByBookingCode("BK-TEST-001");
        assertThat(found).isPresent();
        assertThat(found.get().getPayableAmount()).isEqualByComparingTo(new BigDecimal("2400000.00"));
        assertThat(found.get().getCustomerId()).isEqualTo(customerId);
    }

    @Test
    void testStateTransitions() {
        Instant now = Instant.now();
        Booking booking = new Booking(
                "BK-TRANS-01",
                customerId,
                roomTypeId,
                physicalRoomId101,
                (short) 2,
                now.plus(1, ChronoUnit.DAYS),
                now.plus(2, ChronoUnit.DAYS),
                now.plus(15, ChronoUnit.MINUTES),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1000000.00")
        );

        Booking saved = bookingRepository.save(booking);

        // Confirm
        saved.confirm(now);
        Booking confirmed = bookingRepository.save(saved);
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();

        // Complete
        confirmed.complete(now.plus(2, ChronoUnit.DAYS));
        Booking completed = bookingRepository.save(confirmed);
        assertThat(completed.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test
    void testFindByStatusAndHoldExpiresAtBefore() {
        Instant now = Instant.now();

        // Expired hold (holdExpiresAt is 5 minutes in the past)
        Booking expiredHold = new Booking(
                "BK-EXP-01",
                customerId,
                roomTypeId,
                physicalRoomId101,
                (short) 1,
                now.plus(1, ChronoUnit.DAYS),
                now.plus(2, ChronoUnit.DAYS),
                now.minus(5, ChronoUnit.MINUTES),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1000000.00")
        );

        // Active hold (holdExpiresAt is 10 minutes in the future)
        Booking activeHold = new Booking(
                "BK-ACT-01",
                customerId,
                roomTypeId,
                physicalRoomId102,
                (short) 1,
                now.plus(1, ChronoUnit.DAYS),
                now.plus(2, ChronoUnit.DAYS),
                now.plus(10, ChronoUnit.MINUTES),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1000000.00")
        );

        bookingRepository.saveAll(List.of(expiredHold, activeHold));

        List<Booking> expiredToSweep = bookingRepository.findByStatusAndHoldExpiresAtBefore(
                BookingStatus.PENDING_PAYMENT,
                now
        );

        assertThat(expiredToSweep).hasSize(1);
        assertThat(expiredToSweep.getFirst().getBookingCode()).isEqualTo("BK-EXP-01");
    }

    @Test
    void testFindByStatusAndEndAtLessThanEqual() {
        Instant now = Instant.now();

        // Confirmed booking with endAt in the past (already departed)
        Booking pastConfirmed = new Booking(
                "BK-PAST-01",
                customerId,
                roomTypeId,
                physicalRoomId101,
                (short) 1,
                now.minus(3, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.DAYS),
                now.minus(3, ChronoUnit.DAYS).plus(15, ChronoUnit.MINUTES),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1000000.00")
        );
        pastConfirmed.confirm(now.minus(3, ChronoUnit.DAYS));

        // Confirmed booking with endAt in the future (still active)
        Booking futureConfirmed = new Booking(
                "BK-FUT-01",
                customerId,
                roomTypeId,
                physicalRoomId102,
                (short) 1,
                now.plus(1, ChronoUnit.DAYS),
                now.plus(3, ChronoUnit.DAYS),
                now.plus(15, ChronoUnit.MINUTES),
                new BigDecimal("2000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("2000000.00")
        );
        futureConfirmed.confirm(now);

        // Pending payment booking with endAt in the past (should NOT match because not CONFIRMED)
        Booking pastPending = new Booking(
                "BK-PEND-PAST-01",
                customerId,
                roomTypeId,
                physicalRoomId101,
                (short) 1,
                now.minus(6, ChronoUnit.DAYS),
                now.minus(4, ChronoUnit.DAYS),
                now.minus(6, ChronoUnit.DAYS).plus(15, ChronoUnit.MINUTES),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1000000.00")
        );

        bookingRepository.saveAll(List.of(pastConfirmed, futureConfirmed, pastPending));

        List<Booking> departedConfirmed = bookingRepository.findByStatusAndEndAtLessThanEqual(
                BookingStatus.CONFIRMED,
                now
        );

        assertThat(departedConfirmed).hasSize(1);
        assertThat(departedConfirmed.getFirst().getBookingCode()).isEqualTo("BK-PAST-01");
    }

    @Test
    void testActiveBookingOverlapDetection() {
        Instant now = Instant.now();
        Instant day1Start = now.plus(1, ChronoUnit.DAYS);
        Instant day3End = now.plus(3, ChronoUnit.DAYS);

        // Active confirmed booking for room 101 from Day 1 to Day 3
        Booking confirmedBooking = new Booking(
                "BK-CONF-01",
                customerId,
                roomTypeId,
                physicalRoomId101,
                (short) 2,
                day1Start,
                day3End,
                now.plus(15, ChronoUnit.MINUTES),
                new BigDecimal("2000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("2000000.00")
        );
        confirmedBooking.confirm(now);
        bookingRepository.save(confirmedBooking);

        // 1. Overlapping range (Day 2 to Day 4) -> Should report overlap
        boolean overlaps = bookingRepository.hasActiveBookingOverlap(
                physicalRoomId101,
                now.plus(2, ChronoUnit.DAYS),
                now.plus(4, ChronoUnit.DAYS),
                now
        );
        assertThat(overlaps).isTrue();

        // 2. Different room (102) -> Should report no overlap
        boolean room102Overlaps = bookingRepository.hasActiveBookingOverlap(
                physicalRoomId102,
                now.plus(2, ChronoUnit.DAYS),
                now.plus(4, ChronoUnit.DAYS),
                now
        );
        assertThat(room102Overlaps).isFalse();

        // 3. Adjacent range (Day 3 to Day 5, check-in starts at Day 3 check-out) -> Half-open [startAt, endAt) means NO overlap!
        boolean adjacentOverlaps = bookingRepository.hasActiveBookingOverlap(
                physicalRoomId101,
                day3End,
                now.plus(5, ChronoUnit.DAYS),
                now
        );
        assertThat(adjacentOverlaps).isFalse();
    }

    @Test
    void testFindOccupiedPhysicalRoomIds() {
        Instant now = Instant.now();
        Instant day1Start = now.plus(1, ChronoUnit.DAYS);
        Instant day3End = now.plus(3, ChronoUnit.DAYS);

        Booking booking101 = new Booking(
                "BK-OCC-101",
                customerId,
                roomTypeId,
                physicalRoomId101,
                (short) 1,
                day1Start,
                day3End,
                now.plus(15, ChronoUnit.MINUTES),
                new BigDecimal("2000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("2000000.00")
        );
        booking101.confirm(now);
        bookingRepository.save(booking101);

        List<Long> occupiedRooms = bookingRepository.findOccupiedPhysicalRoomIds(
                roomTypeId,
                now.plus(2, ChronoUnit.DAYS),
                now.plus(4, ChronoUnit.DAYS),
                now
        );

        assertThat(occupiedRooms).containsExactly(physicalRoomId101);
    }

    @Test
    void testPostgresExclusionConstraintRejectsOverlappingBookings() {
        Instant now = Instant.now();
        Instant start = now.plus(1, ChronoUnit.DAYS);
        Instant end = now.plus(3, ChronoUnit.DAYS);

        Booking first = new Booking(
                "BK-EXCL-1",
                customerId,
                roomTypeId,
                physicalRoomId101,
                (short) 1,
                start,
                end,
                now.plus(15, ChronoUnit.MINUTES),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1000000.00")
        );
        bookingRepository.saveAndFlush(first);

        // Attempt to save a second booking overlapping the exact same room and dates
        Booking second = new Booking(
                "BK-EXCL-2",
                customerId,
                roomTypeId,
                physicalRoomId101,
                (short) 1,
                start.plus(Duration.ofHours(1)),
                end.plus(Duration.ofHours(1)),
                now.plus(15, ChronoUnit.MINUTES),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1000000.00")
        );

        // PostgreSQL exclusion constraint ex_bookings_room_overlap MUST reject this
        assertThatThrownBy(() -> bookingRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
