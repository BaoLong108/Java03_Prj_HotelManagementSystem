package com.hotelpms.room.service;

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
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private final RoomTypeRepository roomTypes;
    private final PhysicalRoomRepository physicalRooms;
    private final RoomImageService images;
    private final RoomAvailabilityService availability;

    public RoomService(RoomTypeRepository roomTypes, PhysicalRoomRepository physicalRooms, RoomImageService images,
            RoomAvailabilityService availability) {
        this.roomTypes = roomTypes;
        this.physicalRooms = physicalRooms;
        this.images = images;
        this.availability = availability;
    }

    @Transactional(readOnly = true)
    public List<RoomTypeResponse> listAvailableRoomTypes(java.time.Instant startAt, java.time.Instant endAt,
            int guests, java.time.Instant now) {
        var available = availability.searchAt(startAt, endAt, guests, now).stream()
                .map(RoomAvailabilityService.AvailableRoomType::id).collect(java.util.stream.Collectors.toSet());
        return enrich(roomTypes.findAllActiveWithRoomCount().stream().filter(type -> available.contains(type.id())).toList());
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoomTypeResponse> listActiveRoomTypes() {
        return enrich(roomTypes.findAllActiveWithRoomCount());
    }

    @Transactional(readOnly = true)
    public RoomTypeResponse getRoomType(Long id) {
        return enrich(java.util.List.of(roomTypes.findActiveByIdWithRoomCount(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loai phong khong ton tai")))).getFirst();
    }

    // ── Admin: RoomType ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoomTypeResponse> adminListAllRoomTypes() {
        return enrich(roomTypes.findAllWithRoomCount());
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
        // Dung findByIdWithRoomCount (ke ca inactive) thay vi findActiveByIdWithRoomCount
        // vi RoomType moi tao co the chua duoc flush vao query active
        return enrich(java.util.List.of(roomTypes.findByIdWithRoomCount(rt.getId()).orElseThrow())).getFirst();
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
        roomTypes.saveAndFlush(rt); // flush de JPQL query ben duoi thay du lieu moi nhat
        return enrich(java.util.List.of(roomTypes.findByIdWithRoomCount(id).orElseThrow())).getFirst();
    }

    @Transactional
    public void deactivateRoomType(Long id) {
        RoomType rt = roomTypes.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loai phong khong ton tai"));

        // Dung COUNT query thay .size() de khong load ca list vao memory
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

        if (physicalRooms.existsByRoomNumberIgnoreCase(req.roomNumber().strip())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_NUMBER_DUPLICATE",
                    "So phong '" + req.roomNumber() + "' da ton tai");
        }

        PhysicalRoom room = new PhysicalRoom(rt, req.roomNumber().strip(), req.floorNumber());
        physicalRooms.saveAndFlush(room);
        return PhysicalRoomResponse.from(room);
    }

    @Transactional
    public PhysicalRoomResponse updatePhysicalRoom(Long id, UpdatePhysicalRoomRequest req) {
        PhysicalRoom room = physicalRooms.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PHYSICAL_ROOM_NOT_FOUND", "Phong khong ton tai"));

        if (physicalRooms.existsByRoomNumberIgnoreCaseAndIdNot(req.roomNumber().strip(), id)) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_NUMBER_DUPLICATE",
                    "So phong '" + req.roomNumber() + "' da ton tai");
        }

        room.setRoomNumber(req.roomNumber().strip());
        room.setFloorNumber(req.floorNumber());
        physicalRooms.save(room);
        return PhysicalRoomResponse.from(room);
    }

    @Transactional
    public PhysicalRoomResponse createPhysicalRoomWithImages(CreatePhysicalRoomRequest req,
            List<org.springframework.web.multipart.MultipartFile> files, int coverIndex, boolean replaceGallery) {
        var validated = images.validate(files, coverIndex);
        roomTypes.lockById(req.roomTypeId()).filter(RoomType::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Hạng phòng không tồn tại hoặc đã ẩn"));
        PhysicalRoomResponse created = createPhysicalRoom(req);
        images.replace(req.roomTypeId(), validated, coverIndex, replaceGallery);
        return created;
    }

    private List<RoomTypeResponse> enrich(List<RoomTypeResponse> types) {
        var metadata = images.metadata(types.stream().map(RoomTypeResponse::id).toList());
        return types.stream().map(type -> type.withImages(metadata.getOrDefault(type.id(), List.of()))).toList();
    }

    @Transactional
    public void deactivatePhysicalRoom(Long id) {
        PhysicalRoom room = physicalRooms.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PHYSICAL_ROOM_NOT_FOUND", "Phong khong ton tai"));

        // Khong cho an phong dang co booking active -- bao toan du lieu booking
        if (physicalRooms.hasActiveBookings(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_HAS_ACTIVE_BOOKINGS",
                    "Khong the an phong dang co booking PENDING_PAYMENT hoac CONFIRMED");
        }

        room.setActive(false);
        physicalRooms.save(room);
    }
}
