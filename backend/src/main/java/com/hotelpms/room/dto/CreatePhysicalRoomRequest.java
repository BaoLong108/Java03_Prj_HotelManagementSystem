package com.hotelpms.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body tao phong vat ly moi.
 * roomTypeId bat buoc -- xac dinh loai phong.
 */
public record CreatePhysicalRoomRequest(

        @NotNull(message = "ID loai phong khong duoc null")
        Long roomTypeId,

        @NotBlank(message = "So phong khong duoc trong")
        @Size(max = 30, message = "So phong toi da 30 ky tu")
        String roomNumber,

        @Positive(message = "So tang phai lon hon 0")
        Short floorNumber
) { }