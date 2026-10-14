package com.hotelpms.room.controller;

import com.hotelpms.room.dto.RoomTypeResponse;
import com.hotelpms.room.service.RoomService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API công khai cho loại phòng.
 * Khách chưa đăng nhập được phép xem — không lộ số phòng vật lý.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    /** GET /api/v1/rooms — danh sách loại phòng đang hoạt động */
    @GetMapping
    public List<RoomTypeResponse> list() {
        return rooms.listActiveRoomTypes();
    }

    /** GET /api/v1/rooms/{id} — chi tiết một loại phòng */
    @GetMapping("/{id}")
    public RoomTypeResponse get(@PathVariable Long id) {
        return rooms.getRoomType(id);
    }
}
