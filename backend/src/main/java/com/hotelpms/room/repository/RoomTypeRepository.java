package com.hotelpms.room.repository;

import com.hotelpms.room.entity.RoomType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

    /** Tất cả loại phòng đang hoạt động, sắp xếp theo tên. */
    List<RoomType> findAllByActiveTrueOrderByNameAsc();

    Optional<RoomType> findByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    /**
     * Đếm số phòng vật lý đang active thuộc loại phòng này — dùng để hiển thị
     * "X phòng còn trống" mà không load toàn bộ collection.
     */
    @Query("SELECT COUNT(p) FROM PhysicalRoom p WHERE p.roomType.id = :roomTypeId AND p.active = true")
    long countActivePhysicalRooms(Long roomTypeId);
}
