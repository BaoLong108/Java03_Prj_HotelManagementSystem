package com.hotelpms.analytics;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
class RevenueAnalyticsRepository {
    private static final String CREATED = """
            b.created_at >= :begin AND b.created_at < :end
            AND (CAST(:roomTypeId AS bigint) IS NULL OR b.room_type_id = :roomTypeId)
            """;
    private static final String AGGREGATES = """
            WITH events AS (
                SELECT (b.created_at AT TIME ZONE :zone)::date AS day, b.room_type_id,
                    b.status, 'CREATED' AS event, 0::numeric AS amount,
                    0::numeric AS membership, 0::numeric AS voucher
                FROM bookings b WHERE
            """ + CREATED + """
                UNION ALL
                SELECT (b.cancelled_at AT TIME ZONE :zone)::date, b.room_type_id,
                    b.status, 'CANCELLED', 0, 0, 0
                FROM bookings b
                WHERE b.cancelled_at >= :begin AND b.cancelled_at < :end
                    AND (CAST(:roomTypeId AS bigint) IS NULL OR b.room_type_id = :roomTypeId)
                UNION ALL
                SELECT (p.succeeded_at AT TIME ZONE :zone)::date, b.room_type_id,
                    b.status, 'SETTLED', p.requested_amount, b.discount_amount, b.voucher_discount_amount
                FROM payment_attempts p JOIN bookings b ON b.id = p.booking_id
                WHERE p.provider = 'DEMO' AND p.status = 'SUCCEEDED'
                    AND p.succeeded_at >= :begin AND p.succeeded_at < :end
                    AND (CAST(:roomTypeId AS bigint) IS NULL OR b.room_type_id = :roomTypeId)
            )
            SELECT e.day, e.room_type_id, rt.name,
                count(*) FILTER (WHERE event = 'CREATED') AS reservations,
                count(*) FILTER (WHERE event = 'CREATED' AND status = 'PENDING_PAYMENT') AS pending,
                count(*) FILTER (WHERE event = 'CREATED' AND status = 'CONFIRMED') AS confirmed,
                count(*) FILTER (WHERE event = 'CREATED' AND status = 'COMPLETED') AS completed,
                count(*) FILTER (WHERE event = 'CREATED' AND status = 'CANCELLED') AS cancelled,
                count(*) FILTER (WHERE event = 'CREATED' AND status = 'EXPIRED') AS expired,
                count(*) FILTER (WHERE event = 'CANCELLED') AS cancellations,
                count(*) FILTER (WHERE event = 'SETTLED') AS settlements,
                coalesce(sum(amount), 0) AS volume,
                coalesce(sum(amount) FILTER (WHERE status IN ('CONFIRMED', 'COMPLETED')), 0) AS confirmed_value,
                coalesce(sum(membership), 0) AS membership,
                coalesce(sum(voucher), 0) AS voucher
            FROM events e JOIN room_types rt ON rt.id = e.room_type_id
            GROUP BY GROUPING SETS ((), (e.day), (e.room_type_id, rt.name))
            ORDER BY e.day NULLS LAST, rt.name NULLS LAST, e.room_type_id
            """;

