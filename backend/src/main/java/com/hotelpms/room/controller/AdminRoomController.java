package com.hotelpms.room.controller;

import com.hotelpms.room.dto.CreatePhysicalRoomRequest;
import com.hotelpms.room.dto.PhysicalRoomResponse;
import com.hotelpms.room.dto.RoomTypeRequest;
import com.hotelpms.room.dto.RoomTypeResponse;
import com.hotelpms.room.dto.UpdatePhysicalRoomRequest;
import com.hotelpms.room.service.RoomService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * CRUD quan tri loai phong va phong vat ly -- chi ADMIN.
 * Base path: /api/v1/admin/rooms
 */
@RestController
@RequestMapping("/api/v1/admin/rooms")
public class AdminRoomController {

    private final RoomService rooms;

    public AdminRoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    // ── Room Type ──────────────────────────────────────────────────────────────

    /** GET /api/v1/admin/rooms/types */
    @GetMapping("/types")
    public List<RoomTypeResponse> listAllTypes() {
        return rooms.adminListAllRoomTypes();
    }

    /** POST /api/v1/admin/rooms/types */
    @PostMapping("/types")
    public ResponseEntity<RoomTypeResponse> createType(@Valid @RequestBody RoomTypeRequest req) {
        RoomTypeResponse created = rooms.createRoomType(req);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** PUT /api/v1/admin/rooms/types/{id} */
    @PutMapping("/types/{id}")
    public RoomTypeResponse updateType(@PathVariable Long id,
                                       @Valid @RequestBody RoomTypeRequest req) {
        return rooms.updateRoomType(id, req);
    }

    /** DELETE /api/v1/admin/rooms/types/{id} -- soft-delete */
    @DeleteMapping("/types/{id}")
    public ResponseEntity<Void> deactivateType(@PathVariable Long id) {
        rooms.deactivateRoomType(id);
        return ResponseEntity.noContent().build();
    }

    // ── Physical Room ──────────────────────────────────────────────────────────

    /** GET /api/v1/admin/rooms/physical?roomTypeId={id} */
    @GetMapping("/physical")
    public List<PhysicalRoomResponse> listPhysical(@RequestParam Long roomTypeId) {
        return rooms.listPhysicalRooms(roomTypeId);
    }

    /** GET /api/v1/admin/rooms/physical/{id} */
    @GetMapping("/physical/{id}")
    public PhysicalRoomResponse getPhysical(@PathVariable Long id) {
        return rooms.getPhysicalRoom(id);
    }

    /** POST /api/v1/admin/rooms/physical */
    @PostMapping("/physical")
    public ResponseEntity<PhysicalRoomResponse> createPhysical(
            @Valid @RequestBody CreatePhysicalRoomRequest req) {
        PhysicalRoomResponse created = rooms.createPhysicalRoom(req);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** PUT /api/v1/admin/rooms/physical/{id} */
    @PutMapping("/physical/{id}")
    public PhysicalRoomResponse updatePhysical(@PathVariable Long id,
                                               @Valid @RequestBody UpdatePhysicalRoomRequest req) {
        return rooms.updatePhysicalRoom(id, req);
    }

    /** DELETE /api/v1/admin/rooms/physical/{id} -- soft-delete */
    @DeleteMapping("/physical/{id}")
    public ResponseEntity<Void> deactivatePhysical(@PathVariable Long id) {
        rooms.deactivatePhysicalRoom(id);
        return ResponseEntity.noContent().build();
    }
}