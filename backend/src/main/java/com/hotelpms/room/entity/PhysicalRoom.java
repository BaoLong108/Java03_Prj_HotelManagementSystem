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
 * Phòng vật lý thực tế trong khách sạn.
 * Mỗi phòng thuộc đúng một {@link RoomType}; số phòng là duy nhất trong toàn bộ khách sạn.
 * Không lộ số phòng ra ngoài API khách hàng — chỉ dùng nội bộ khi tạo booking.
 */
@Entity
@Table(name = "physical_rooms")
public class PhysicalRoom extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Loại phòng mà phòng này thuộc về.
     * Không thay đổi sau khi tạo; muốn đổi loại phải deactivate và tạo phòng mới.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false, updatable = false)
    private RoomType roomType;

    /** Số phòng hiển thị cho nhân viên (101, 202A…). Duy nhất trong toàn khách sạn. */
    @Column(name = "room_number", nullable = false, unique = true, length = 30)
    private String roomNumber;

    /** Tầng của phòng; null nếu không áp dụng. */
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

    public void setFloorNumber(Short floorNumber) { this.floorNumber = floorNumber; }
    public void setActive(boolean active) { this.active = active; }
}
