package com.hotelpms.support;

import static com.hotelpms.support.SupportTypes.*;

import com.hotelpms.shared.api.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class SupportChat {
    record Changed(long customer, Long staff, boolean inbox) {}
    private record Thread(long id, long customer, Long staff, Status status, long customerRead, long staffRead,
                          String name, Instant updated) {}
    private static final String THREAD = """
        SELECT c.*, u.full_name FROM support_conversations c JOIN app_users u ON u.id=c.customer_id
        """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SupportSockets sockets;
    private final ApplicationEventPublisher events;
    private final SupportRateLimit limits;

    SupportChat(JdbcTemplate jdbc, ObjectMapper json, SupportSockets sockets, ApplicationEventPublisher events, SupportRateLimit limits) {
        this.jdbc = jdbc;
        this.json = json;
        this.sockets = sockets;
        this.events = events;
        this.limits = limits;
    }

    List<Conversation> list(Actor actor, boolean staff, long before) {
        requireRole(actor, staff);
        String access = staff ? "(c.status='WAITING_STAFF' OR c.assigned_staff_id=?)" : "c.customer_id=?";
        return jdbc.query(THREAD + " WHERE " + access + " AND c.id<? ORDER BY c.id DESC LIMIT 30",
                SupportChat::thread, actor.id(), before).stream().map(row -> view(row, actor, staff)).toList();
    }

    Conversation get(long id, Actor actor, boolean staff) {
        return view(authorized(id, actor, staff, false), actor, staff);
    }

    @Transactional
    public Conversation open(Actor actor) {
        requireRole(actor, false);
        jdbc.update("""
            INSERT INTO support_conversations(customer_id) VALUES (?)
            ON CONFLICT (customer_id) WHERE status<>'CLOSED' DO NOTHING
            """, actor.id());
        return list(actor, false, Long.MAX_VALUE).stream().filter(item -> item.status() != Status.CLOSED)
                .findFirst().orElseThrow(() -> conflict("Hãy thử mở lại cuộc trò chuyện."));
    }

    @Transactional
    public Conversation handoff(long id, Actor actor) {
        Thread row = authorized(id, actor, false, true);
        if (row.status() == Status.CLOSED) throw conflict("Cuộc trò chuyện đã kết thúc.");
        if (row.status() == Status.AI_ACTIVE) {
            jdbc.update("UPDATE support_conversations SET status='WAITING_STAFF',updated_at=now() WHERE id=?", id);
            change(row, true);
        }
        return get(id, actor, false);
    }

    @Transactional
    public Conversation claim(long id, Actor actor) {
        requireRole(actor, true);
        int count = jdbc.update("""
            UPDATE support_conversations SET assigned_staff_id=?,status='STAFF_ACTIVE',accepted_at=now(),updated_at=now()
            WHERE id=? AND status='WAITING_STAFF' AND customer_id<>?
            """, actor.id(), id, actor.id());
        if (count == 0) throw conflict("Cuộc trò chuyện đã có người nhận hoặc không còn trong hàng chờ.");
        Thread row = authorized(id, actor, true, false);
        change(row, true);
        return view(row, actor, true);
    }

    @Transactional
    public Conversation close(long id, Actor actor) {
        Thread row = authorized(id, actor, true, true);
        if (!assigned(row, actor)) throw missing();
        if (row.status() == Status.STAFF_ACTIVE) {
            jdbc.update("UPDATE support_conversations SET status='CLOSED',closed_at=now(),updated_at=now() WHERE id=?", id);
            change(row, true);
        }
        return get(id, actor, true);
    }

    List<Message> messages(long id, Actor actor, boolean staff, Long before, Long after) {
        Thread row = authorized(id, actor, staff, false);
        if (staff && !assigned(row, actor)) return List.of();
        String direction = after != null ? " AND id>? ORDER BY id LIMIT 40" : " AND id<? ORDER BY id DESC LIMIT 40";
        var page = jdbc.query("SELECT * FROM support_messages WHERE conversation_id=?" + direction,
                (rs, index) -> message(rs, staff), id, after != null ? after : before != null ? before : Long.MAX_VALUE);
        return after != null ? page : page.reversed();
    }

    @Transactional
    public void read(long id, Actor actor, boolean staff, long through) {
        Thread row = authorized(id, actor, staff, true);
        if (staff && !assigned(row, actor)) return;
        if (through <= (staff ? row.staffRead() : row.customerRead())) return;
        Long last = jdbc.queryForObject("SELECT max(id) FROM support_messages WHERE conversation_id=? AND id<=?", Long.class, id, through);
        if (last == null || last <= (staff ? row.staffRead() : row.customerRead())) return;
        jdbc.update(staff ? "UPDATE support_conversations SET staff_read_id=? WHERE id=?"
                : "UPDATE support_conversations SET customer_read_id=? WHERE id=?", last, id);
        change(row, row.status() == Status.WAITING_STAFF);
    }

    @Transactional
    public Message send(long id, Actor actor, boolean staff, Send request, boolean concierge) {
        Thread row = authorized(id, actor, staff, true);
        if (staff && !assigned(row, actor)) throw missing();
        Sender sender = staff ? Sender.STAFF : Sender.CUSTOMER;
        var existing = jdbc.query("""
            SELECT * FROM support_messages WHERE conversation_id=? AND sender=? AND sender_id=? AND client_id=?
            """, (rs, index) -> message(rs, staff), id, sender.name(), actor.id(), request.clientId()).stream().findFirst();
        if (existing.isPresent()) {
            if (!existing.get().content().equals(request.content().strip())) throw conflict("Mã tin nhắn đã được sử dụng.");
            return existing.get();
        }
        boolean modeAllowsSend = concierge ? !staff && row.status() == Status.AI_ACTIVE
                : row.status() == Status.STAFF_ACTIVE || (!staff && row.status() == Status.WAITING_STAFF);
        if (!modeAllowsSend) throw conflict("Chế độ hỗ trợ đã thay đổi. Hãy tải lại cuộc trò chuyện.");
        limits.allow("message:" + actor.id(), 30);
        var saved = jdbc.queryForObject("""
            INSERT INTO support_messages(conversation_id,sender,sender_id,client_id,content)
            VALUES (?,?,?,?,?) RETURNING *
            """, (rs, index) -> message(rs, staff), id, sender.name(), actor.id(), request.clientId(), request.content().strip());
        touch(row);
        return saved;
    }

    @Transactional
    public boolean finishAi(long id, Actor actor, long replyTo, String content, JsonNode details) {
        Thread row = authorized(id, actor, false, true);
        if (row.status() != Status.AI_ACTIVE) return false;
        jdbc.update("""
            INSERT INTO support_messages(conversation_id,sender,content,details,reply_to)
            VALUES (?,'AI',?,?::jsonb,?) ON CONFLICT (reply_to) DO NOTHING
            """, id, content, json.writeValueAsString(details), replyTo);
        touch(row);
        return true;
    }

    Message aiReply(long id, long replyTo, Actor actor) {
        authorized(id, actor, false, false);
        return jdbc.query("SELECT * FROM support_messages WHERE conversation_id=? AND reply_to=?",
                (rs, index) -> message(rs, false), id, replyTo).stream().findFirst().orElse(null);
    }

    private void touch(Thread row) {
        jdbc.update("UPDATE support_conversations SET updated_at=now() WHERE id=?", row.id());
        change(row, row.status() == Status.WAITING_STAFF);
    }

    private void change(Thread row, boolean inbox) {
        events.publishEvent(new Changed(row.customer(), row.staff(), inbox));
    }

    private Thread authorized(long id, Actor actor, boolean staff, boolean lock) {
        requireRole(actor, staff);
        String access = staff ? "(c.status='WAITING_STAFF' OR c.assigned_staff_id=?)" : "c.customer_id=?";
        return jdbc.query(THREAD + " WHERE c.id=? AND " + access + (lock ? " FOR UPDATE OF c" : ""),
                SupportChat::thread, id, actor.id()).stream().findFirst().orElseThrow(SupportChat::missing);
    }

    private Conversation view(Thread row, Actor actor, boolean staff) {
        long through = staff ? row.staffRead() : row.customerRead();
        String sender = staff ? "CUSTOMER" : "STAFF";
        Long unread = jdbc.queryForObject("""
            SELECT count(*) FROM support_messages WHERE conversation_id=? AND id>? AND sender=?
            """, Long.class, row.id(), through, sender);
        var preview = jdbc.queryForList("""
            SELECT left(content,120) FROM support_messages
            WHERE conversation_id=? AND (NOT ? OR sender='CUSTOMER') ORDER BY id DESC LIMIT 1
            """, String.class, row.id(), staff && row.status() == Status.WAITING_STAFF);
        return new Conversation(row.id(), row.status(), staff ? row.name() : null, assigned(row, actor),
                row.staff() != null && sockets.staffOnline(row.staff()), unread,
                preview.isEmpty() ? "" : preview.getFirst(), row.updated());
    }

    private Message message(ResultSet rs, boolean staff) throws SQLException {
        Sender sender = Sender.valueOf(rs.getString("sender"));
        String details = rs.getString("details");
        return new Message(rs.getLong("id"), sender, rs.getString("content"), rs.getTimestamp("created_at").toInstant(),
                details == null || staff ? null : json.readTree(details));
    }

    private static Thread thread(ResultSet rs, int index) throws SQLException {
        return new Thread(rs.getLong("id"), rs.getLong("customer_id"), rs.getObject("assigned_staff_id", Long.class),
                Status.valueOf(rs.getString("status")), rs.getLong("customer_read_id"), rs.getLong("staff_read_id"),
                rs.getString("full_name"), rs.getTimestamp("updated_at").toInstant());
    }

    private static boolean assigned(Thread row, Actor actor) { return row.staff() != null && row.staff() == actor.id(); }

    private static void requireRole(Actor actor, boolean staff) {
        if (actor == null || !(staff ? actor.staff() : actor.customer())) throw new ApiException(
                HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền truy cập.");
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_NOT_FOUND", "Không tìm thấy cuộc trò chuyện.");
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "SUPPORT_CONFLICT", message);
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "Asia/Ho_Chi_Minh")
    public void purgeOldHistory() {
        jdbc.update("DELETE FROM support_conversations WHERE updated_at < now()-interval '90 days'");
    }
}
