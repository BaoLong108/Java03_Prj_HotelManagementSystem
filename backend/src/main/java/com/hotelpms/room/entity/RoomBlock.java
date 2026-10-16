package com.hotelpms.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * Block mot khoang thoi gian cua mot phong vat ly (bao tri, giu cho su kien, ...).
 *
 * Moi block co hieu luc tu start_at den end_at (khong bao gom dau end).
 * DB dam bao khong co 2 block active overlap nhau tren cung 1 phong
 * bang EXCLUDE USING gist constraint.
 *
 * Vong doi: ACTIVE (released_at IS NULL) -> RELEASED (released_at IS NOT NULL).
 * Khong xoa cung de bao toan lich su.
 */
@Entity
@Table(name = "room_blocks")
public class RoomBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Phong vat ly bi block. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "physical_room_id", nullable = false, updatable = false)
    private PhysicalRoom physicalRoom;

    /** Thoi diem bat dau block (inclusive). */
    @Column(name = "start_at", nullable = false, updatable = false)
    private Instant startAt;

    /** Thoi diem ket thuc block (exclusive). Phai sau start_at -- dam bao boi DB constraint. */
    @Column(name = "end_at", nullable = false, updatable = false)
    private Instant endAt;

    /** Ly do block: "Bao tri dinh ky", "Su kien VIP", "Don dep sau su co"... */
    @NotBlank
    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** Nhan vien tao block -- luu id de tranh phu thuoc vong vao auth module. */
    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Nhan vien mo block (giai phong phong som hon ke hoach).
     * Null neu chua duoc giai phong.
     */
    @Column(name = "released_by_user_id")
    private Long releasedByUserId;

    /** Thoi diem giai phong. Null neu con hieu luc (ACTIVE). */
    @Column(name = "released_at")
    private Instant releasedAt;

    protected RoomBlock() { }

    /**
     * Tao block moi.
     *
     * @param physicalRoom phong can block
     * @param startAt      bat dau block (inclusive)
     * @param endAt        ket thuc block (exclusive) -- phai sau startAt
     * @param reason       ly do block
     * @param createdByUserId id nhan vien tao
     */
    public RoomBlock(PhysicalRoom physicalRoom, Instant startAt, Instant endAt,
                     String reason, Long createdByUserId) {
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt phai sau startAt");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Ly do block khong duoc de trong");
        }
        this.physicalRoom = physicalRoom;
        this.startAt = startAt;
        this.endAt = endAt;
        this.reason = reason.strip();
        this.createdByUserId = createdByUserId;
        this.createdAt = Instant.now();
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    /** True neu block nay con hieu luc (chua duoc giai phong). */
    public boolean isActive() {
        return releasedAt == null;
    }

    /** True neu khoang thoi gian [from, to) co overlap voi block nay (khi block con active). */
    public boolean overlaps(Instant from, Instant to) {
        return isActive() && startAt.isBefore(to) && endAt.isAfter(from);
    }

    // ── Command ────────────────────────────────────────────────────────────────

    /**
     * Giai phong phong (mo block som hon ke hoach).
     * Idempotent: goi nhieu lan khong co tac dung gi them.
     *
     * @param releasedByUserId id nhan vien mo block
     */
    public void release(Long releasedByUserId) {
        if (this.releasedAt != null) return; // da duoc giai phong roi
        this.releasedByUserId = releasedByUserId;
        this.releasedAt = Instant.now();
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public PhysicalRoom getPhysicalRoom() { return physicalRoom; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public String getReason() { return reason; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getReleasedByUserId() { return releasedByUserId; }
    public Instant getReleasedAt() { return releasedAt; }
}