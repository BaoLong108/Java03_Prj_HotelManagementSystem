package com.hotelpms.pricing.repository;

import com.hotelpms.pricing.model.RateRule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RateRuleRepository {
    private static final RowMapper<RateRule> RULE = (rs, row) -> new RateRule(rs.getLong("id"),
            RateRule.Kind.valueOf(rs.getString("kind")), rs.getObject("starts_on", LocalDate.class),
            rs.getObject("ends_on", LocalDate.class), rs.getBigDecimal("nightly_rate"));
    private final JdbcTemplate jdbc;

    public RateRuleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<RateRule> overlapping(long roomTypeId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT id, kind, starts_on, ends_on, nightly_rate FROM room_rate_rules
                WHERE room_type_id = ? AND is_active
                  AND daterange(starts_on, ends_on, '[]') && daterange(?::date, ?::date, '[]')
                ORDER BY starts_on, id
                """, RULE, roomTypeId, from, to);
    }

    public Optional<RateRule> find(long roomTypeId, long id) {
        return jdbc.query("""
                SELECT id, kind, starts_on, ends_on, nightly_rate FROM room_rate_rules
                WHERE room_type_id = ? AND id = ? AND is_active
                """, RULE, roomTypeId, id).stream().findFirst();
    }

    public RateRule insert(long roomTypeId, RateRule rule) {
        return jdbc.queryForObject("""
                INSERT INTO room_rate_rules (room_type_id, kind, starts_on, ends_on, nightly_rate)
                VALUES (?, ?, ?, ?, ?) RETURNING id, kind, starts_on, ends_on, nightly_rate
                """, RULE, roomTypeId, rule.kind().name(), rule.startsOn(), rule.endsOn(), rule.nightlyRate());
    }

    public RateRule update(long roomTypeId, RateRule rule) {
        return jdbc.queryForObject("""
                UPDATE room_rate_rules SET kind = ?, starts_on = ?, ends_on = ?, nightly_rate = ?
                WHERE room_type_id = ? AND id = ? AND is_active
                RETURNING id, kind, starts_on, ends_on, nightly_rate
                """, RULE, rule.kind().name(), rule.startsOn(), rule.endsOn(), rule.nightlyRate(), roomTypeId, rule.id());
    }

    public void deactivate(long roomTypeId, long id) {
        jdbc.update("UPDATE room_rate_rules SET is_active = false WHERE room_type_id = ? AND id = ?", roomTypeId, id);
    }
}
