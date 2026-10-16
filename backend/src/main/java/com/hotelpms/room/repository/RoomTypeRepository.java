package com.hotelpms.room.repository;

import com.hotelpms.room.dto.RoomTypeResponse;
import com.hotelpms.room.entity.RoomType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

    Optional<RoomType> findByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    /** Public: chỉ loại phòng active, kèm số phòng vật lý active — 1 query. */
    @Query("""
            SELECT new com.hotelpms.room.dto.RoomTypeResponse(
                rt.id, rt.code, rt.name, rt.description,
                CAST(rt.maxOccupancy AS int), rt.basePricePerNight,
                SUM(CASE WHEN p.active = true THEN 1L ELSE 0L END)
            )
            FROM RoomType rt LEFT JOIN rt.physicalRooms p
            WHERE rt.active = true
            GROUP BY rt.id, rt.code, rt.name, rt.description, rt.maxOccupancy, rt.basePricePerNight
            ORDER BY rt.name ASC
            """)
    List<RoomTypeResponse> findAllActiveWithRoomCount();

    /** Public: chi tiết loại phòng active theo id — 1 query. */
    @Query("""
            SELECT new com.hotelpms.room.dto.RoomTypeResponse(
                rt.id, rt.code, rt.name, rt.description,
                CAST(rt.maxOccupancy AS int), rt.basePricePerNight,
                SUM(CASE WHEN p.active = true THEN 1L ELSE 0L END)
            )
            FROM RoomType rt LEFT JOIN rt.physicalRooms p
            WHERE rt.active = true AND rt.id = :id
            GROUP BY rt.id, rt.code, rt.name, rt.description, rt.maxOccupancy, rt.basePricePerNight
            """)
    Optional<RoomTypeResponse> findActiveByIdWithRoomCount(@Param("id") Long id);

    /** Admin: tất cả loại phòng (kể cả inactive) kèm số phòng vật lý active — 1 query. */
    @Query("""
            SELECT new com.hotelpms.room.dto.RoomTypeResponse(
                rt.id, rt.code, rt.name, rt.description,
                CAST(rt.maxOccupancy AS int), rt.basePricePerNight,
                SUM(CASE WHEN p.active = true THEN 1L ELSE 0L END)
            )
            FROM RoomType rt LEFT JOIN rt.physicalRooms p
            GROUP BY rt.id, rt.code, rt.name, rt.description, rt.maxOccupancy, rt.basePricePerNight
            ORDER BY rt.name ASC
            """)
    List<RoomTypeResponse> findAllWithRoomCount();
}
