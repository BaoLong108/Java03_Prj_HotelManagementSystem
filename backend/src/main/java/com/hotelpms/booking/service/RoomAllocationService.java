package com.hotelpms.booking.service;

import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.loyalty.VoucherService;
import com.hotelpms.room.service.RoomAvailabilityService;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomAllocationService {

    public record RoomTypeInfo(
            Long id,
            String name,
            short maxOccupancy,
            BigDecimal basePricePerNight,
            boolean active
    ) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final BookingRepository bookingRepository;
    private final RoomAvailabilityService availability;
    private final VoucherService vouchers;

    public RoomAllocationService(JdbcTemplate jdbcTemplate, BookingRepository bookingRepository,
            RoomAvailabilityService availability, VoucherService vouchers) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookingRepository = bookingRepository;
        this.availability = availability;
        this.vouchers = vouchers;
    }

    public List<Long> findAvailableRoomTypeIds(Instant startAt, Instant endAt, Instant now) {
        return availability.searchAt(startAt, endAt, 1, now).stream()
                .map(RoomAvailabilityService.AvailableRoomType::id).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPhysicalRoom(Long roomId) {
        var locked = jdbcTemplate.queryForList("SELECT id FROM physical_rooms WHERE id=? FOR UPDATE", Long.class, roomId);
        if (locked.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,
                "PHYSICAL_ROOM_NOT_FOUND", "Không tìm thấy phòng");
    }

    public Optional<RoomTypeInfo> getRoomTypeInfo(Long roomTypeId) {
        String sql = """
            SELECT id, name, max_occupancy, base_price_per_night, is_active
            FROM room_types
            WHERE id = ?
        """;
        List<RoomTypeInfo> results = jdbcTemplate.query(sql, (rs, rowNum) -> new RoomTypeInfo(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getShort("max_occupancy"),
                rs.getBigDecimal("base_price_per_night"),
                rs.getBoolean("is_active")
        ), roomTypeId);

        return results.stream().findFirst();
    }

    /**
     * Digits-only room numbers come first in numeric order. Ties and alphanumeric
     * numbers use case-insensitive lexical order in the C collation, then spelling and ID.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> findAndLockAvailablePhysicalRoom(Long roomTypeId, Instant startAt, Instant endAt) {
        // Inventory writes take an exclusive type lock, so concurrent allocators see the same room order.
        var activeType = jdbcTemplate.queryForList("""
            SELECT id FROM room_types WHERE id = ? AND is_active = TRUE FOR SHARE
            """, Long.class, roomTypeId);
        if (activeType.isEmpty()) return Optional.empty();

        String findCandidatesSql = """
            SELECT id FROM physical_rooms
            WHERE room_type_id = ? AND is_active = TRUE
            ORDER BY CASE WHEN room_number ~ '^[0-9]+$' THEN room_number::numeric END NULLS LAST,
                     lower(room_number) COLLATE "C", room_number COLLATE "C", id
        """;
        List<Long> candidateIds = jdbcTemplate.queryForList(findCandidatesSql, Long.class, roomTypeId);

        for (Long roomId : candidateIds) {
            // Wait for a competing hold or block, then check using the latest committed state.
            lockPhysicalRoom(roomId);
            if (!Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT is_active FROM physical_rooms WHERE id=?", Boolean.class, roomId))) continue;
            Instant now = Instant.now();
            // The exclusion constraint still sees unprocessed expired holds as pending.
            var expired = jdbcTemplate.queryForList("""
                UPDATE bookings SET status='EXPIRED', expired_at=?, updated_at=?
                WHERE physical_room_id=? AND status='PENDING_PAYMENT' AND hold_expires_at<=?
                  AND start_at<? AND end_at>?
                RETURNING id
                """, Long.class, Timestamp.from(now), Timestamp.from(now), roomId, Timestamp.from(now),
                    Timestamp.from(endAt), Timestamp.from(startAt));
            expired.forEach(vouchers::releaseForBooking);

            // 1. Check active booking overlaps
            boolean bookingOverlap = bookingRepository.hasActiveBookingOverlap(roomId, startAt, endAt, now);
            if (bookingOverlap) {
                continue;
            }

            // 2. Check active room block overlaps
            String blockCheckSql = """
                SELECT COUNT(*) FROM room_blocks
                WHERE physical_room_id = ?
                  AND released_at IS NULL
                  AND start_at < ?
                  AND end_at > ?
            """;
            Integer blockCount = jdbcTemplate.queryForObject(
                    blockCheckSql,
                    Integer.class,
                    roomId,
                    Timestamp.from(endAt),
                    Timestamp.from(startAt)
            );

            if (blockCount != null && blockCount > 0) {
                continue;
            }

            // Candidate room is completely free and locked
            return Optional.of(roomId);
        }

        return Optional.empty();
    }
}
