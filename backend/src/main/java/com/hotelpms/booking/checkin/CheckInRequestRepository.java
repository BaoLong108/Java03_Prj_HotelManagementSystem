package com.hotelpms.booking.checkin;

import com.hotelpms.booking.entity.BookingStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CheckInRequestRepository {
    private static final String PROJECTION = """
            SELECT r.*, b.booking_code, b.status AS booking_status, b.start_at, b.end_at,
                rt.name AS room_type_name, b.guest_count,
                CASE WHEN r.status = 'PENDING' AND (b.status <> 'CONFIRMED' OR b.end_at <= :now)
                    THEN 'INVALIDATED' ELSE r.status END AS effective_status
            FROM check_in_requests r JOIN bookings b ON b.id = r.booking_id
            JOIN room_types rt ON rt.id = b.room_type_id
            """;
    private final NamedParameterJdbcTemplate jdbc;

    CheckInRequestRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<CheckInRequest> history(long bookingId, Instant now) {
        return jdbc.query(PROJECTION + " WHERE r.booking_id = :booking ORDER BY r.id DESC LIMIT 50",
                now(now).addValue("booking", bookingId), (rs, n) -> request(rs));
    }

    Optional<CheckInRequest> pending(long bookingId, Instant now) {
        return jdbc.query(PROJECTION + " WHERE r.booking_id = :booking AND r.status = 'PENDING'",
                now(now).addValue("booking", bookingId), (rs, n) -> request(rs)).stream().findFirst();
    }

    Optional<CheckInService.QueueItem> find(long id, Instant now) {
        return jdbc.query(PROJECTION + " WHERE r.id = :id", now(now).addValue("id", id),
                (rs, n) -> queueItem(rs)).stream().findFirst();
    }

    List<CheckInService.QueueItem> list(CheckInRequest.Status status, String code, int page, int size, Instant now) {
        var parameters = now(now).addValue("status", status == null ? null : status.name(), java.sql.Types.VARCHAR)
                .addValue("code", "%" + code.toUpperCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%")
                .addValue("limit", size + 1).addValue("offset", (long) page * size);
        return jdbc.query("SELECT * FROM (" + PROJECTION + """
                ) queue
                WHERE (:status IS NULL OR queue.effective_status = :status)
                    AND upper(queue.booking_code) LIKE :code ESCAPE '!'
                ORDER BY queue.id DESC LIMIT :limit OFFSET :offset
                """, parameters, (rs, n) -> queueItem(rs));
    }

    long insert(long bookingId, Instant estimated, boolean early, String note, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO check_in_requests (booking_id, estimated_arrival_at, early_check_in_requested, arrival_note, created_at, updated_at)
                VALUES (:booking, :estimated, :early, :note, :now, :now) RETURNING id
                """, now(now).addValue("booking", bookingId).addValue("estimated", estimated.atOffset(ZoneOffset.UTC))
                .addValue("early", early).addValue("note", note), Long.class);
    }

    boolean transition(long id, CheckInRequest.Status status, Long reviewer, String rejection, Instant now) {
        return jdbc.update("""
                UPDATE check_in_requests SET status = :status, reviewed_by = :reviewer,
                    reviewed_at = CASE WHEN :reviewer IS NULL THEN NULL ELSE :now END,
                    rejection_reason = :rejection, updated_at = :now
                WHERE id = :id AND status = 'PENDING'
                """, now(now).addValue("id", id).addValue("status", status.name())
                .addValue("reviewer", reviewer, java.sql.Types.BIGINT).addValue("rejection", rejection)) == 1;
    }

    private static MapSqlParameterSource now(Instant now) {
        return new MapSqlParameterSource("now", now.atOffset(ZoneOffset.UTC));
    }

    private static CheckInRequest request(ResultSet rs) throws SQLException {
        return new CheckInRequest(rs.getLong("id"), instant(rs, "estimated_arrival_at"), rs.getBoolean("early_check_in_requested"),
                rs.getString("arrival_note"), CheckInRequest.Status.valueOf(rs.getString("effective_status")),
                rs.getObject("reviewed_by", Long.class), instant(rs, "reviewed_at"), rs.getString("rejection_reason"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static CheckInService.QueueItem queueItem(ResultSet rs) throws SQLException {
        return new CheckInService.QueueItem(rs.getLong("booking_id"), rs.getString("booking_code"),
                BookingStatus.valueOf(rs.getString("booking_status")), rs.getString("room_type_name"),
                instant(rs, "start_at"), instant(rs, "end_at"), rs.getInt("guest_count"), request(rs));
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        var timestamp = rs.getTimestamp(field);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
