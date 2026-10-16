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

    /**
     * Lay cac block con hieu luc cua mot phong.
     * Dung cho nhan vien xem lich block.
     */
    @Transactional(readOnly = true)
    public List<RoomBlockResponse> listActiveBlocks(Long roomId) {
        requireRoom(roomId);
        return blocks.findActiveByRoomId(roomId)
                .stream().map(RoomBlockResponse::from).toList();
    }

    /**
     * Lay toan bo lich su block (ca released) cua mot phong.
     */
    @Transactional(readOnly = true)
    public List<RoomBlockResponse> listAllBlocks(Long roomId) {
        requireRoom(roomId);
        return blocks.findAllByRoomIdOrderByStartAtDesc(roomId)
                .stream().map(RoomBlockResponse::from).toList();
    }

    /**
     * Kiem tra phong co kha dung trong khoang thoi gian khong.
     * Tra ve true neu phong KHONG bi block trong [from, to).
     */
    @Transactional(readOnly = true)
    public boolean isRoomAvailable(Long roomId, Instant from, Instant to) {
        return !blocks.isRoomBlocked(roomId, from, to);
    }

    // ── Command ────────────────────────────────────────────────────────────────

    /**
     * Tao block moi cho phong.
     * Kiem tra:
     *   - Phong phai ton tai va dang active
     *   - endAt phai sau startAt
     *   - Khong co block nao overlap trong khoang thoi gian do (DB dam bao them qua EXCLUDE constraint)
     *
     * @param req     thong tin block
     * @param staffId id nhan vien tao block
     */
    @Transactional
    public RoomBlockResponse createBlock(RoomBlockRequest req, Long staffId) {
        // Validate thoi gian
        if (!req.endAt().isAfter(req.startAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BLOCK_PERIOD",
                    "Thoi gian ket thuc phai sau thoi gian bat dau");
        }

        PhysicalRoom room = rooms.findById(req.physicalRoomId())
                .filter(PhysicalRoom::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "PHYSICAL_ROOM_NOT_FOUND", "Phong khong ton tai hoac da bi an"));

        // Kiem tra overlap truoc khi insert (DB cung co EXCLUDE constraint lam net san)
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

    /**
     * Giai phong phong (mo block som hon ke hoach).
     *
     * @param blockId id block can giai phong
     * @param staffId id nhan vien thuc hien
     */
    @Transactional
    public RoomBlockResponse releaseBlock(Long blockId, Long staffId) {
        RoomBlock block = blocks.findById(blockId)
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

    // ── Internal helper ────────────────────────────────────────────────────────

    /** Dung boi booking service de kiem tra truoc khi assign phong. */
    @Transactional(readOnly = true)
    public void assertRoomNotBlocked(Long roomId, Instant from, Instant to) {
        if (blocks.isRoomBlocked(roomId, from, to)) {
            List<RoomBlock> conflicts = blocks.findOverlapping(roomId, from, to);
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