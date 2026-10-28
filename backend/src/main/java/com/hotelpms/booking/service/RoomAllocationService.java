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
 * Dich vu phan phong va kiem tra tinh trang san co cua phong vat ly.
 *
 * M2-04: truy van phong co the su dung trong khoang [startAt, endAt),
 * khoa row FOR UPDATE SKIP LOCKED de ngan hai giao dich dong thoi
 * cap cung mot phong, sau do kiem tra lai booking + block con hieu luc.
 */
@Service
public class RoomAllocationService {

    public record RoomTypeInfo(
            Long id,
            String name,
            short maxOccupancy,
            BigDecimal basePricePerNight,
            boolean active
    ) {}

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
     * Tim va khoa phong vat ly kha dung cho khoang [startAt, endAt).
     *
     * Thuat toan:
     * 1. Doc danh sach candidates (active, cung loai phong) order by id -- tranh deadlock.
     * 2. Voi moi candidate: FOR UPDATE SKIP LOCKED -- skip phong dang bi khoa boi tx khac.
     * 3. Kiem tra overlap booking: CONFIRMED/COMPLETED hoac PENDING_PAYMENT con hold.
     * 4. Kiem tra overlap block: released_at IS NULL, [block.start, block.end) giao [startAt, endAt).
     * 5. Phong dau tien khong co overlap la phong duoc chon.
     *
     * Goi trong @Transactional cua caller de lock co hieu luc.
     */
    public Optional<Long> findAndLockAvailablePhysicalRoom(Long roomTypeId,
                                                           Instant startAt,
                                                           Instant endAt,
                                                           Instant now) {
        // Buoc 1: lay danh sach candidates order by id de dam bao thu tu khoa nhat quan
        String candidateSql = """
            SELECT id FROM physical_rooms
            WHERE room_type_id = ? AND is_active = TRUE
            ORDER BY id ASC
        """;
        List<Long> candidateIds = jdbcTemplate.queryForList(candidateSql, Long.class, roomTypeId);

        for (Long roomId : candidateIds) {
            // Buoc 2: khoa row bang FOR UPDATE SKIP LOCKED
            // -- SKIP LOCKED bo qua phong dang bi lock boi tx dong thoi (tranh deadlock)
            String lockSql = "SELECT id FROM physical_rooms WHERE id = ? FOR UPDATE SKIP LOCKED";
            List<Long> locked = jdbcTemplate.queryForList(lockSql, Long.class, roomId);
            if (locked.isEmpty()) {
                // Phong dang bi khoa boi tx khac, thu phong tiep theo
                continue;
            }

            // Buoc 3: kiem tra booking overlap [startAt, endAt)
            // CONFIRMED, COMPLETED, hoac PENDING_PAYMENT con trong cua so hold
            boolean bookingOverlap = bookingRepository.hasActiveBookingOverlap(
                    roomId, startAt, endAt, now);
            if (bookingOverlap) {
                continue;
            }

            // Buoc 4: kiem tra room block overlap [startAt, endAt)
            // Dung RoomBlockRepository.countOverlapping da co san -- khong viet lai SQL native
            long blockCount = roomBlockRepository.countOverlapping(roomId, startAt, endAt);
            if (blockCount > 0) {
                continue;
            }

            // Phong nay hoan toan trong va da bi khoa -- chon no
            return Optional.of(roomId);
        }

        return Optional.empty();
    }

    /**
     * Tra ve danh sach roomTypeId co it nhat 1 phong vat ly kha dung
     * trong khoang [startAt, endAt) voi so khach <= maxOccupancy.
     *
     * Dung cho API public GET /api/v1/rooms/available.
     * Khong can khoa (read-only snapshot), booking moi se khoa lai trong tx tao hold.
     */
    public List<Long> findAvailableRoomTypeIds(Instant startAt, Instant endAt, Instant now) {
        // Lay tat ca phong active kem roomTypeId
        String sql = """
            SELECT pr.id AS room_id, pr.room_type_id
            FROM physical_rooms pr
            WHERE pr.is_active = TRUE
            ORDER BY pr.room_type_id ASC, pr.id ASC
        """;

        record RoomRow(Long roomId, Long roomTypeId) {}
        List<RoomRow> allRooms = jdbcTemplate.query(sql, (rs, i) -> new RoomRow(
                rs.getLong("room_id"), rs.getLong("room_type_id")));

        // Voi moi phong, kiem tra booking + block; nhom roomTypeId co phong trong
        return allRooms.stream()
                .filter(r -> {
                    boolean bOverlap = bookingRepository.hasActiveBookingOverlap(
                            r.roomId(), startAt, endAt, now);
                    if (bOverlap) return false;
                    long bkCount = roomBlockRepository.countOverlapping(r.roomId(), startAt, endAt);
                    return bkCount == 0;
                })
                .map(RoomRow::roomTypeId)
                .distinct()
                .toList();
    }
}
