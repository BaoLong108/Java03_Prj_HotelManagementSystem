package com.hotelpms.booking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record CreateBookingRequest(
        @NotNull(message = "roomTypeId không được để trống")
        Long roomTypeId,

        @Positive(message = "guestCount phải lớn hơn 0")
        short guestCount,

        @NotNull(message = "startAt không được để trống")
        Instant startAt,

        @NotNull(message = "endAt không được để trống")
        Instant endAt
) {
}
