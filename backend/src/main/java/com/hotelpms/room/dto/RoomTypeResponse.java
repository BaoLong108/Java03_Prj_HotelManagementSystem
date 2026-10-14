package com.hotelpms.room.dto;

import com.hotelpms.room.entity.RoomType;
import java.math.BigDecimal;

/**
 * DTO trả về cho client khi liệt kê / xem chi tiết loại phòng.
 * Không lộ danh sách phòng vật lý; chỉ hiển thị số phòng available để giúp khách chọn.
 */
public record RoomTypeResponse(
        Long id,
        String code,
        String name,
        String description,
        int maxOccupancy,
        BigDecimal basePricePerNight,
        long activeRoomCount
) {
    public static RoomTypeResponse from(RoomType roomType, long activeRoomCount) {
        return new RoomTypeResponse(
                roomType.getId(),
                roomType.getCode(),
                roomType.getName(),
                roomType.getDescription(),
                roomType.getMaxOccupancy(),
                roomType.getBasePricePerNight(),
                activeRoomCount
        );
    }
}
