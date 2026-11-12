package com.hotelpms.report;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReportCsvService {
    private final JdbcTemplate jdbc;
    private final ReportService reports;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ReportFilters.ZONE);
    public ReportCsvService(JdbcTemplate jdbc, ReportService reports) { this.jdbc = jdbc; this.reports = reports; }
    static String cell(String input) {
        String value = input == null ? "" : input;
        String leading = value.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    static void row(StringBuilder csv, String... values) {
        csv.append(Arrays.stream(values).map(ReportCsvService::cell).collect(Collectors.joining(","))).append("\r\n");
    }
    private static String time(Instant value) { return value == null ? "" : TIME.format(value); }
    public byte[] bookings(String from, String to) {
        var p = ReportFilters.period(from, to);
        StringBuilder csv = new StringBuilder("\uFEFF");
        row(csv, "BOOKINGS", "Từ " + from, "Đến " + to + " (không gồm ngày đến)", "Asia/Ho_Chi_Minh");
        row(csv, "Mã", "Trạng thái", "Loại phòng", "Số phòng", "Nhận", "Trả", "Số khách", "Số tiền", "SĐT đã che");
        jdbc.query("""
                SELECT b.*, t.name AS type_name, r.room_number, c.phone_number FROM bookings b
                JOIN room_types t ON t.id=b.room_type_id JOIN physical_rooms r ON r.id=b.physical_room_id
                JOIN customer_profiles c ON c.user_id=b.customer_id
                WHERE b.start_at < ? AND b.end_at > ? ORDER BY b.start_at,b.id
                """, rs -> { row(csv, rs.getString("booking_code"), rs.getString("status"), rs.getString("type_name"),
                    rs.getString("room_number"), time(rs.getTimestamp("start_at").toInstant()), time(rs.getTimestamp("end_at").toInstant()),
                    rs.getString("guest_count"), rs.getBigDecimal("payable_amount").toPlainString(),
                    DailyBookingService.mask(rs.getString("phone_number"), null).phoneMasked()); }, ReportService.ts(p.end()), ReportService.ts(p.start()));
        csv.append("\r\n"); row(csv, "ROOM_BLOCKS", "Gồm cả bản ghi đã mở lại");
        row(csv, "ID", "Số phòng", "Từ", "Đến", "Lý do", "Người chặn", "Lúc chặn", "Người mở", "Lúc mở", "Trạng thái");
        for (var block : reports.history(from, to, null)) row(csv, Long.toString(block.id()), block.roomNumber(), time(block.startAt()), time(block.endAt()),
                block.reason(), block.createdByName(), time(block.createdAt()), block.releasedByName(), time(block.releasedAt()), block.active() ? "Đang hiệu lực" : "Đã mở");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
}
