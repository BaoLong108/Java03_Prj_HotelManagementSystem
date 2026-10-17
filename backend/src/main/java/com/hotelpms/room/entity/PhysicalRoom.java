package com.hotelpms.room.entity;

import com.hotelpms.shared.model.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Phong vat ly thuc te trong khach san.
 * Moi phong thuoc dung mot {@link RoomType}.
 * So phong la duy nhat trong toan bo khach san.
 * Khong lo so phong ra ngoai public API -- chi nhan vien moi thay.
 */
@Entity
@Table(name = "physical_rooms")
public class PhysicalRoom extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Loai phong ma phong nay thuoc ve.
     * Khong thay doi sau khi tao -- muon doi loai phai deactivate va tao phong moi.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false, updatable = false)
    private RoomType roomType;

    /** So phong hien thi cho nhan vien (101, 202A...). Duy nhat trong toan khach san. */
    @Column(name = "room_number", nullable = false, unique = true, length = 30)
    private String roomNumber;

    /** Tang cua phong; null neu khong ap dung. */
    @Column(name = "floor_number")
    private Short floorNumber;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected PhysicalRoom() { }

    public PhysicalRoom(RoomType roomType, String roomNumber, Short floorNumber) {
        this.roomType = roomType;
        this.roomNumber = roomNumber;
        this.floorNumber = floorNumber;
    }

    public Long getId() { return id; }
    public RoomType getRoomType() { return roomType; }
    public String getRoomNumber() { return roomNumber; }
    public Short getFloorNumber() { return floorNumber; }
    public boolean isActive() { return active; }

    /** So phong co the doi duoc (sua sai chinh ta / danh so lai). */
    public void setRoomNumber(String roomNumber) { this.roomNumber = roomNumber; }
    public void setFloorNumber(Short floorNumber) { this.floorNumber = floorNumber; }
    public void setActive(boolean active) { this.active = active; }
}