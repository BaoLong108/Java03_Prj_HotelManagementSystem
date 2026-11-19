package com.hotelpms.crm;

import static com.hotelpms.payment.repository.CustomerTransactionRepository.SETTLED_PAYMENT;
import static com.hotelpms.payment.repository.CustomerTransactionRepository.TRANSACTION_STATUS;

import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.crm.CustomerCrm.*;
import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.payment.dto.CustomerTransaction.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CustomerCrmRepository {
    private static final String CUSTOMERS = """
            FROM app_users u JOIN customer_profiles cp ON cp.user_id = u.id
            WHERE EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
              WHERE ur.user_id = u.id AND r.code = 'CUSTOMER')
            """;
    private static final String CUSTOMER_FIELDS = """
            u.id, u.full_name, u.email, cp.phone_number, u.is_active, u.created_at,
            cp.tier_code, cp.points_balance, cp.eligible_spend_total
            """;
    private static final String BOOKING_COUNTS = """
            , (SELECT count(*) FROM bookings b WHERE b.customer_id = u.id) AS booking_count
            """;
    private static final String MASKED_CODE = "('••••' || right(v.code, 2))";
    private static final String BOOKING_SOURCE = """
            FROM bookings b JOIN room_types t ON t.id = b.room_type_id
            WHERE b.customer_id = :id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    CustomerCrmRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Statistics statistics() {
        return jdbc.getJdbcTemplate().queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE cp.tier_code = 'STANDARD'),
                       count(*) FILTER (WHERE cp.tier_code = 'VIP'),
                       count(*) FILTER (WHERE cp.tier_code = 'DIAMOND')
                """ + CUSTOMERS, (rs, row) -> new Statistics(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
    }

    Page<Customer> customers(String term, LoyaltyTier tier, Boolean active, Sort sort, int page, int size) {
        var params = new MapSqlParameterSource();
        var source = new StringBuilder(CUSTOMERS);
        if (!term.isEmpty()) {
            source.append("""
                     AND (strpos(lower(u.full_name), :term) > 0 OR strpos(lower(u.email), :term) > 0
                       OR strpos(cp.phone_number, :term) > 0 OR u.id::text = :term)
                    """);
            params.addValue("term", term);
        }
        if (tier != null) {
            source.append(" AND cp.tier_code = :tier");
            params.addValue("tier", tier.name());
        }
        if (active != null) {
            source.append(" AND u.is_active = :active");
            params.addValue("active", active);
        }
        String order = switch (sort) {
            case SPENDING -> "cp.eligible_spend_total DESC, u.id DESC";
            case POINTS -> "cp.points_balance DESC, u.id DESC";
            case REGISTERED -> "u.created_at DESC, u.id DESC";
        };
        // Compute booking counts for the current page only.
        String pagedCustomers = "(SELECT " + CUSTOMER_FIELDS + ", row_number() OVER (ORDER BY " + order + ") AS position "
                + source + " ORDER BY " + order + " LIMIT :size OFFSET :offset) u";
        String query = "SELECT u.*" + BOOKING_COUNTS + " FROM " + pagedCustomers + " ORDER BY u.position";
        return readPage("SELECT count(*) " + source, query, params, page, size, (rs, row) -> customer(rs));
    }

    Optional<Customer> customer(long id) {
        return jdbc.query("SELECT " + CUSTOMER_FIELDS + BOOKING_COUNTS + CUSTOMERS + " AND u.id = :id",
                owner(id), (rs, row) -> customer(rs)).stream().findFirst();
    }

    record Totals(long completed, long verified, long earned, long redeemed) {}

    Totals totals(long id) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM bookings WHERE customer_id = :id AND status = 'COMPLETED'),
                       (SELECT count(*) FROM bookings b JOIN payment_attempts p ON p.booking_id = b.id
                         LEFT JOIN vnpay_checkouts c ON c.payment_attempt_id = p.id
                         WHERE b.customer_id = :id AND (
                """ + SETTLED_PAYMENT + """
                       )),
                       (SELECT COALESCE(sum(points_awarded), 0) FROM loyalty_awards WHERE customer_id = :id),
                       (SELECT COALESCE(sum(points_cost), 0) FROM loyalty_vouchers WHERE customer_id = :id)
                """, owner(id), (rs, row) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
    }

    Page<Booking> bookings(long id, BookingStatus status, Instant from, Instant until, int page, int size) {
        var params = owner(id).addValue("now", Timestamp.from(Instant.now()));
        var source = new StringBuilder(BOOKING_SOURCE);
        if (status != null) {
            source.append(" AND b.status = :status");
            params.addValue("status", status.name());
        }
        if (from != null) {
            source.append(" AND b.start_at >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        if (until != null) {
            source.append(" AND b.start_at < :until");
            params.addValue("until", Timestamp.from(until));
        }
        String sql = """
                SELECT booking_code, name, start_at, end_at, guest_count, created_at, status,
                       base_amount, discount_amount, voucher_discount_amount, payable_amount,
                       (SELECT %s FROM payment_attempts p
                         LEFT JOIN vnpay_checkouts c ON c.payment_attempt_id = p.id
                         WHERE p.booking_id = b.id
                         ORDER BY (%s) DESC NULLS LAST,
                           (c.outcome = 'REVIEW_REQUIRED') DESC NULLS LAST, p.created_at DESC, p.id DESC
                         LIMIT 1) AS payment_status
                FROM (SELECT b.*, t.name %s ORDER BY b.created_at DESC, b.id DESC LIMIT :size OFFSET :offset) b
                ORDER BY b.created_at DESC, b.id DESC
                """.formatted(TRANSACTION_STATUS, SETTLED_PAYMENT, source);
        return readPage("SELECT count(*) " + source, sql, params, page, size, (rs, row) -> {
            String payment = rs.getString("payment_status");
            return new Booking(rs.getString("booking_code"), rs.getString("name"), instant(rs, "start_at"),
                    instant(rs, "end_at"), rs.getInt("guest_count"), instant(rs, "created_at"),
                    BookingStatus.valueOf(rs.getString("status")), payment == null ? null : Status.valueOf(payment),
                    rs.getBigDecimal("base_amount"), rs.getBigDecimal("discount_amount"),
                    rs.getBigDecimal("voucher_discount_amount"), rs.getBigDecimal("payable_amount"));
        });
    }

    Page<Point> points(long id, int page, int size) {
        String sources = """
                SELECT a.id, 'EARNED' AS type, a.points_awarded AS points, a.awarded_at AS at,
                       b.booking_code, NULL AS voucher_code
                FROM loyalty_awards a JOIN bookings b ON b.id = a.booking_id WHERE a.customer_id = :id
                UNION ALL
                SELECT v.id, 'REDEEMED', v.points_cost, v.created_at, b.booking_code, %s
                FROM loyalty_vouchers v LEFT JOIN bookings b ON b.id = v.booking_id WHERE v.customer_id = :id
                """.formatted(MASKED_CODE);
        return readPage("""
                SELECT (SELECT count(*) FROM loyalty_awards WHERE customer_id = :id)
                     + (SELECT count(*) FROM loyalty_vouchers WHERE customer_id = :id)
                """, sources + " ORDER BY at DESC, type, id DESC LIMIT :size OFFSET :offset",
                owner(id), page, size, (rs, row) -> new Point(rs.getLong("id"), rs.getString("type"), rs.getLong("points"),
                        instant(rs, "at"), rs.getString("booking_code"), rs.getString("voucher_code")));
    }

    Page<Voucher> vouchers(long id, int page, int size) {
        return readPage("SELECT count(*) FROM loyalty_vouchers WHERE customer_id = :id", """
                SELECT v.id, %s AS code, v.points_cost, v.discount_amount, v.created_at,
                       CASE WHEN v.used_at IS NOT NULL THEN 'USED' WHEN v.booking_id IS NOT NULL THEN 'RESERVED'
                            ELSE 'AVAILABLE' END AS status, b.booking_code, v.used_at
                FROM loyalty_vouchers v LEFT JOIN bookings b ON b.id = v.booking_id
                WHERE v.customer_id = :id ORDER BY v.created_at DESC, v.id DESC LIMIT :size OFFSET :offset
                """.formatted(MASKED_CODE), owner(id), page, size, (rs, row) -> new Voucher(
                rs.getLong("id"), rs.getString("code"), rs.getInt("points_cost"), rs.getBigDecimal("discount_amount"),
                instant(rs, "created_at"), rs.getString("status"), rs.getString("booking_code"), instant(rs, "used_at")));
    }

    Page<CheckIn> checkIns(long id, int page, int size) {
        String source = " FROM check_in_requests q JOIN bookings b ON b.id = q.booking_id ";
        String owner = " WHERE b.customer_id = :id ";
        return readPage("SELECT count(*)" + source + owner, """
                SELECT q.id, b.booking_code, q.created_at, q.estimated_arrival_at, q.early_check_in_requested,
                       q.status, u.full_name AS reviewer, q.reviewed_at, q.rejection_reason
                """ + source + " LEFT JOIN app_users u ON u.id = q.reviewed_by " + owner
                + " ORDER BY q.created_at DESC, q.id DESC LIMIT :size OFFSET :offset", owner(id), page, size,
                (rs, row) -> new CheckIn(rs.getLong("id"), rs.getString("booking_code"), instant(rs, "created_at"),
                        instant(rs, "estimated_arrival_at"), rs.getBoolean("early_check_in_requested"), rs.getString("status"),
                        rs.getString("reviewer"), instant(rs, "reviewed_at"), rs.getString("rejection_reason")));
    }

    private <T> Page<T> readPage(String count, String query, MapSqlParameterSource params, int page, int size, RowMapper<T> mapper) {
        long total = jdbc.queryForObject(count, params, Long.class);
        long offset = (long) page * size;
        var items = offset >= total ? java.util.List.<T>of()
                : jdbc.query(query, params.addValue("size", size).addValue("offset", offset), mapper);
        return new Page<>(items, page, size, total, Math.ceilDiv(total, size));
    }

    private static MapSqlParameterSource owner(long id) {
        return new MapSqlParameterSource("id", id);
    }

    private static Customer customer(ResultSet rs) throws SQLException {
        return new Customer(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone_number"),
                rs.getBoolean("is_active"), instant(rs, "created_at"), LoyaltyTier.valueOf(rs.getString("tier_code")),
                rs.getLong("points_balance"), rs.getBigDecimal("eligible_spend_total"), rs.getLong("booking_count"));
    }

    private static Instant instant(ResultSet rs, String key) throws SQLException {
        Timestamp value = rs.getTimestamp(key);
        return value == null ? null : value.toInstant();
    }
}
