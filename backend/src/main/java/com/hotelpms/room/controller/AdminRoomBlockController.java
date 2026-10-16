package com.hotelpms.room.controller;

import com.hotelpms.room.dto.RoomBlockRequest;
import com.hotelpms.room.dto.RoomBlockResponse;
import com.hotelpms.room.service.RoomBlockService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * API nhan vien/admin quan ly block phong.
 * Base path: /api/v1/admin/room-blocks
 *
 * staffId duoc lay tu request attribute (se duoc set boi JWT filter o M1-03).
 * Tam thoi hardcode = 1L cho den khi co auth.
 */
@RestController
@RequestMapping("/api/v1/admin/room-blocks")
public class AdminRoomBlockController {

    private final RoomBlockService blockService;

    public AdminRoomBlockController(RoomBlockService blockService) {
        this.blockService = blockService;
    }

    /**
     * GET /api/v1/admin/room-blocks?roomId={id}&history=false
     * Lay danh sach block cua phong.
     * history=true: lay tat ca (ca released); history=false (default): chi lay active.
     */
    @GetMapping
    public List<RoomBlockResponse> list(
            @RequestParam Long roomId,
            @RequestParam(defaultValue = "false") boolean history) {
        return history
                ? blockService.listAllBlocks(roomId)
                : blockService.listActiveBlocks(roomId);
    }

    /**
     * POST /api/v1/admin/room-blocks — tao block moi
     * TODO M1-03: lay staffId tu JWT principal thay vi hardcode.
     */
    @PostMapping
    public ResponseEntity<RoomBlockResponse> create(@Valid @RequestBody RoomBlockRequest req) {
        Long staffId = 1L; // TODO: lay tu SecurityContext sau khi co JWT
        RoomBlockResponse created = blockService.createBlock(req, staffId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * DELETE /api/v1/admin/room-blocks/{id} — giai phong (mo block som)
     * TODO M1-03: lay staffId tu JWT principal.
     */
    @DeleteMapping("/{id}")
    public RoomBlockResponse release(@PathVariable Long id) {
        Long staffId = 1L; // TODO: lay tu SecurityContext sau khi co JWT
        return blockService.releaseBlock(id, staffId);
    }
}