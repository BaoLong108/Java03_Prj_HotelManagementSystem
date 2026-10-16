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

    /** Public: chi loai phong active, kem so phong vat ly active -- 1 query. */
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

    /** Public: chi tiet loai phong active theo id -- 1 query. */
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

    /** Admin: tat ca loai phong (ke ca inactive) kem so phong active -- 1 query. */
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

    /**
     * Admin: chi tiet bat ky loai phong nao theo id (ke ca inactive).
     * Dung sau createRoomType / updateRoomType de tra response dung.
     */
    @Query("""
            SELECT new com.hotelpms.room.dto.RoomTypeResponse(
                rt.id, rt.code, rt.name, rt.description,
                CAST(rt.maxOccupancy AS int), rt.basePricePerNight,
                SUM(CASE WHEN p.active = true THEN 1L ELSE 0L END)
            )
            FROM RoomType rt LEFT JOIN rt.physicalRooms p
            WHERE rt.id = :id
            GROUP BY rt.id, rt.code, rt.name, rt.description, rt.maxOccupancy, rt.basePricePerNight
            """)
    Optional<RoomTypeResponse> findByIdWithRoomCount(@Param("id") Long id);
}