package com.hotelpms.room.dto;

import java.math.BigDecimal;

/**
 * DTO trả về cho client khi liệt kê / xem chi tiết loại phòng.
 * Không lộ danh sách phòng vật lý; chỉ hiển thị số phòng available để giúp khách chọn.
 *
 * Constructor 7-arg được JPQL dùng trực tiếp qua "new" expression.
 */
public record RoomTypeResponse(
        Long id,
        String code,
        String name,
        String description,
        int maxOccupancy,
        BigDecimal basePricePerNight,
        long activeRoomCount
) { }
