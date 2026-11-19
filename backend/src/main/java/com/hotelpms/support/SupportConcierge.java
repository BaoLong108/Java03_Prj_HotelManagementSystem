package com.hotelpms.support;

import static com.hotelpms.support.SupportTypes.*;

import com.hotelpms.booking.entity.Booking;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.booking.service.BookingService;
import com.hotelpms.loyalty.LoyaltyService;
import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.payment.vnpay.VnpayService;
import com.hotelpms.pricing.dto.QuoteRequest;
import com.hotelpms.pricing.service.QuoteService;
import com.hotelpms.room.dto.RoomTypeResponse;
import com.hotelpms.room.service.RoomAvailabilityService;
import com.hotelpms.room.service.RoomService;
import com.hotelpms.shared.api.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class SupportConcierge {
    enum Action { CHAT, ROOMS, POLICY, BOOKINGS }
    record Request(Long conversationId, UUID clientId, @NotBlank @Size(max = 1200) String text,
                   Action action, LocalDate arrival, LocalDate departure, Integer guests) {}
    enum Source { LIVE_AI, FAQ, HOTEL_PMS, UNAVAILABLE }
    record Room(long id, String name, String description, String coverImageUrl, int capacity, List<String> amenities,
                Long available, BigDecimal payable) {}
    record BookingView(String code, String roomType, Instant startAt, Instant endAt, String status, String paymentStatus) {}
    record Answer(String text, Source source, Long faqId, List<Room> rooms, List<BookingView> bookings,
                  LocalDate arrival, LocalDate departure, Integer guests, boolean loginRequired, boolean suppressed) {}
    private static final String UNKNOWN = "Thông tin này chưa có trong chính sách công khai. Bạn có thể hỏi nhân viên để xác nhận.";
    private static final String OFFLINE = "Hiện trợ lý AI chưa sẵn sàng. Bạn vẫn có thể tìm phòng, xem chính sách đã công bố hoặc nhắn nhân viên.";
    private static final String DATES = "Bạn vui lòng cho biết ngày đến, ngày đi và số khách, hoặc dùng nút Tìm phòng trống.";
    private static final Pattern BOOKING_CODE = Pattern.compile("\\bBK\\d{6}-[A-Z0-9]{6}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)\\+?\\d(?:[ .-]*\\d){8,18}(?!\\d)");
    private static final Pattern STAY_DATE = Pattern.compile(
            "(?<![\\d/])(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}(?:/\\d{4})?)(?![\\d/])");
    private static final Pattern GUESTS = Pattern.compile("\\b(\\d{1,2})\\s*(?:nguoi|khach|guests?|people)\\b");
    private static final Pattern STAY_INTENT = Pattern.compile("\\b(?:tim phong|dat phong|phong trong|ngay den|ngay di|check.in|check.out)\\b");
    private final SupportChat chat;
    private final HotelFaqs faqs;
    private final ConciergeProvider provider;
    private final RoomAvailabilityService availability;
    private final RoomService rooms;
    private final QuoteService quotes;
    private final BookingRepository bookings;
    private final SupportRateLimit limits;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final LoyaltyService loyalty;
    private final VnpayService vnpay;
    private final Semaphore globalProviders = new Semaphore(10);
    private final java.util.Set<String> inflight = ConcurrentHashMap.newKeySet();

    SupportConcierge(SupportChat chat, HotelFaqs faqs, ConciergeProvider provider, RoomAvailabilityService availability,
            RoomService rooms, QuoteService quotes, BookingRepository bookings, SupportRateLimit limits, ObjectMapper json,
            JdbcTemplate jdbc, LoyaltyService loyalty, VnpayService vnpay) {
        this.chat = chat;
        this.faqs = faqs;
        this.provider = provider;
        this.availability = availability;
        this.rooms = rooms;
        this.quotes = quotes;
        this.bookings = bookings;
        this.limits = limits;
        this.json = json;
        this.jdbc = jdbc;
        this.loyalty = loyalty;
        this.vnpay = vnpay;
    }

    Answer respond(Actor actor, String address, Request request) {
        String key = actor == null ? "guest:" + address : "user:" + actor.id();
        limits.allow("concierge:" + key, 12);
        if (!inflight.add(key)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SUPPORT_BUSY",
                "Trợ lý đang xử lý câu hỏi trước. Vui lòng chờ.");
        try {
            var saved = saveQuestion(actor, request);
            if (saved != null) {
                if (chat.get(request.conversationId(), actor, false).status() != Status.AI_ACTIVE) return suppressed();
                var previous = chat.aiReply(request.conversationId(), saved.id(), actor);
                if (previous != null) return json.treeToValue(previous.details(), Answer.class);
            }
            Answer result;
            try { result = answer(actor, request, saved == null ? null : saved.id()); }
            catch (DataAccessException e) { result = text("Chưa thể tải dữ liệu khách sạn. Bạn có thể thử lại hoặc nhắn nhân viên.", Source.UNAVAILABLE); }
            if (saved != null && !chat.finishAi(request.conversationId(), actor, saved.id(), result.text(), json.valueToTree(result))) {
                return suppressed();
            }
            return result;
        } finally { inflight.remove(key); }
    }

    private Message saveQuestion(Actor actor, Request request) {
        if (request.conversationId() == null) return null;
        if (actor == null || !actor.customer()) throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "Vui lòng đăng nhập.");
        if (request.clientId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Thiếu mã tin nhắn.");
        return chat.send(request.conversationId(), actor, false, new Send(request.clientId(), request.text()), true);
    }

    private Answer answer(Actor actor, Request request, Long questionId) {
        String input = request.text().strip();
        String normalized = HotelFaqs.normalize(input);
        String code = BOOKING_CODE.matcher(input).results().map(match -> match.group().toUpperCase(Locale.ROOT)).findFirst().orElse(null);
        Action action = request.action() == null ? Action.CHAT : request.action();
        if (action == Action.BOOKINGS || code != null || normalized.matches(".*\\b(?:don dat phong cua toi|dat phong gan nhat|don nao.*cho thanh toan|my booking[s]?)\\b.*")) {
            return lookup(actor, code, normalized.contains("cho thanh toan"), Source.HOTEL_PMS);
        }
        if (action == Action.ROOMS) return search(actor, request.arrival(), request.departure(), request.guests(), "", false, Source.HOTEL_PMS);
        if (normalized.contains("vnpay") && normalized.contains("thanh toan")) {
            return text(vnpay.available() ? "Khách sạn có tùy chọn thanh toán qua VNPay Sandbox ở bước thanh toán."
                    : "Hiện tùy chọn VNPay chưa khả dụng. Bạn có thể hỏi nhân viên hỗ trợ.", Source.HOTEL_PMS);
        }
        if (normalized.contains("giam") && normalized.matches(".*\\b(vip|diamond|standard)\\b.*")) {
            var tier = normalized.contains("diamond") ? LoyaltyTier.DIAMOND : normalized.contains("vip") ? LoyaltyTier.VIP : LoyaltyTier.STANDARD;
            return text("Hạng " + tier.name() + " được giảm " + loyalty.getDiscountRate(tier).movePointRight(2).stripTrailingZeros().toPlainString()
                    + "% tiền phòng khi báo giá.", Source.HOTEL_PMS);
        }
        List<Faq> choices = faqs.relevant(input, 14);
        Faq match = faqs.matching(input, choices);
        if (action == Action.POLICY) return match == null ? text(UNKNOWN, Source.FAQ) : faq(match, Source.FAQ);
        if (provider.available()) {
            if (!globalProviders.tryAcquire()) return text(OFFLINE, Source.UNAVAILABLE);
            try {
                var decision = provider.decide(redact(input), history(actor, request, questionId), choices,
                        LocalDate.now(BookingService.HOTEL_ZONE).toString());
                if (decision != null && decision.arguments() != null && decision.arguments().isObject()) return execute(actor, decision, choices);
                return text(UNKNOWN, Source.LIVE_AI);
            } catch (ConciergeProvider.Unavailable e) {
                return offlineAnswer(actor, input, normalized, match);
            } finally { globalProviders.release(); }
        }
        return offlineAnswer(actor, input, normalized, match);
    }

    private Answer offlineAnswer(Actor actor, String input, String normalized, Faq match) {
        var dates = STAY_DATE.matcher(input).results().map(result -> result.group()).limit(3).toList();
        var guests = GUESTS.matcher(normalized);
        if (dates.size() != 2 || !guests.find()) {
            if (match != null) return faq(match, Source.FAQ);
            return !dates.isEmpty() && STAY_INTENT.matcher(normalized).find()
                    ? text(DATES, Source.HOTEL_PMS) : text(OFFLINE, Source.UNAVAILABLE);
        }
        try {
            int hotelYear = LocalDate.now(BookingService.HOTEL_ZONE).getYear();
            return search(actor, parseStayDate(dates.getFirst(), hotelYear), parseStayDate(dates.getLast(), hotelYear),
                    Integer.parseInt(guests.group(1)), "", false, Source.HOTEL_PMS);
        } catch (DateTimeException e) { return text(DATES, Source.HOTEL_PMS); }
    }

    static LocalDate parseStayDate(String value, int hotelYear) {
        if (!value.contains("/")) return LocalDate.parse(value);
        String[] parts = value.split("/");
        int year = parts.length == 3 ? Integer.parseInt(parts[2]) : hotelYear;
        return LocalDate.of(year, Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
    }

    private Answer execute(Actor actor, ConciergeProvider.Decision decision, List<Faq> choices) {
        JsonNode args = decision.arguments();
        Source source = Source.LIVE_AI;
        return switch (decision.name()) {
            case "faq" -> {
                long id = args.path("id").asLong(0);
                Faq selected = choices.stream().anyMatch(faq -> faq.id() == id) ? faqs.active(id) : null;
                yield selected == null ? text(UNKNOWN, source) : faq(selected, source);
            }
            case "rooms" -> {
                try {
                    yield search(actor, LocalDate.parse(args.path("arrival").asString("")), LocalDate.parse(args.path("departure").asString("")),
                            args.path("guests").asInt(0), args.path("amenity").asString(""), args.path("cheapest").asBoolean(false), source);
                } catch (DateTimeException e) { yield text(DATES, source); }
            }
            case "catalog" -> catalog(args.path("guests").asInt(0), args.path("amenity").asString(""), source);
            case "bookings" -> lookup(actor, null, args.path("pending").asBoolean(false), source);
            case "clarify" -> text("STAY".equals(args.path("topic").asString("")) ? DATES : UNKNOWN, source);
            default -> text(UNKNOWN, source);
        };
    }

    private List<String> history(Actor actor, Request request, Long questionId) {
        if (questionId == null) return List.of();
        return chat.messages(request.conversationId(), actor, false, questionId, null).reversed().stream()
                .filter(message -> message.sender() == Sender.CUSTOMER).limit(provider.context())
                .map(message -> redact(message.content())).toList().reversed();
    }

    private Answer search(Actor actor, LocalDate arrival, LocalDate departure, Integer guests,
            String amenity, boolean cheapest, Source source) {
        if (arrival == null || departure == null || guests == null || guests < 1 || guests > 20
                || arrival.isBefore(LocalDate.now(BookingService.HOTEL_ZONE)) || !departure.isAfter(arrival)
                || ChronoUnit.DAYS.between(arrival, departure) > 366 || arrival.getYear() > 2100) return text(DATES, source);
        var start = arrival.atTime(12, 0).atZone(BookingService.HOTEL_ZONE).toOffsetDateTime();
        var end = departure.atTime(10, 0).atZone(BookingService.HOTEL_ZONE).toOffsetDateTime();
        var available = availability.search(start.toInstant(), end.toInstant(), guests).stream()
                .collect(Collectors.toMap(RoomAvailabilityService.AvailableRoomType::id, RoomAvailabilityService.AvailableRoomType::availableRoomCount));
        Long customer = actor != null && actor.customer() ? actor.id() : null;
        var found = rooms.listActiveRoomTypes().stream().filter(room -> available.getOrDefault(room.id(), 0L) > 0 && hasAmenity(room, amenity))
                .limit(40).map(room -> {
                    BigDecimal total = null;
                    try { total = quotes.quote(new QuoteRequest(room.id(), start, end, guests), customer).payableAmount(); }
                    catch (ApiException | DataAccessException ignored) { /* The client labels this quote unavailable. */ }
                    return option(room, available.get(room.id()), total);
                });
        if (cheapest) found = found.sorted(Comparator.comparing(Room::payable, Comparator.nullsLast(Comparator.naturalOrder())));
        var matches = found.limit(6).toList();
        return new Answer(matches.isEmpty() ? "Không tìm thấy hạng phòng phù hợp cho cả kỳ nghỉ." :
                "Đây là các hạng phòng còn trống cho kỳ nghỉ bạn chọn. Hệ thống sẽ kiểm tra lại khi bạn giữ phòng.",
                source, null, matches, List.of(), arrival, departure, guests, false, false);
    }

    private Answer catalog(int guests, String amenity, Source source) {
        if (guests < 0 || guests > 20) return text(DATES, source);
        var options = rooms.listActiveRoomTypes().stream().filter(room -> room.maxOccupancy() >= guests && hasAmenity(room, amenity))
                .limit(6).map(room -> option(room, null, null)).toList();
        return new Answer(options.isEmpty() ? "Chưa có hạng phòng phù hợp với yêu cầu." : DATES, source,
                null, options, List.of(), null, null, null, false, false);
    }

    private Answer lookup(Actor actor, String code, boolean pending, Source source) {
        if (actor == null || !actor.customer()) return new Answer("Bạn cần đăng nhập để xem đơn đặt phòng của mình.", source,
                null, List.of(), List.of(), null, null, null, true, false);
        var page = PageRequest.of(0, 5);
        List<Booking> found = code != null ? bookings.findByBookingCodeAndCustomerId(code, actor.id()).stream().toList()
                : pending ? bookings.findByCustomerIdAndStatusAndHoldExpiresAtAfterOrderByCreatedAtDescIdDesc(
                        actor.id(), BookingStatus.PENDING_PAYMENT, Instant.now(), page)
                : bookings.findByCustomerIdOrderByCreatedAtDescIdDesc(actor.id(), page);
        var views = found.stream().map(booking -> jdbc.queryForObject("""
            SELECT t.name,
                COALESCE((SELECT COALESCE(v.outcome,p.status) FROM payment_attempts p
                LEFT JOIN vnpay_checkouts v ON v.payment_attempt_id=p.id WHERE p.booking_id=?
                ORDER BY (p.status='SUCCEEDED') DESC, (v.outcome='REVIEW_REQUIRED') DESC NULLS LAST, p.created_at DESC, p.id DESC LIMIT 1),
                'NOT_STARTED') AS payment
            FROM room_types t WHERE t.id=?
            """, (rs, index) -> new BookingView(booking.getBookingCode(), rs.getString(1), booking.getStartAt(),
                    booking.getEndAt(), booking.getStatus() == BookingStatus.PENDING_PAYMENT && !booking.isHoldActive(Instant.now())
                            ? BookingStatus.EXPIRED.name() : booking.getStatus().name(), rs.getString(2)), booking.getId(), booking.getRoomTypeId())).toList();
        return new Answer(views.isEmpty() ? "Không tìm thấy đơn đặt phòng phù hợp trong tài khoản của bạn." : "Đơn đặt phòng của bạn",
                source, null, List.of(), views, null, null, null, false, false);
    }

    private static Room option(RoomTypeResponse room, Long available, BigDecimal price) {
        return new Room(room.id(), room.name(), room.description(), room.coverImageUrl(), room.maxOccupancy(), room.amenities(), available, price);
    }

    private static boolean hasAmenity(RoomTypeResponse room, String amenity) {
        String wanted = HotelFaqs.normalize(amenity.strip());
        return wanted.isBlank() || room.amenities().stream().anyMatch(value -> HotelFaqs.normalize(value).contains(wanted));
    }

    private static String redact(String text) {
        String value = BOOKING_CODE.matcher(text).replaceAll("BOOKING_REFERENCE");
        value = EMAIL.matcher(value).replaceAll("EMAIL_REDACTED");
        return NUMBER.matcher(value).replaceAll("NUMBER_REDACTED");
    }

    private static Answer faq(Faq value, Source source) {
        return new Answer(value.answer(), source, value.id(), List.of(), List.of(), null, null, null, false, false);
    }

    private static Answer text(String value, Source source) {
        return new Answer(value, source, null, List.of(), List.of(), null, null, null, false, false);
    }

    private static Answer suppressed() {
        return new Answer("", Source.HOTEL_PMS, null, List.of(), List.of(), null, null, null, false, true);
    }
}
