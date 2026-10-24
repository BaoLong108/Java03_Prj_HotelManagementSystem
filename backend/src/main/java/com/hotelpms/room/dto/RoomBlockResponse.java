package com.hotelpms.room.dto;

import com.hotelpms.room.entity.RoomBlock;
import java.time.Instant;

/**
 * DTO tra ve thong tin block phong.
 */
public record RoomBlockResponse(
        Long id,
        Long physicalRoomId,
        String roomNumber,
        Instant startAt,
        Instant endAt,
        String reason,
        Long createdByUserId,
        Instant createdAt,
        boolean active,
        Long releasedByUserId,
        Instant releasedAt
) {
    public static RoomBlockResponse from(RoomBlock b) {
        return new RoomBlockResponse(
                b.getId(),
                b.getPhysicalRoom().getId(),
                b.getPhysicalRoom().getRoomNumber(),
                b.getStartAt(),
                b.getEndAt(),
                b.getReason(),
                b.getCreatedByUserId(),
                b.getCreatedAt(),
                b.isActive(),
                b.getReleasedByUserId(),
                b.getReleasedAt()
        );
    }
}