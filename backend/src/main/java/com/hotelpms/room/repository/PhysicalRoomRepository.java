package com.hotelpms.room.repository;

import com.hotelpms.room.entity.PhysicalRoom;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhysicalRoomRepository extends JpaRepository<PhysicalRoom, Long> {

    boolean existsByRoomNumberIgnoreCase(String roomNumber);

    Optional<PhysicalRoom> findByRoomNumber(String roomNumber);

    /** Tất cả phòng vật lý thuộc một loại phòng, dùng cho quản trị nội bộ. */
    List<PhysicalRoom> findAllByRoomTypeIdOrderByRoomNumberAsc(Long roomTypeId);

    /** Phòng vật lý đang active thuộc loại phòng, dùng khi chọn phòng cho booking. */
    List<PhysicalRoom> findAllByRoomTypeIdAndActiveTrueOrderByRoomNumberAsc(Long roomTypeId);
}
