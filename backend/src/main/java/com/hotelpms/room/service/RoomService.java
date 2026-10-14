package com.hotelpms.room.service;

import com.hotelpms.room.dto.RoomTypeResponse;
import com.hotelpms.room.repository.RoomTypeRepository;
import com.hotelpms.shared.api.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private final RoomTypeRepository roomTypes;

    public RoomService(RoomTypeRepository roomTypes) {
        this.roomTypes = roomTypes;
    }

    /** Trả về tất cả loại phòng đang hoạt động kèm số phòng vật lý active. */
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> listActiveRoomTypes() {
        return roomTypes.findAllByActiveTrueOrderByNameAsc().stream()
                .map(rt -> RoomTypeResponse.from(rt, roomTypes.countActivePhysicalRooms(rt.getId())))
                .toList();
    }

    /** Trả về chi tiết một loại phòng theo id. Trả 404 nếu không tìm thấy hoặc đã inactive. */
    @Transactional(readOnly = true)
    public RoomTypeResponse getRoomType(Long id) {
        return roomTypes.findById(id)
                .filter(rt -> rt.isActive())
                .map(rt -> RoomTypeResponse.from(rt, roomTypes.countActivePhysicalRooms(rt.getId())))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại"));
    }
}
