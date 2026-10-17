package com.hotelpms.room.adapter;

import com.hotelpms.pricing.port.RoomTypePricingPort;
import com.hotelpms.room.repository.RoomTypeRepository;
import com.hotelpms.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Connects the existing Room data to the Pricing contract without changing quote rules. */
@Component
public class RoomTypePricingAdapter implements RoomTypePricingPort {
    private final RoomTypeRepository roomTypes;

    public RoomTypePricingAdapter(RoomTypeRepository roomTypes) { this.roomTypes = roomTypes; }

    @Override
    @Transactional(readOnly = true)
    public RoomTypeInfo findActiveById(Long roomTypeId) {
        var roomType = roomTypes.findById(roomTypeId).filter(type -> type.isActive())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại"));
        return new RoomTypeInfo(roomType.getId(), roomType.getBasePricePerNight(),
                roomType.getMaxOccupancy(), roomType.isActive());
    }
}
