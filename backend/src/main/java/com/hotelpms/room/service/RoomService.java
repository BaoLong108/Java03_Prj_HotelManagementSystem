package com.hotelpms.room.service;

import com.hotelpms.room.dto.PhysicalRoomRequest;
import com.hotelpms.room.dto.PhysicalRoomResponse;
import com.hotelpms.room.dto.RoomTypeRequest;
import com.hotelpms.room.dto.RoomTypeResponse;
import com.hotelpms.room.entity.PhysicalRoom;
import com.hotelpms.room.entity.RoomType;
import com.hotelpms.room.repository.PhysicalRoomRepository;
import com.hotelpms.room.repository.RoomTypeRepository;
import com.hotelpms.shared.api.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private final RoomTypeRepository roomTypes;
    private final PhysicalRoomRepository physicalRooms;

    public RoomService(RoomTypeRepository roomTypes, PhysicalRoomRepository physicalRooms) {
        this.roomTypes = roomTypes;
        this.physicalRooms = physicalRooms;
    }

    // ─── PUBLIC API ────────────────────────────────────────────────────────────

    /** Danh sách loại phòng active — dùng cho trang chọn phòng của khách. */
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> listActiveRoomTypes() {
        return roomTypes.findAllActiveWithRoomCount();
    }

    /** Chi tiết một loại phòng active theo id. */
    @Transactional(readOnly = true)
    public RoomTypeResponse getRoomType(Long id) {
        return roomTypes.findActiveByIdWithRoomCount(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại"));
    }

    // ─── ADMIN: ROOM TYPE ──────────────────────────────────────────────────────

    /** Tất cả loại phòng (kể cả inactive) cho màn hình quản trị. */
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> adminListAllRoomTypes() {
        return roomTypes.findAllWithRoomCount();
    }

    /** Tạo loại phòng mới. Mã code phải unique (case-insensitive). */
    @Transactional
    public RoomTypeResponse createRoomType(RoomTypeRequest req) {
        if (roomTypes.existsByCodeIgnoreCase(req.code())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_TYPE_CODE_DUPLICATE",
                    "Mã loại phòng '" + req.code() + "' đã tồn tại");
        }
        RoomType rt = new RoomType(
                req.code().toUpperCase().strip(),
                req.name().strip(),
                req.description(),
                req.maxOccupancy(),
                req.basePricePerNight()
        );
        roomTypes.save(rt);
        return roomTypes.findActiveByIdWithRoomCount(rt.getId())
                .orElseThrow();
    }

    /** Cập nhật loại phòng — không cho đổi code sau khi tạo. */
    @Transactional
    public RoomTypeResponse updateRoomType(Long id, RoomTypeRequest req) {
        RoomType rt = roomTypes.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại"));

        // Nếu code thay đổi thì kiểm tra trùng
        if (!rt.getCode().equalsIgnoreCase(req.code()) && roomTypes.existsByCodeIgnoreCase(req.code())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_TYPE_CODE_DUPLICATE",
                    "Mã loại phòng '" + req.code() + "' đã tồn tại");
        }

        rt.setName(req.name().strip());
        rt.setDescription(req.description());
        rt.setMaxOccupancy(req.maxOccupancy());
        rt.setBasePricePerNight(req.basePricePerNight());
        roomTypes.save(rt);
        return roomTypes.findAllWithRoomCount().stream()
                .filter(r -> r.id().equals(id))
                .findFirst().orElseThrow();
    }

    /**
     * Ẩn loại phòng (soft-delete: is_active = false).
     * Không cho ẩn nếu còn phòng vật lý active thuộc loại này.
     */
    @Transactional
    public void deactivateRoomType(Long id) {
        RoomType rt = roomTypes.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại"));

        long activeCount = physicalRooms
                .findAllByRoomTypeIdAndActiveTrueOrderByRoomNumberAsc(id).size();
        if (activeCount > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_TYPE_HAS_ACTIVE_ROOMS",
                    "Không thể ẩn loại phòng đang có " + activeCount + " phòng vật lý hoạt động");
        }
        rt.setActive(false);
        roomTypes.save(rt);
    }

    // ─── ADMIN: PHYSICAL ROOM ─────────────────────────────────────────────────

    /** Danh sách tất cả phòng vật lý thuộc một loại phòng. */
    @Transactional(readOnly = true)
    public List<PhysicalRoomResponse> listPhysicalRooms(Long roomTypeId) {
        roomTypes.findById(roomTypeId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại"));
        return physicalRooms.findAllByRoomTypeIdOrderByRoomNumberAsc(roomTypeId)
                .stream().map(PhysicalRoomResponse::from).toList();
    }

    /** Tạo phòng vật lý mới. Số phòng phải unique toàn khách sạn. */
    @Transactional
    public PhysicalRoomResponse createPhysicalRoom(PhysicalRoomRequest req) {
        RoomType rt = roomTypes.findById(req.roomTypeId())
                .filter(RoomType::isActive)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND",
                        "Loại phòng không tồn tại hoặc đã bị ẩn"));

        if (physicalRooms.existsByRoomNumberIgnoreCase(req.roomNumber())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_NUMBER_DUPLICATE",
                    "Số phòng '" + req.roomNumber() + "' đã tồn tại");
        }

        PhysicalRoom room = new PhysicalRoom(rt, req.roomNumber().strip(), req.floorNumber());
        physicalRooms.save(room);
        return PhysicalRoomResponse.from(room);
    }

    /** Cập nhật thông tin phòng vật lý — không cho đổi roomType. */
    @Transactional
    public PhysicalRoomResponse updatePhysicalRoom(Long id, PhysicalRoomRequest req) {
        PhysicalRoom room = physicalRooms.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PHYSICAL_ROOM_NOT_FOUND", "Phòng không tồn tại"));

        if (physicalRooms.existsByRoomNumberIgnoreCaseAndIdNot(req.roomNumber(), id)) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_NUMBER_DUPLICATE",
                    "Số phòng '" + req.roomNumber() + "' đã tồn tại");
        }

        room.setFloorNumber(req.floorNumber());
        physicalRooms.save(room);
        return PhysicalRoomResponse.from(room);
    }

    /**
     * Ẩn phòng vật lý (soft-delete: is_active = false).
     * Không xóa cứng để bảo toàn lịch sử booking.
     */
    @Transactional
    public void deactivatePhysicalRoom(Long id) {
        PhysicalRoom room = physicalRooms.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PHYSICAL_ROOM_NOT_FOUND", "Phòng không tồn tại"));
        room.setActive(false);
        physicalRooms.save(room);
    }
}
