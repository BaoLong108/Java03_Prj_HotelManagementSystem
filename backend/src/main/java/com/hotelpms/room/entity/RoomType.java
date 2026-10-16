package com.hotelpms.room.entity;

import com.hotelpms.shared.model.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loại phòng (Standard, Deluxe, Suite…). Định nghĩa tiện nghi, sức chứa và giá gốc.
 * Phòng vật lý thực tế được quản lý riêng qua {@link PhysicalRoom}.
 */
@Entity
@Table(name = "room_types")
public class RoomType extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã loại phòng — duy nhất, viết hoa, ví dụ: STANDARD, DELUXE, SUITE. */
    @NotBlank
    @Size(max = 30)
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Số khách tối đa cho loại phòng này. */
    @Positive
    @Column(name = "max_occupancy", nullable = false)
    private short maxOccupancy;

    /** Giá gốc mỗi đêm tính bằng VND (snapshot tại thời điểm tạo booking). */
    @DecimalMin("0.0")
    @Column(name = "base_price_per_night", nullable = false, precision = 15, scale = 2)
    private BigDecimal basePricePerNight;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "roomType", fetch = FetchType.LAZY)
    private List<PhysicalRoom> physicalRooms = new ArrayList<>();

    protected RoomType() { }

    public RoomType(String code, String name, String description,
                    short maxOccupancy, BigDecimal basePricePerNight) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.maxOccupancy = maxOccupancy;
        this.basePricePerNight = basePricePerNight;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public short getMaxOccupancy() { return maxOccupancy; }
    public BigDecimal getBasePricePerNight() { return basePricePerNight; }
    public boolean isActive() { return active; }
    public List<PhysicalRoom> getPhysicalRooms() { return Collections.unmodifiableList(physicalRooms); }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setMaxOccupancy(short maxOccupancy) { this.maxOccupancy = maxOccupancy; }
    public void setBasePricePerNight(BigDecimal basePricePerNight) { this.basePricePerNight = basePricePerNight; }
    public void setActive(boolean active) { this.active = active; }
}
