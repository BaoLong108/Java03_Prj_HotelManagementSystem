package com.hotelpms.room.repository;

import com.hotelpms.room.entity.PhysicalRoom;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhysicalRoomRepository extends JpaRepository<PhysicalRoom, Long> {

    boolean existsByRoomNumberIgnoreCase(String roomNumber);

    /** Kiểm tra trùng số phòng khi update (loại trừ chính phòng đó). */
    boolean existsByRoomNumberIgnoreCaseAndIdNot(String roomNumber, Long id);

    Optional<PhysicalRoom> findByRoomNumber(String roomNumber);

    /** Tất cả phòng thuộc một loại phòng, dùng cho admin. */
    List<PhysicalRoom> findAllByRoomTypeIdOrderByRoomNumberAsc(Long roomTypeId);

    /** Phòng active thuộc loại phòng, dùng khi chọn phòng cho booking. */
    List<PhysicalRoom> findAllByRoomTypeIdAndActiveTrueOrderByRoomNumberAsc(Long roomTypeId);

    /**
     * Kiểm tra phòng có lịch sử booking không (CONFIRMED, COMPLETED, PENDING_PAYMENT).
     * Dùng để quyết định soft-delete thay vì hard-delete.
     */
    @Query("""
            SELECT COUNT(b) > 0
            FROM Booking b
            WHERE b.physicalRoomId = :roomId
              AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED')
            """)
    boolean hasActiveBookings(@Param("roomId") Long roomId);
}
