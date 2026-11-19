package com.hotelpms.payment.repository;

import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.payment.dto.CustomerTransaction;
import com.hotelpms.payment.dto.CustomerTransaction.Detail;
import com.hotelpms.payment.dto.CustomerTransaction.Method;
import com.hotelpms.payment.dto.CustomerTransaction.Sort;
import com.hotelpms.payment.dto.CustomerTransaction.Status;
import com.hotelpms.payment.entity.PaymentProvider;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerTransactionRepository {
    // Both lists and details use the same status projection. It reads only persisted server data.
    public static final String SETTLED_PAYMENT = """
            p.status = 'SUCCEEDED' AND p.succeeded_at IS NOT NULL
                AND (p.provider <> 'VNPAY' OR (c.outcome = 'SUCCEEDED' AND c.received_at IS NOT NULL
                  AND c.response_code = '00' AND c.transaction_status = '00'))
            """;
    public static final String TRANSACTION_STATUS = """
            CASE
              WHEN c.outcome = 'REVIEW_REQUIRED' THEN 'REVIEW_REQUIRED'
              WHEN %s THEN 'SUCCEEDED'
              WHEN c.outcome = 'FAILED' AND c.response_code = '24' THEN 'CANCELLED'
              WHEN c.outcome = 'FAILED' AND c.response_code = '11' THEN 'EXPIRED'
              WHEN p.status = 'FAILED' THEN 'FAILED'
              WHEN b.status = 'CANCELLED' THEN 'CANCELLED'
              WHEN b.status = 'EXPIRED' OR COALESCE(c.expires_at, b.hold_expires_at) <= :now THEN 'EXPIRED'
              WHEN p.provider = 'VNPAY' THEN 'PROCESSING'
              ELSE 'PENDING'
            END
            """.formatted(SETTLED_PAYMENT);

    private static final String SOURCE = """
            FROM bookings b
            JOIN payment_attempts p ON p.booking_id = b.id
            LEFT JOIN vnpay_checkouts c ON c.payment_attempt_id = p.id
            """;
    private static final String SELECT = """
            SELECT p.id, b.booking_code, b.room_type_id, t.name AS room_type_name, cover.id AS cover_id,
                   c.reference, p.provider, NULLIF(c.provider_transaction_id, '0') AS gateway_transaction_id,
                   p.requested_amount, p.created_at, p.succeeded_at, c.received_at, b.status AS booking_status,
                   b.base_amount, b.discount_amount, b.payable_amount AS current_booking_payable,
            """ + TRANSACTION_STATUS + " AS transaction_status " + SOURCE + """
            JOIN room_types t ON t.id = b.room_type_id
            LEFT JOIN room_type_images cover ON cover.room_type_id = t.id AND cover.is_cover AND t.is_active
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerTransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Filter(Status status, PaymentProvider provider, Instant from, Instant until, String search, Sort sort) {}

    public CustomerTransaction.Page findPage(long customerId, Filter filter, int page, int size, Instant now) {
        var params = owner(customerId, now);
        var where = new StringBuilder(" WHERE b.customer_id = :customer");
        if (filter.status() != null) {
            where.append(" AND (").append(TRANSACTION_STATUS).append(") = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.provider() != null) {
            where.append(" AND p.provider = :provider");
            params.addValue("provider", filter.provider().name());
        }
        if (filter.from() != null) {
            where.append(" AND p.created_at >= :from");
            params.addValue("from", Timestamp.from(filter.from()));
        }
        if (filter.until() != null) {
            where.append(" AND p.created_at < :until");
            params.addValue("until", Timestamp.from(filter.until()));
        }
        if (!filter.search().isEmpty()) {
            where.append("""
                     AND (strpos(lower(b.booking_code), :search) > 0
                       OR strpos(lower(c.reference), :search) > 0
                       OR strpos(NULLIF(c.provider_transaction_id, '0'), :search) > 0
                       OR ('#' || p.id::text) = :search OR p.id::text = :search)
                    """);
            params.addValue("search", filter.search());
        }
        long total = jdbc.queryForObject("SELECT count(*) " + SOURCE + where, params, Long.class);
        long offset = (long) page * size;
        List<CustomerTransaction> items = List.of();
        if (offset < total) {
            String order = filter.sort() == Sort.OLDEST ? "p.created_at ASC, p.id ASC" : "p.created_at DESC, p.id DESC";
            params.addValue("size", size).addValue("offset", offset);
            items = jdbc.query(SELECT + where + " ORDER BY " + order + " LIMIT :size OFFSET :offset",
                    params, (rs, row) -> summary(rs));
        }
        return new CustomerTransaction.Page(items, page, size, total, Math.ceilDiv(total, size));
    }

    public Optional<Detail> findOwned(long customerId, long id, Instant now) {
        var params = owner(customerId, now).addValue("id", id);
        return jdbc.query(SELECT + " WHERE b.customer_id = :customer AND p.id = :id", params, (rs, row) -> {
            var summary = summary(rs);
            return new Detail(summary, rs.getString("gateway_transaction_id"), rs.getBigDecimal("base_amount"),
                    rs.getBigDecimal("discount_amount"), voucherDiscount(rs), summary.amount(),
                    instant(rs, "received_at"), summary.status() == Status.SUCCEEDED ? instant(rs, "succeeded_at") : null);
        }).stream().findFirst();
    }

    private static MapSqlParameterSource owner(long customerId, Instant now) {
        return new MapSqlParameterSource("customer", customerId).addValue("now", Timestamp.from(now));
    }

    private static CustomerTransaction summary(ResultSet rs) throws SQLException {
        var provider = PaymentProvider.valueOf(rs.getString("provider"));
        var status = Status.valueOf(rs.getString("transaction_status"));
        var method = switch (provider) {
            case VNPAY -> Method.VNPAY;
            case DEMO -> Method.DEMO;
            case ZERO_PAYABLE -> voucherDiscount(rs).signum() > 0 ? Method.VOUCHER : Method.ZERO_PAYABLE;
        };
        var amount = rs.getBigDecimal("requested_amount");
        var coverId = rs.getObject("cover_id", Long.class);
        String coverUrl = coverId == null ? null : "/api/v1/rooms/" + rs.getLong("room_type_id") + "/images/" + coverId;
        long id = rs.getLong("id");
        String reference = rs.getString("reference");
        return new CustomerTransaction(id, rs.getString("booking_code"), rs.getString("room_type_name"),
                coverUrl, reference == null ? "#" + id : reference, provider, method, amount, "VND",
                instant(rs, "created_at"), status, BookingStatus.valueOf(rs.getString("booking_status")),
                status == Status.SUCCEEDED && amount.compareTo(rs.getBigDecimal("current_booking_payable")) == 0,
                status == Status.SUCCEEDED ? instant(rs, "succeeded_at") : null);
    }

    private static BigDecimal voucherDiscount(ResultSet rs) throws SQLException {
        // Booking base and membership discount are fixed. The attempt retains its payable even
        // when the voucher is later removed or replaced before a new attempt.
        return rs.getBigDecimal("base_amount").subtract(rs.getBigDecimal("discount_amount"))
                .subtract(rs.getBigDecimal("requested_amount"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
