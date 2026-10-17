package com.hotelpms.booking.service;

import com.hotelpms.booking.repository.BookingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

    public RoomAllocationService(JdbcTemplate jdbcTemplate, BookingRepository bookingRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookingRepository = bookingRepository;
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
     * Finds and locks an available physical room for the given room type and time range.
     * Orders candidate rooms by ID to guarantee deadlock-free lock acquisition across concurrent transactions.
     */
    public Optional<Long> findAndLockAvailablePhysicalRoom(Long roomTypeId, Instant startAt, Instant endAt, Instant now) {
        String findCandidatesSql = """
            SELECT id FROM physical_rooms
            WHERE room_type_id = ? AND is_active = TRUE
            ORDER BY id ASC
        """;
        List<Long> candidateIds = jdbcTemplate.queryForList(findCandidatesSql, Long.class, roomTypeId);

        for (Long roomId : candidateIds) {
            // Lock the candidate row for the duration of the current transaction
            String lockSql = "SELECT id FROM physical_rooms WHERE id = ? FOR UPDATE";
            jdbcTemplate.queryForObject(lockSql, Long.class, roomId);

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
                    java.sql.Timestamp.from(endAt),
                    java.sql.Timestamp.from(startAt)
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
