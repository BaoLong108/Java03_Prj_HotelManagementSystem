package com.hotelpms.booking.checkin;

import java.time.Instant;

public record CheckInRequest(
        long id, Instant estimatedArrivalAt, boolean earlyCheckInRequested, String arrivalNote,
        Status status, Long reviewedBy, Instant reviewedAt, String rejectionReason,
        Instant createdAt, Instant updatedAt
) {
    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED, INVALIDATED }
    public enum Decision { APPROVED, REJECTED }

    CustomerView forCustomer() {
        return new CustomerView(id, estimatedArrivalAt, earlyCheckInRequested, arrivalNote, status, reviewedAt,
                rejectionReason, createdAt, updatedAt);
    }

    record CustomerView(long id, Instant estimatedArrivalAt, boolean earlyCheckInRequested, String arrivalNote,
                        Status status, Instant reviewedAt, String rejectionReason, Instant createdAt, Instant updatedAt) {}
}
