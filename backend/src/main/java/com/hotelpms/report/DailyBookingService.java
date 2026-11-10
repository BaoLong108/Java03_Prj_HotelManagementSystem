package com.hotelpms.report;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DailyBookingService {
    private final JdbcTemplate jdbc;
    public DailyBookingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Contact(String phoneMasked, String emailMasked) { }
    public record BookingRow(String bookingCode, String status, long roomTypeId, String roomTypeName,
            String roomNumber, Instant startAt, Instant endAt, int guestCount, Instant holdExpiresAt,
            String payableAmount, Contact customer) { }
    public record DailyResult(List<BookingRow> items, int limit, boolean truncated) { }

    static Contact mask(String phone, String email) {
        String digits = Objects.toString(phone, "").replaceAll("[^0-9]", "");
        String address = Objects.toString(email, "");
        int at = address.indexOf('@');
        return new Contact("*******" + digits.substring(Math.max(0, digits.length() - 3)),
                at > 0 ? address.substring(0, 1) + "***" + address.substring(at) : "***");
    }

    public DailyResult daily(String date, String type, String status) {
        var day = ReportFilters.date(date);
        Long typeId = ReportFilters.id(type);
        String state = ReportFilters.status(status);
        var items = jdbc.query("""
                SELECT b.booking_code, b.status, b.room_type_id, t.name AS room_type_name, r.room_number,
                       b.start_at, b.end_at, b.guest_count, b.hold_expires_at, b.payable_amount,
                       cp.phone_number, u.email
                FROM bookings b JOIN room_types t ON t.id=b.room_type_id
                JOIN physical_rooms r ON r.id=b.physical_room_id
                JOIN customer_profiles cp ON cp.user_id=b.customer_id JOIN app_users u ON u.id=cp.user_id
                WHERE b.start_at < ? AND b.end_at > ?
                  AND (?::bigint IS NULL OR b.room_type_id=?) AND (?::text IS NULL OR b.status=?)
                ORDER BY b.start_at, b.id LIMIT 501
                """, (rs, n) -> new BookingRow(rs.getString("booking_code"), rs.getString("status"),
                        rs.getLong("room_type_id"), rs.getString("room_type_name"), rs.getString("room_number"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                        rs.getInt("guest_count"), rs.getTimestamp("hold_expires_at").toInstant(),
                        rs.getBigDecimal("payable_amount").toPlainString(), mask(rs.getString("phone_number"), rs.getString("email"))),
                Timestamp.from(day.plusDays(1).atStartOfDay(ReportFilters.ZONE).toInstant()),
                Timestamp.from(day.atStartOfDay(ReportFilters.ZONE).toInstant()), typeId, typeId, state, state);
        return new DailyResult(items.stream().limit(500).toList(), 500, items.size() > 500);
    }
}
