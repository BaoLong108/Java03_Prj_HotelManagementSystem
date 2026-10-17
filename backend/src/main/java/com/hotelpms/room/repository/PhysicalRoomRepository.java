package com.hotelpms.room.repository;

import com.hotelpms.room.entity.PhysicalRoom;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhysicalRoomRepository extends JpaRepository<PhysicalRoom, Long> {

    boolean existsByRoomNumberIgnoreCase(String roomNumber);

    boolean existsByRoomNumberIgnoreCaseAndIdNot(String roomNumber, Long id);

    Optional<PhysicalRoom> findByRoomNumber(String roomNumber);

    List<PhysicalRoom> findAllByRoomTypeIdOrderByRoomNumberAsc(Long roomTypeId);

    List<PhysicalRoom> findAllByRoomTypeIdAndActiveTrueOrderByRoomNumberAsc(Long roomTypeId);

    /** Dem so phong active trong mot loai phong -- dung thay .size() de tranh load list. */
    long countByRoomTypeIdAndActiveTrue(Long roomTypeId);

    /**
     * Kiem tra phong co booking dang active (PENDING_PAYMENT / CONFIRMED) khong.
     * Dung native query vi Booking entity chua duoc tao -- tranh phu thuoc cross-module qua JPQL.
     * Khi Booking entity co san, co the chuyen sang JPQL.
     */
    @Query(value = """
            SELECT COUNT(*) > 0
            FROM bookings
            WHERE physical_room_id = :roomId
              AND status IN ('PENDING_PAYMENT', 'CONFIRMED')
            """, nativeQuery = true)
    boolean hasActiveBookings(@Param("roomId") Long roomId);
}