    // Estimate based on the current active inventory and recorded, effective RoomBlock windows.
    private static final String INVENTORY = """
            WITH nights AS (
                SELECT d::date AS day,
                    (d::date + time '12:00') AT TIME ZONE :zone AS arrival,
                    (d::date + 1 + time '10:00') AT TIME ZONE :zone AS departure
                FROM generate_series(CAST(:from AS date)::timestamp, CAST(:to AS date)::timestamp,
                    interval '1 day') AS d
            ), capacity AS (
                SELECT n.day, r.id
                FROM nights n CROSS JOIN physical_rooms r JOIN room_types rt ON rt.id = r.room_type_id
                WHERE r.is_active AND rt.is_active AND r.created_at <= n.arrival
                    AND (CAST(:roomTypeId AS bigint) IS NULL OR rt.id = :roomTypeId)
                    AND NOT EXISTS (
                        SELECT 1 FROM room_blocks rb WHERE rb.physical_room_id = r.id
                            AND greatest(rb.start_at, rb.created_at) < n.departure
                            AND least(rb.end_at, coalesce(rb.released_at, rb.end_at)) > n.arrival
                            AND greatest(rb.start_at, rb.created_at)
                                < least(rb.end_at, coalesce(rb.released_at, rb.end_at))
                    )
            ), reservations AS (
                SELECT b.physical_room_id, (b.start_at AT TIME ZONE :zone)::date AS arrival,
                    (b.end_at AT TIME ZONE :zone)::date AS departure
                FROM bookings b
                WHERE b.status IN ('CONFIRMED', 'COMPLETED')
                    AND b.start_at < :end AND b.end_at > :begin
                    AND (CAST(:roomTypeId AS bigint) IS NULL OR b.room_type_id = :roomTypeId)
                    AND EXISTS (SELECT 1 FROM payment_attempts p WHERE p.booking_id = b.id AND p.status = 'SUCCEEDED')
            )
            SELECT count(*) AS available,
                count(*) FILTER (WHERE EXISTS (
                    SELECT 1 FROM reservations b WHERE b.physical_room_id = c.id
                        AND c.day >= b.arrival AND c.day < b.departure
                )) AS booked
            FROM capacity c
            """;

    private final NamedParameterJdbcTemplate jdbc;

    RevenueAnalyticsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Aggregate(
            LocalDate day, Long typeId, String typeName, long reservations, long pending, long confirmed, long completed,
            long cancelled, long expired, long cancellations, long settlements, BigDecimal volume,
            BigDecimal confirmedValue, BigDecimal membership, BigDecimal voucher
    ) {
        static Aggregate read(ResultSet rs) throws SQLException {
            return new Aggregate(rs.getObject("day", LocalDate.class), rs.getObject("room_type_id", Long.class),
                    rs.getString("name"), rs.getLong("reservations"), rs.getLong("pending"), rs.getLong("confirmed"),
                    rs.getLong("completed"), rs.getLong("cancelled"), rs.getLong("expired"), rs.getLong("cancellations"),
                    rs.getLong("settlements"), rs.getBigDecimal("volume"), rs.getBigDecimal("confirmed_value"),
                    rs.getBigDecimal("membership"), rs.getBigDecimal("voucher"));
        }
    }

    record Capacity(long booked, long available) {}
    record TierCount(String tier, long count) {}
    record DemoVolume(long settlements, BigDecimal amount) {}

    List<Aggregate> aggregates(SqlParameterSource filters) {
        return jdbc.query(AGGREGATES, filters, (rs, n) -> Aggregate.read(rs));
    }

    Capacity inventory(SqlParameterSource filters) {
        return jdbc.queryForObject(INVENTORY, filters, (rs, n) -> new Capacity(rs.getLong("booked"), rs.getLong("available")));
    }

    List<TierCount> membership(SqlParameterSource filters) {
        return jdbc.query("""
                SELECT cp.tier_code, count(DISTINCT b.customer_id) AS customers
                FROM bookings b JOIN customer_profiles cp ON cp.user_id = b.customer_id
                WHERE
                """ + CREATED + " GROUP BY cp.tier_code", filters,
                (rs, n) -> new TierCount(rs.getString("tier_code"), rs.getLong("customers")));
    }

    List<RevenueAnalytics.RoomType> roomTypes() {
        return jdbc.query("SELECT id, name FROM room_types ORDER BY name, id",
                (rs, n) -> new RevenueAnalytics.RoomType(rs.getLong("id"), rs.getString("name"), 0, BigDecimal.ZERO));
    }

    DemoVolume demoVolume(SqlParameterSource filters) {
        return jdbc.queryForObject("""
                SELECT count(*) AS settlements, coalesce(sum(p.requested_amount), 0) AS volume
                FROM payment_attempts p JOIN bookings b ON b.id = p.booking_id
                WHERE p.provider = 'DEMO' AND p.status = 'SUCCEEDED'
                    AND p.succeeded_at >= :begin AND p.succeeded_at < :end
                    AND (CAST(:roomTypeId AS bigint) IS NULL OR b.room_type_id = :roomTypeId)
                """, filters, (rs, n) -> new DemoVolume(rs.getLong("settlements"), rs.getBigDecimal("volume")));
    }
}
