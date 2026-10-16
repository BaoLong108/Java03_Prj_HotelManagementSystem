package com.hotelpms.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Request body tao block phong.
 * startAt va endAt la Instant (UTC) -- client tu convert tu timezone hien thi.
 */
public record RoomBlockRequest(

        @NotNull(message = "ID phong khong duoc null")
        Long physicalRoomId,

        @NotNull(message = "Thoi gian bat dau khong duoc null")
        Instant startAt,

        @NotNull(message = "Thoi gian ket thuc khong duoc null")
        Instant endAt,

        @NotBlank(message = "Ly do block khong duoc de trong")
        String reason
) { }