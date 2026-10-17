package com.hotelpms.room.controller;

import com.hotelpms.room.dto.RoomBlockRequest;
import com.hotelpms.room.dto.RoomBlockResponse;
import com.hotelpms.room.service.RoomBlockService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * API nhan vien/admin quan ly block phong.
 * Base path: /api/v1/admin/room-blocks
 *
 * Nguoi thao tac lay tu JWT da xac minh cua module Auth.
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
     * history=false (default): chi block active.
     * history=true: tat ca (ca released).
     */
    @GetMapping
    public List<RoomBlockResponse> list(
            @RequestParam Long roomId,
            @RequestParam(defaultValue = "false") boolean history) {
        return history
                ? blockService.listAllBlocks(roomId)
                : blockService.listActiveBlocks(roomId);
    }

    /** GET /api/v1/admin/room-blocks/{id} -- chi tiet mot block */
    @GetMapping("/{id}")
    public RoomBlockResponse get(@PathVariable Long id) {
        return blockService.getBlock(id);
    }

    /** POST /api/v1/admin/room-blocks -- tao block moi */
    @PostMapping
    public ResponseEntity<RoomBlockResponse> create(@Valid @RequestBody RoomBlockRequest req,
                                                   @AuthenticationPrincipal Jwt jwt) {
        Long staffId = Long.valueOf(jwt.getSubject());
        RoomBlockResponse created = blockService.createBlock(req, staffId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** DELETE /api/v1/admin/room-blocks/{id} -- giai phong (mo block som) */
    @DeleteMapping("/{id}")
    public RoomBlockResponse release(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        Long staffId = Long.valueOf(jwt.getSubject());
        return blockService.releaseBlock(id, staffId);
    }
}
