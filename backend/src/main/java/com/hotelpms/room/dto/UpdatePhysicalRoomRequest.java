package com.hotelpms.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body cap nhat phong vat ly.
 * Khong co roomTypeId -- loai phong khong duoc doi sau khi tao.
 * roomNumber cho phep doi (de sua sai chinh ta / danh so lai).
 */
public record UpdatePhysicalRoomRequest(

        @NotBlank(message = "So phong khong duoc trong")
        @Size(max = 30, message = "So phong toi da 30 ky tu")
        String roomNumber,

        @Positive(message = "So tang phai lon hon 0")
        Short floorNumber
) { }