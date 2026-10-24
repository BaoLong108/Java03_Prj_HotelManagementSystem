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

    public RoomBlockService(RoomBlockRepository blocks, PhysicalRoomRepository rooms) {
        this.blocks = blocks;
        this.rooms = rooms;
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
     * Chi goi 1 query duy nhat (findOverlapping) -- khong con race condition.
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