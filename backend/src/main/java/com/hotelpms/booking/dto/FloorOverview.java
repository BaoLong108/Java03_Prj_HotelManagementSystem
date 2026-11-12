package com.hotelpms.booking.dto;

import com.hotelpms.booking.entity.BookingStatus;
import java.time.Instant;
import java.util.List;

public record FloorOverview(Instant asOf, List<Floor> floors) {
    public record Floor(Integer floorNumber, List<Room> rooms) { }

    public record Room(long roomId, String roomNumber, long roomTypeId, String roomTypeName,
                       Status operationalStatus, Block block, Assignment assignment, Instant updatedAt) { }

    public enum Status { AVAILABLE, ASSIGNED, HELD, BLOCKED, OUT_OF_SERVICE }

    public record Block(long id, Instant startAt, Instant endAt, String reason) { }

    public record Assignment(String bookingCode, BookingStatus status, Instant startAt, Instant endAt) { }
}
