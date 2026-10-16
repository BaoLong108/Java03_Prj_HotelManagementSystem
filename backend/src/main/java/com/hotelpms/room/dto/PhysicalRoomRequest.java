package com.hotelpms.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body dùng cho tạo mới và cập nhật phòng vật lý.
 */
public record PhysicalRoomRequest(

        @NotNull(message = "ID loại phòng không được null")
        Long roomTypeId,

        @NotBlank(message = "Số phòng không được trống")
        @Size(max = 30, message = "Số phòng tối đa 30 ký tự")
        String roomNumber,

        @Positive(message = "Số tầng phải lớn hơn 0")
        Short floorNumber
) { }
