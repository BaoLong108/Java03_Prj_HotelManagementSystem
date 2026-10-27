package com.hotelpms.room.controller;

import com.hotelpms.room.dto.RoomTypeResponse;
import com.hotelpms.room.service.RoomService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API công khai cho loại phòng — không yêu cầu đăng nhập.
 * Không lộ số phòng vật lý hay thông tin nội bộ.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomService rooms;
    private final com.hotelpms.room.service.RoomAvailabilityService availability;

    public RoomController(RoomService rooms, com.hotelpms.room.service.RoomAvailabilityService availability) {
        this.rooms = rooms;
        this.availability = availability;
    }

    @GetMapping("/availability")
    public java.util.List<com.hotelpms.room.service.RoomAvailabilityService.AvailableRoomType> availability(
            @org.springframework.web.bind.annotation.RequestParam java.time.Instant startAt,
            @org.springframework.web.bind.annotation.RequestParam java.time.Instant endAt,
            @org.springframework.web.bind.annotation.RequestParam int guestCount) {
        return availability.search(startAt, endAt, guestCount);
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
