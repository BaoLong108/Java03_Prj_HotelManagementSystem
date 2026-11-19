package com.hotelpms.support;

import com.hotelpms.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class SupportRateLimit {
    private final JdbcTemplate jdbc;

    SupportRateLimit(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void allow(String scope, int limit) {
        int count = jdbc.queryForObject("""
            INSERT INTO support_rate_windows(scope,window_at,requests)
            VALUES (?,date_trunc('minute',now()),1)
            ON CONFLICT (scope,window_at) DO UPDATE SET requests=LEAST(support_rate_windows.requests+1,?)
            RETURNING requests
            """, Integer.class, scope, limit + 1);
        if (count > limit) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SUPPORT_RATE_LIMIT",
                "Bạn gửi quá nhanh. Vui lòng thử lại sau ít phút.");
    }

    @Scheduled(fixedDelay = 3_600_000)
    void purge() {
        jdbc.update("DELETE FROM support_rate_windows WHERE window_at < now()-interval '1 hour'");
    }
}
