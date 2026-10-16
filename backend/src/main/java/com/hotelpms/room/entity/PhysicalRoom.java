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
 * PhÃ²ng váº­t lÃ½ thá»±c táº¿ trong khÃ¡ch sáº¡n.
 * Má»—i phÃ²ng thuá»™c Ä‘Ãºng má»™t {@link RoomType}; sá»‘ phÃ²ng lÃ  duy nháº¥t trong toÃ n bá»™ khÃ¡ch sáº¡n.
 * KhÃ´ng lá»™ sá»‘ phÃ²ng ra ngoÃ i API khÃ¡ch hÃ ng â€” chá»‰ dÃ¹ng ná»™i bá»™ khi táº¡o booking.
 */
@Entity
@Table(name = "physical_rooms")
public class PhysicalRoom extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Loáº¡i phÃ²ng mÃ  phÃ²ng nÃ y thuá»™c vá».
     * KhÃ´ng thay Ä‘á»•i sau khi táº¡o; muá»‘n Ä‘á»•i loáº¡i pháº£i deactivate vÃ  táº¡o phÃ²ng má»›i.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false, updatable = false)
    private RoomType roomType;

    /** Sá»‘ phÃ²ng hiá»ƒn thá»‹ cho nhÃ¢n viÃªn (101, 202Aâ€¦). Duy nháº¥t trong toÃ n khÃ¡ch sáº¡n. */
    @Column(name = "room_number", nullable = false, unique = true, length = 30)
    private String roomNumber;

    /** Táº§ng cá»§a phÃ²ng; null náº¿u khÃ´ng Ã¡p dá»¥ng. */
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
