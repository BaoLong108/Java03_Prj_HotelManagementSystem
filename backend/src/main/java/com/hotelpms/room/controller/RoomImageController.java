package com.hotelpms.room.controller;

import com.hotelpms.room.service.RoomImageService;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoomImageController {
    private final RoomImageService images;
    public RoomImageController(RoomImageService images) { this.images = images; }

    @GetMapping("/api/v1/rooms/{typeId}/images/{imageId}")
    public ResponseEntity<byte[]> get(@PathVariable long typeId, @PathVariable long imageId) {
        var content = images.content(typeId, imageId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(content.data());
    }
}
