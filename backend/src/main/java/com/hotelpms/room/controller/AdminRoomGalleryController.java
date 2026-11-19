package com.hotelpms.room.controller;

import com.hotelpms.room.dto.GalleryEditRequest;
import com.hotelpms.room.dto.RoomImageResponse;
import com.hotelpms.room.service.RoomGalleryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/rooms/types/{typeId}/images")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRoomGalleryController {
    private final RoomGalleryService galleries;
    public AdminRoomGalleryController(RoomGalleryService galleries) { this.galleries = galleries; }
    @PutMapping(consumes = "multipart/form-data")
    public List<RoomImageResponse> save(@PathVariable long typeId,
            @Valid @RequestPart("gallery") GalleryEditRequest request,
            @RequestPart(value = "images", required = false) List<MultipartFile> uploads) {
        return galleries.save(typeId, request, uploads);
    }
}
