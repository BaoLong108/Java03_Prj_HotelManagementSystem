package com.hotelpms.report;

import com.hotelpms.shared.api.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.http.HttpStatus;

final class ReportFilters {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Set<String> STATUSES = Set.of("PENDING_PAYMENT", "CONFIRMED", "COMPLETED", "CANCELLED", "EXPIRED");
    private ReportFilters() { }
    record Period(LocalDate from, LocalDate to) {
        java.time.Instant start() { return from.atStartOfDay(ZONE).toInstant(); }
        java.time.Instant end() { return to.atStartOfDay(ZONE).toInstant(); }
    }
    static Period period(String from, String to) {
        var start = date(from); var end = date(to);
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end);
        if (days < 1 || days > 92) throw invalid();
        return new Period(start, end);
    }
    static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "Bộ lọc ngày, phòng hoặc trạng thái không hợp lệ");
    }
    static LocalDate date(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1900 || date.getYear() > 9998) throw invalid();
            return date;
        } catch (java.time.DateTimeException e) { throw invalid(); }
    }
    static Long id(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            if (!value.matches("[0-9]+")) throw invalid();
            long id = Long.parseLong(value);
            if (id < 1) throw invalid();
            return id;
        } catch (NumberFormatException e) { throw invalid(); }
    }
    static String status(String value) {
        if (value == null || value.isEmpty()) return null;
        if (!STATUSES.contains(value)) throw invalid();
        return value;
    }
}
