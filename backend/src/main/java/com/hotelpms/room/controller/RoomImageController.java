package com.hotelpms.room.controller;

import com.hotelpms.room.service.RoomImageService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
public class RoomImageController {
    private final RoomImageService images;
    public RoomImageController(RoomImageService images) { this.images = images; }

    @GetMapping("/api/v1/rooms/{typeId}/images/{imageId}")
    public ResponseEntity<byte[]> get(@PathVariable long typeId, @PathVariable long imageId,
            @RequestParam(defaultValue = "full") String size) {
        var content = images.content(typeId, imageId, size.equals("thumb"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .cacheControl(CacheControl.noCache().cachePublic())
                .body(content.data());
    }
}
