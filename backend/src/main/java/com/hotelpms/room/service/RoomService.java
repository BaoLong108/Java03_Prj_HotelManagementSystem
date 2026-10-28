package com.hotelpms.room.service;

import com.hotelpms.booking.service.RoomAllocationService;
import com.hotelpms.room.dto.CreatePhysicalRoomRequest;
import com.hotelpms.room.dto.PhysicalRoomResponse;
import com.hotelpms.room.dto.RoomTypeRequest;
import com.hotelpms.room.dto.RoomTypeResponse;
import com.hotelpms.room.dto.UpdatePhysicalRoomRequest;
import com.hotelpms.room.entity.PhysicalRoom;
import com.hotelpms.room.entity.RoomType;
import com.hotelpms.room.repository.PhysicalRoomRepository;
import com.hotelpms.room.repository.RoomTypeRepository;
import com.hotelpms.shared.api.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private final RoomTypeRepository roomTypes;
    private final PhysicalRoomRepository physicalRooms;
    private final RoomAllocationService allocation;

    public RoomService(RoomTypeRepository roomTypes,
                       PhysicalRoomRepository physicalRooms,
                       RoomAllocationService allocation) {
        this.roomTypes = roomTypes;
        this.physicalRooms = physicalRooms;
        this.allocation = allocation;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoomTypeResponse> listActiveRoomTypes() {
        return roomTypes.findAllActiveWithRoomCount();
    }

    @Transactional(readOnly = true)
    public RoomTypeResponse getRoomType(Long id) {
        return roomTypes.findActiveByIdWithRoomCount(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loai phong khong ton tai"));
    }

    /**
     * M2-04: Tra ve danh sach loai phong co it nhat 1 phong kha dung
     * trong khoang [startAt, endAt) va maxOccupancy >= guests.
     *
     * Khong khoa -- chi doc snapshot. Khoa thuc su xay ra khi tao booking hold.
     * Khong dung activeRoomCount de suy ra kha dung theo thoi gian.
     *
     * @param startAt bat dau khoang thoi gian (inclusive)
     * @param endAt   ket thuc khoang thoi gian (exclusive)
     * @param guests  so khach; loc loai phong co maxOccupancy >= guests
     * @param now     thoi diem hien tai de tinh hold con hieu luc
     */
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> listAvailableRoomTypes(Instant startAt, Instant endAt,
                                                         int guests, Instant now) {
        // Lay roomTypeId co phong trong
        List<Long> availableTypeIds = allocation.findAvailableRoomTypeIds(startAt, endAt, now);
        if (availableTypeIds.isEmpty()) return List.of();

        // Lay DTO cua cac loai phong do, loc theo so khach
        return availableTypeIds.stream()
                .map(id -> roomTypes.findActiveByIdWithRoomCount(id))
                .filter(opt -> opt.isPresent())
                .map(opt -> opt.get())
                .filter(rt -> rt.maxOccupancy() >= guests)
                .toList();
    }

    // ── Admin: RoomType ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoomTypeResponse> adminListAllRoomTypes() {
        return roomTypes.findAllWithRoomCount();
    }

    @Transactional
    public RoomTypeResponse createRoomType(RoomTypeRequest req) {
        if (roomTypes.existsByCodeIgnoreCase(req.code())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_TYPE_CODE_DUPLICATE",
                    "Ma loai phong '" + req.code() + "' da ton tai");
        }
        RoomType rt = new RoomType(
                req.code().toUpperCase().strip(),
                req.name().strip(),
                req.description(),
                req.maxOccupancy(),
                req.basePricePerNight()
        );
        roomTypes.save(rt);
        return roomTypes.findByIdWithRoomCount(rt.getId()).orElseThrow();
    }

    @Transactional
    public RoomTypeResponse updateRoomType(Long id, RoomTypeRequest req) {
        RoomType rt = roomTypes.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loai phong khong ton tai"));

        if (!rt.getCode().equalsIgnoreCase(req.code())
                && roomTypes.existsByCodeIgnoreCase(req.code())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_TYPE_CODE_DUPLICATE",
                    "Ma loai phong '" + req.code() + "' da ton tai");
        }

        rt.setName(req.name().strip());
        rt.setDescription(req.description());
        rt.setMaxOccupancy(req.maxOccupancy());
        rt.setBasePricePerNight(req.basePricePerNight());
        roomTypes.saveAndFlush(rt);
        return roomTypes.findByIdWithRoomCount(id).orElseThrow();
    }

    @Transactional
    public void deactivateRoomType(Long id) {
        RoomType rt = roomTypes.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loai phong khong ton tai"));

        long activeCount = physicalRooms.countByRoomTypeIdAndActiveTrue(id);
        if (activeCount > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_TYPE_HAS_ACTIVE_ROOMS",
                    "Khong the an loai phong dang co " + activeCount + " phong vat ly hoat dong");
        }
        rt.setActive(false);
        roomTypes.save(rt);
    }

    // ── Admin: PhysicalRoom ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PhysicalRoomResponse> listPhysicalRooms(Long roomTypeId) {
        roomTypes.findById(roomTypeId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loai phong khong ton tai"));
        return physicalRooms.findAllByRoomTypeIdOrderByRoomNumberAsc(roomTypeId)
                .stream().map(PhysicalRoomResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PhysicalRoomResponse getPhysicalRoom(Long id) {
        PhysicalRoom room = physicalRooms.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PHYSICAL_ROOM_NOT_FOUND", "Phong khong ton tai"));
        return PhysicalRoomResponse.from(room);
    }

    @Transactional
    public PhysicalRoomResponse createPhysicalRoom(CreatePhysicalRoomRequest req) {
        RoomType rt = roomTypes.findById(req.roomTypeId())
                .filter(RoomType::isActive)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND",
                        "Loai phong khong ton tai hoac da bi an"));

        if (physicalRooms.existsByRoomNumberIgnoreCase(req.roomNumber())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_NUMBER_DUPLICATE",
                    "So phong '" + req.roomNumber() + "' da ton tai");
        }

        PhysicalRoom room = new PhysicalRoom(rt, req.roomNumber().strip(), req.floorNumber());
        physicalRooms.save(room);
        return PhysicalRoomResponse.from(room);
    }

    @Transactional
    public PhysicalRoomResponse updatePhysicalRoom(Long id, UpdatePhysicalRoomRequest req) {
        PhysicalRoom room = physicalRooms.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PHYSICAL_ROOM_NOT_FOUND", "Phong khong ton tai"));

        if (physicalRooms.existsByRoomNumberIgnoreCaseAndIdNot(req.roomNumber(), id)) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_NUMBER_DUPLICATE",
                    "So phong '" + req.roomNumber() + "' da ton tai");
        }

        room.setRoomNumber(req.roomNumber().strip());
        room.setFloorNumber(req.floorNumber());
        physicalRooms.save(room);
        return PhysicalRoomResponse.from(room);
    }

    @Transactional
    public void deactivatePhysicalRoom(Long id) {
        PhysicalRoom room = physicalRooms.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PHYSICAL_ROOM_NOT_FOUND", "Phong khong ton tai"));

        if (physicalRooms.hasActiveBookings(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_HAS_ACTIVE_BOOKINGS",
                    "Khong the an phong dang co booking PENDING_PAYMENT hoac CONFIRMED");
        }

        room.setActive(false);
        physicalRooms.save(room);
    }
}
