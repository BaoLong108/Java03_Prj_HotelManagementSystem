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
        long activeRoomCount,
        boolean active,
        String coverImageUrl,
        java.util.List<RoomImageResponse> images
) {
    // JPQL projection: metadata is loaded separately without fetching image bytes.
    public RoomTypeResponse(Long id, String code, String name, String description, int maxOccupancy,
                            BigDecimal basePricePerNight, long activeRoomCount, boolean active) {
        this(id, code, name, description, maxOccupancy, basePricePerNight, activeRoomCount, active, null, java.util.List.of());
    }

    public RoomTypeResponse withImages(java.util.List<RoomImageResponse> gallery) {
        return new RoomTypeResponse(id, code, name, description, maxOccupancy, basePricePerNight,
                activeRoomCount, active, gallery.stream().filter(RoomImageResponse::cover).map(RoomImageResponse::url)
                .findFirst().orElse(null), java.util.List.copyOf(gallery));
    }
}
