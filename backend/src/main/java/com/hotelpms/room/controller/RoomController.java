package com.hotelpms.room.controller;

import com.hotelpms.room.dto.RoomTypeResponse;
import com.hotelpms.room.service.RoomService;
import com.hotelpms.shared.api.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API cong khai cho loai phong -- khong yeu cau dang nhap.
 * Khong lo so phong vat ly hay thong tin noi bo.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    /** GET /api/v1/rooms -- danh sach loai phong dang hoat dong */
    @GetMapping
    public List<RoomTypeResponse> list() {
        return rooms.listActiveRoomTypes();
    }

    /** GET /api/v1/rooms/{id} -- chi tiet mot loai phong */
    @GetMapping("/{id}")
    public RoomTypeResponse get(@PathVariable Long id) {
        return rooms.getRoomType(id);
    }

    /**
     * M2-04: GET /api/v1/rooms/available?startAt=...&endAt=...&guests=...
     *
     * Tra ve danh sach loai phong co it nhat 1 phong vat ly kha dung
     * trong khoang [startAt, endAt) va maxOccupancy >= guests.
     *
     * Endpoint PUBLIC. Khong dung activeRoomCount de suy ra tinh trang tuong lai.
     *
     * @param startAt  ISO-8601 instant, e.g. 2026-11-01T14:00:00Z (inclusive)
     * @param endAt    ISO-8601 instant (exclusive)
     * @param guests   so khach; min 1, max 10
     */
    @GetMapping("/available")
    public List<RoomTypeResponse> available(
            @RequestParam Instant startAt,
            @RequestParam Instant endAt,
            @RequestParam(defaultValue = "1") int guests) {
        if (!endAt.isAfter(startAt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "endAt phai sau startAt");
        }
        if (guests < 1 || guests > 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GUEST_COUNT",
                    "So khach phai tu 1 den 10");
        }
        return rooms.listAvailableRoomTypes(startAt, endAt, guests, Instant.now());
    }
}