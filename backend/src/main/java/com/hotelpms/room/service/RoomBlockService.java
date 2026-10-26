package com.hotelpms.room.service;

import com.hotelpms.room.dto.RoomBlockRequest;
import com.hotelpms.room.dto.RoomBlockResponse;
import com.hotelpms.room.entity.PhysicalRoom;
import com.hotelpms.room.entity.RoomBlock;
import com.hotelpms.room.repository.PhysicalRoomRepository;
import com.hotelpms.room.repository.RoomBlockRepository;
import com.hotelpms.shared.api.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomBlockService {

    private final RoomBlockRepository blocks;
    private final PhysicalRoomRepository rooms;
    private final com.hotelpms.booking.service.RoomAllocationService allocator;
    private final com.hotelpms.booking.repository.BookingRepository bookings;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public RoomBlockService(RoomBlockRepository blocks, PhysicalRoomRepository rooms,
            com.hotelpms.booking.service.RoomAllocationService allocator,
            com.hotelpms.booking.repository.BookingRepository bookings, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.blocks = blocks;
        this.rooms = rooms;
        this.allocator = allocator;
        this.bookings = bookings;
        this.jdbc = jdbc;
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoomBlockResponse> listActiveBlocks(Long roomId) {
        requireRoom(roomId);
        return blocks.findActiveByRoomId(roomId)
                .stream().map(RoomBlockResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RoomBlockResponse> listAllBlocks(Long roomId) {
        requireRoom(roomId);
        return blocks.findAllByRoomIdOrderByStartAtDesc(roomId)
                .stream().map(RoomBlockResponse::from).toList();
    }

    /** GET chi tiet mot block theo id. */
    @Transactional(readOnly = true)
    public RoomBlockResponse getBlock(Long blockId) {
        return blocks.findByIdWithRoom(blockId)
                .map(RoomBlockResponse::from)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND", "Block khong ton tai"));
    }

    // ── Command ────────────────────────────────────────────────────────────────

    @Transactional
    public RoomBlockResponse createBlock(RoomBlockRequest req, Long staffId) {
        if (!req.endAt().isAfter(req.startAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BLOCK_PERIOD",
                    "Thoi gian ket thuc phai sau thoi gian bat dau");
        }

        allocator.lockPhysicalRoom(req.physicalRoomId());
        if (bookings.hasActiveBookingOverlap(req.physicalRoomId(), req.startAt(), req.endAt(), Instant.now()))
            throw new ApiException(HttpStatus.CONFLICT, "BOOKING_OVERLAP", "Phòng đã có booking hoặc hold còn hạn");
        PhysicalRoom room = rooms.findById(req.physicalRoomId())
                .filter(PhysicalRoom::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "PHYSICAL_ROOM_NOT_FOUND", "Phong khong ton tai hoac da bi an"));

        // findOverlapping tra ve list kem thong tin conflict ro rang cho client
        List<RoomBlock> overlapping = blocks.findOverlapping(
                req.physicalRoomId(), req.startAt(), req.endAt());
        if (!overlapping.isEmpty()) {
            RoomBlock conflict = overlapping.get(0);
            throw new ApiException(HttpStatus.CONFLICT, "BLOCK_OVERLAP",
                    String.format("Phong da bi block tu %s den %s (ly do: %s)",
                            conflict.getStartAt(), conflict.getEndAt(), conflict.getReason()));
        }

        RoomBlock block = new RoomBlock(room, req.startAt(), req.endAt(),
                req.reason(), staffId);
        blocks.save(block);
        return RoomBlockResponse.from(block);
    }

    @Transactional
    public RoomBlockResponse releaseBlock(Long blockId, Long staffId) {
        var ids = jdbc.queryForList("SELECT physical_room_id FROM room_blocks WHERE id=?", Long.class, blockId);
        if (ids.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND", "Không tìm thấy block");
        allocator.lockPhysicalRoom(ids.getFirst());
        RoomBlock block = blocks.findByIdWithRoom(blockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "BLOCK_NOT_FOUND", "Block khong ton tai"));

        if (!block.isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "BLOCK_ALREADY_RELEASED",
                    "Block nay da duoc giai phong roi");
        }

        block.release(staffId);
        blocks.save(block);
        return RoomBlockResponse.from(block);
    }

    // ── Internal: dung boi booking service ────────────────────────────────────

    /**
     * Nem ApiException neu phong bi block trong khoang thoi gian.
     * Read-only check. Commands must also lock the physical room before checking overlaps.
     */
    @Transactional(readOnly = true)
    public void assertRoomNotBlocked(Long roomId, Instant from, Instant to) {
        List<RoomBlock> conflicts = blocks.findOverlapping(roomId, from, to);
        if (!conflicts.isEmpty()) {
            RoomBlock first = conflicts.get(0);
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_BLOCKED",
                    String.format("Phong bi block tu %s den %s: %s",
                            first.getStartAt(), first.getEndAt(), first.getReason()));
        }
    }

    private void requireRoom(Long roomId) {
        if (!rooms.existsById(roomId)) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "PHYSICAL_ROOM_NOT_FOUND", "Phong khong ton tai");
        }
    }
}
