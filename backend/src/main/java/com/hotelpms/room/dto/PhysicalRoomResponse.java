package com.hotelpms.room.dto;

import com.hotelpms.room.entity.PhysicalRoom;

/**
 * DTO trả về thông tin phòng vật lý cho admin.
 * Số phòng chỉ lộ ở admin API, không lộ ở public API.
 */
public record PhysicalRoomResponse(
        Long id,
        Long roomTypeId,
        String roomTypeCode,
        String roomTypeName,
        String roomNumber,
        Short floorNumber,
        boolean active
) {
    public static PhysicalRoomResponse from(PhysicalRoom room) {
        return new PhysicalRoomResponse(
                room.getId(),
                room.getRoomType().getId(),
                room.getRoomType().getCode(),
                room.getRoomType().getName(),
                room.getRoomNumber(),
                room.getFloorNumber(),
                room.isActive()
        );
    }
}
