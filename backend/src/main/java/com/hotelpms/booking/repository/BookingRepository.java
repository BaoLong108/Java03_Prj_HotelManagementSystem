package com.hotelpms.booking.repository;

import com.hotelpms.booking.entity.Booking;
import com.hotelpms.booking.entity.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingCode(String bookingCode);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.bookingCode = :bookingCode")
    Optional<Booking> findByBookingCodeForUpdate(@Param("bookingCode") String bookingCode);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    Optional<Booking> findByIdAndCustomerId(Long id, Long customerId);

    Optional<Booking> findByBookingCodeAndCustomerId(String bookingCode, Long customerId);

    List<Booking> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Booking> findByStatusAndHoldExpiresAtBefore(BookingStatus status, Instant cutoff);

    List<Booking> findByStatusAndEndAtLessThanEqual(BookingStatus status, Instant cutoff);

    @Query("""
        SELECT COUNT(b) > 0 FROM Booking b
        WHERE b.physicalRoomId = :physicalRoomId
          AND (
              b.status IN (com.hotelpms.booking.entity.BookingStatus.CONFIRMED, com.hotelpms.booking.entity.BookingStatus.COMPLETED)
              OR (b.status = com.hotelpms.booking.entity.BookingStatus.PENDING_PAYMENT AND b.holdExpiresAt > :now)
          )
          AND b.startAt < :endAt
          AND b.endAt > :startAt
    """)
    boolean hasActiveBookingOverlap(
        @Param("physicalRoomId") Long physicalRoomId,
        @Param("startAt") Instant startAt,
        @Param("endAt") Instant endAt,
        @Param("now") Instant now
    );

    @Query("""
        SELECT b FROM Booking b
        WHERE b.physicalRoomId = :physicalRoomId
          AND (
              b.status IN (com.hotelpms.booking.entity.BookingStatus.CONFIRMED, com.hotelpms.booking.entity.BookingStatus.COMPLETED)
              OR (b.status = com.hotelpms.booking.entity.BookingStatus.PENDING_PAYMENT AND b.holdExpiresAt > :now)
          )
          AND b.startAt < :endAt
          AND b.endAt > :startAt
    """)
    List<Booking> findActiveOverlappingBookings(
        @Param("physicalRoomId") Long physicalRoomId,
        @Param("startAt") Instant startAt,
        @Param("endAt") Instant endAt,
        @Param("now") Instant now
    );

    @Query("""
        SELECT DISTINCT b.physicalRoomId FROM Booking b
        WHERE b.roomTypeId = :roomTypeId
          AND (
              b.status IN (com.hotelpms.booking.entity.BookingStatus.CONFIRMED, com.hotelpms.booking.entity.BookingStatus.COMPLETED)
              OR (b.status = com.hotelpms.booking.entity.BookingStatus.PENDING_PAYMENT AND b.holdExpiresAt > :now)
          )
          AND b.startAt < :endAt
          AND b.endAt > :startAt
    """)
    List<Long> findOccupiedPhysicalRoomIds(
        @Param("roomTypeId") Long roomTypeId,
        @Param("startAt") Instant startAt,
        @Param("endAt") Instant endAt,
        @Param("now") Instant now
    );
}
