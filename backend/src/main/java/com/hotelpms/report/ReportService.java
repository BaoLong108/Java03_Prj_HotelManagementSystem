package com.hotelpms.report;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReportService {
    private final JdbcTemplate jdbc;
    public ReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Summary(LocalDate from, LocalDate to, String revenue, long soldRoomNights,
            long availableRoomNights, BigDecimal occupancyRate, String revpar,
            Map<String, Long> bookingCounts, long pendingHolds, long completedStays, List<ReportMetrics.Day> daily) { }
    public record History(long id, long roomId, String roomNumber, Instant startAt, Instant endAt, String reason,
            String createdByName, Instant createdAt, String releasedByName, Instant releasedAt, boolean active) { }
    static Timestamp ts(Instant value) { return Timestamp.from(value); }
    public Summary summary(String from, String to) {
        var p = ReportFilters.period(from, to);
        Set<Long> rooms = new HashSet<>(jdbc.queryForList("SELECT id FROM physical_rooms WHERE is_active", Long.class));
        var stays = jdbc.query("SELECT start_at,end_at FROM bookings WHERE status IN ('CONFIRMED','COMPLETED') AND start_at < ? AND end_at > ?",
                (rs, n) -> new ReportMetrics.Stay(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), ts(p.end()), ts(p.start()));
        var blocks = jdbc.query("SELECT physical_room_id,start_at,end_at FROM room_blocks WHERE released_at IS NULL AND start_at < ? AND end_at > ?",
                (rs, n) -> new ReportMetrics.Block(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()),
                ts(p.to().atTime(10, 0).atZone(ReportFilters.ZONE).toInstant()), ts(p.start()));
        Map<LocalDate, BigDecimal> revenues = new HashMap<>();
        jdbc.query("""
                SELECT (succeeded_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day, SUM(requested_amount) AS amount
                FROM payment_attempts WHERE status='SUCCEEDED' AND succeeded_at >= ? AND succeeded_at < ? GROUP BY day
                """, rs -> { revenues.put(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)); }, ts(p.start()), ts(p.end()));
        Map<String, Long> counts = new TreeMap<>();
        ReportFilters.STATUSES.forEach(status -> counts.put(status, 0L));
        jdbc.query("SELECT status,COUNT(*) FROM bookings WHERE created_at >= ? AND created_at < ? GROUP BY status",
                rs -> { counts.put(rs.getString(1), rs.getLong(2)); }, ts(p.start()), ts(p.end()));
        long holds = jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE status='PENDING_PAYMENT' AND hold_expires_at > ?", Long.class, ts(Instant.now()));
        long completed = jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE status='COMPLETED' AND end_at >= ? AND end_at < ?", Long.class, ts(p.start()), ts(p.end()));
        var daily = ReportMetrics.days(p, rooms, stays, blocks, revenues);
        long sold = daily.stream().mapToLong(ReportMetrics.Day::soldRoomNights).sum();
        long available = daily.stream().mapToLong(ReportMetrics.Day::availableRoomNights).sum();
        BigDecimal revenue = revenues.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(p.from(), p.to(), revenue.toPlainString(), sold, available, ReportMetrics.rate(sold, available),
                ReportMetrics.revpar(revenue, available), Map.copyOf(counts), holds, completed, daily);
    }
    public List<History> history(String from, String to, String roomId) {
        var p = ReportFilters.period(from, to); var room = ReportFilters.id(roomId);
        return jdbc.query("""
                SELECT b.*, r.room_number, c.full_name AS creator, u.full_name AS releaser
                FROM room_blocks b JOIN physical_rooms r ON r.id=b.physical_room_id
                JOIN app_users c ON c.id=b.created_by_user_id LEFT JOIN app_users u ON u.id=b.released_by_user_id
                WHERE b.start_at < ? AND b.end_at > ? AND (?::bigint IS NULL OR b.physical_room_id=?)
                ORDER BY b.start_at, b.id
                """, (rs, n) -> new History(rs.getLong("id"), rs.getLong("physical_room_id"), rs.getString("room_number"),
                        rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(), rs.getString("reason"),
                        rs.getString("creator"), rs.getTimestamp("created_at").toInstant(), rs.getString("releaser"),
                        rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(), rs.getTimestamp("released_at") == null),
                ts(p.end()), ts(p.start()), room, room);
    }
}
