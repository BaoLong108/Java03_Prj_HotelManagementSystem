package com.hotelpms.room.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request body dùng cho tạo mới và cập nhật loại phòng.
 */
public record RoomTypeRequest(

        @NotBlank(message = "Mã loại phòng không được trống")
        @Size(max = 30, message = "Mã loại phòng tối đa 30 ký tự")
        String code,

        @NotBlank(message = "Tên loại phòng không được trống")
        @Size(max = 120, message = "Tên loại phòng tối đa 120 ký tự")
        String name,

        String description,

        @NotNull(message = "Sức chứa tối đa không được null")
        @Positive(message = "Sức chứa tối đa phải lớn hơn 0")
        Short maxOccupancy,

        @NotNull(message = "Giá mỗi đêm không được null")
        @DecimalMin(value = "0.0", message = "Giá mỗi đêm không được âm")
        BigDecimal basePricePerNight
) { }
