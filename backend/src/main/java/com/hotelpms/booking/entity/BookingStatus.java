package com.hotelpms.booking.entity;

/**
 * Lifecycle status of a booking, matching check constraint ck_bookings_status.
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    EXPIRED
}
