package com.hotelpms.room.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record GalleryEditRequest(@NotNull @Size(max = 5) List<@Valid Item> items,
        int coverIndex, @NotNull @Size(max = 500) String expectedRevision) {
    public record Item(Long imageId, Integer uploadIndex) {}
}
