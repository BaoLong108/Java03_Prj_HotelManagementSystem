package com.hotelpms.booking.service;

import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.room.repository.RoomBlockRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * M2-04 — Dich vu phan phong va kiem tra tinh trang phong vat ly.
 *
 * findAndLockAvailablePhysicalRoom: khoa row FOR UPDATE SKIP LOCKED,
 * kiem tra overlap booking + block theo [startAt, endAt).
 * findAvailableRoomTypeIds: snapshot khong khoa, dung cho API public.
 */
@Service
public class RoomAllocationService {

    public record RoomTypeInfo(
            Long id,
            String name,
            short maxOccupancy,
            BigDecimal basePricePerNight,
            boolean active) {}

    private final JdbcTemplate jdbcTemplate;
    private final BookingRepository bookingRepository;
    private final RoomBlockRepository roomBlockRepository;

    public RoomAllocationService(JdbcTemplate jdbcTemplate,
                                 BookingRepository bookingRepository,
                                 RoomBlockRepository roomBlockRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookingRepository = bookingRepository;
        this.roomBlockRepository = roomBlockRepository;
    }

    /** Doc thong tin RoomType theo id de tinh gia va kiem tra so khach. */
    public Optional<RoomTypeInfo> getRoomTypeInfo(Long roomTypeId) {
        String sql = """
            SELECT id, name, max_occupancy, base_price_per_night, is_active
            FROM room_types WHERE id = ?
            """;
        List<RoomTypeInfo> results = jdbcTemplate.query(sql, (rs, rowNum) -> new RoomTypeInfo(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getShort("max_occupancy"),
                rs.getBigDecimal("base_price_per_night"),
                rs.getBoolean("is_active")), roomTypeId);
        return results.stream().findFirst();
    }

    /**
     * Tim va khoa phong vat ly kha dung trong khoang [startAt, endAt).
     *
     * Thuat toan:
     * 1. Lay candidates (active, cung type) order by id -- thu tu nhat quan tranh deadlock.
     * 2. FOR UPDATE SKIP LOCKED -- skip phong dang bi khoa boi tx dong thoi.
     * 3. Kiem tra booking overlap (CONFIRMED/COMPLETED/PENDING_PAYMENT con hold).
     * 4. Kiem tra block overlap (released_at IS NULL).
     * 5. Phong dau tien khong overlap -> chon va tra ve.
     *
     * Caller phai chay trong @Transactional de lock co hieu luc.
     */
    public Optional<Long> findAndLockAvailablePhysicalRoom(Long roomTypeId,
                                                           Instant startAt,
                                                           Instant endAt,
                                                           Instant now) {
        List<Long> candidateIds = jdbcTemplate.queryForList("""
                SELECT id FROM physical_rooms
                WHERE room_type_id = ? AND is_active = TRUE
                ORDER BY id ASC
                """, Long.class, roomTypeId);

        for (Long roomId : candidateIds) {
            // FOR UPDATE SKIP LOCKED: skip phong bi khoa boi tx khac
            List<Long> locked = jdbcTemplate.queryForList(
                    "SELECT id FROM physical_rooms WHERE id = ? FOR UPDATE SKIP LOCKED",
                    Long.class, roomId);
            if (locked.isEmpty()) continue;

            // Kiem tra booking overlap [startAt, endAt)
            if (bookingRepository.hasActiveBookingOverlap(roomId, startAt, endAt, now)) continue;

            // Kiem tra block overlap [startAt, endAt) qua RoomBlockRepository
            if (roomBlockRepository.countOverlapping(roomId, startAt, endAt) > 0) continue;

            return Optional.of(roomId);
        }
        return Optional.empty();
    }

    /**
     * Snapshot khong khoa: tra ve roomTypeId co it nhat 1 phong trong.
     * Dung cho API public GET /api/v1/rooms/available.
     */
    public List<Long> findAvailableRoomTypeIds(Instant startAt, Instant endAt, Instant now) {
        record RoomRow(Long roomId, Long roomTypeId) {}
        List<RoomRow> allRooms = jdbcTemplate.query("""
                SELECT pr.id AS room_id, pr.room_type_id
                FROM physical_rooms pr
                WHERE pr.is_active = TRUE
                ORDER BY pr.room_type_id ASC, pr.id ASC
                """, (rs, i) -> new RoomRow(rs.getLong("room_id"), rs.getLong("room_type_id")));

        return allRooms.stream()
                .filter(r -> !bookingRepository.hasActiveBookingOverlap(r.roomId(), startAt, endAt, now)
                          && roomBlockRepository.countOverlapping(r.roomId(), startAt, endAt) == 0)
                .map(RoomRow::roomTypeId)
                .distinct()
                .toList();
    }
}