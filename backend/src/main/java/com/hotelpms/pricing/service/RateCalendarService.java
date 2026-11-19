package com.hotelpms.pricing.service;

import com.hotelpms.pricing.dto.RateRuleRequest;
import com.hotelpms.pricing.model.RateRule;
import com.hotelpms.pricing.port.RoomTypePricingPort;
import com.hotelpms.pricing.repository.RateRuleRepository;
import com.hotelpms.room.entity.RoomType;
import com.hotelpms.room.repository.RoomTypeRepository;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RateCalendarService {
    private final RoomTypePricingPort pricingRooms;
    private final RoomTypeRepository rooms;
    private final RateRuleRepository rules;

    public RateCalendarService(RoomTypePricingPort pricingRooms, RoomTypeRepository rooms, RateRuleRepository rules) {
        this.pricingRooms = pricingRooms;
        this.rooms = rooms;
        this.rules = rules;
    }

    public record Calendar(long roomTypeId, YearMonth month, BigDecimal baseRate,
                           List<NightlyPricing.Night> nights, List<RateRule> rules) { }

    public record Preview(NightlyPricing.Night before, NightlyPricing.Night after) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Calendar calendar(long roomTypeId, YearMonth month) {
        validateDate(month.atDay(1));
        BigDecimal base = pricingRooms.findActiveById(roomTypeId).basePricePerNight();
        var active = rules.overlapping(roomTypeId, month.atDay(1), month.atEndOfMonth());
        var nights = month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1))
                .map(date -> NightlyPricing.resolve(date, base, active)).toList();
        return new Calendar(roomTypeId, month, base, nights, active);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Preview> preview(RateRuleRequest request, Long id) {
        RateRule draft = draft(request, id);
        BigDecimal base = pricingRooms.findActiveById(request.roomTypeId()).basePricePerNight();
        var before = rules.overlapping(request.roomTypeId(), draft.startsOn(), draft.endsOn());
        ensureNoOverlap(draft, before);
        var dates = draft.startsOn().datesUntil(draft.endsOn().plusDays(1));
        if (id != null) {
            RateRule original = existing(request.roomTypeId(), id);
            before = Stream.concat(before.stream(), rules.overlapping(request.roomTypeId(), original.startsOn(), original.endsOn()).stream())
                    .distinct().toList();
            dates = Stream.concat(dates, original.startsOn().datesUntil(original.endsOn().plusDays(1))).distinct().sorted();
        }
        List<RateRule> current = before;
        var proposed = new ArrayList<>(current.stream().filter(rule -> !Objects.equals(rule.id(), id)).toList());
        proposed.add(draft);
        return dates.map(date -> new Preview(NightlyPricing.resolve(date, base, current),
                NightlyPricing.resolve(date, base, proposed))).toList();
    }

    @Transactional
    public RateRule save(RateRuleRequest request, Long id) {
        RateRule draft = draft(request, id);
        lockActiveRoom(request.roomTypeId());
        if (id != null) existing(request.roomTypeId(), id);
        ensureNoOverlap(draft, rules.overlapping(request.roomTypeId(), draft.startsOn(), draft.endsOn()));
        return id == null ? rules.insert(request.roomTypeId(), draft) : rules.update(request.roomTypeId(), draft);
    }

    @Transactional
    public void deactivate(long roomTypeId, long id) {
        lockActiveRoom(roomTypeId);
        existing(roomTypeId, id);
        rules.deactivate(roomTypeId, id);
    }

    @Transactional
    public void setBaseRate(long roomTypeId, BigDecimal price) {
        lockActiveRoom(roomTypeId).setBasePricePerNight(price);
    }

    private RoomType lockActiveRoom(long roomTypeId) {
        return rooms.lockById(roomTypeId).filter(RoomType::isActive).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại hoặc đã ngừng bán"));
    }

    private RateRule existing(long roomTypeId, long id) {
        return rules.find(roomTypeId, id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "RATE_RULE_NOT_FOUND", "Không tìm thấy quy tắc giá đang áp dụng cho loại phòng này"));
    }

    private static RateRule draft(RateRuleRequest request, Long id) {
        validateDate(request.startsOn());
        validateDate(request.endsOn());
        long days = ChronoUnit.DAYS.between(request.startsOn(), request.endsOn());
        if (days < 0 || days > 365) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RATE_RANGE", "Khoảng áp dụng phải từ 1 đến 366 đêm");
        }
        if (request.kind() == RateRule.Kind.WEEKEND && request.startsOn().datesUntil(request.endsOn().plusDays(1))
                .noneMatch(NightlyPricing::isWeekend)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RATE_RANGE", "Khoảng áp dụng phải có thứ Bảy hoặc Chủ nhật");
        }
        return new RateRule(id, request.kind(), request.startsOn(), request.endsOn(), request.nightlyRate());
    }

    private static void validateDate(LocalDate date) {
        if (date.getYear() < 1900 || date.getYear() > 9999) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RATE_DATE", "Năm áp dụng phải từ 1900 đến 9999");
        }
    }

    private static void ensureNoOverlap(RateRule draft, List<RateRule> overlapping) {
        if (overlapping.stream().anyMatch(rule -> rule.kind() == draft.kind() && !Objects.equals(rule.id(), draft.id()))) {
            throw new ApiException(HttpStatus.CONFLICT, "RATE_RULE_OVERLAP", "Đã có quy tắc cùng loại trong khoảng này. Hãy sửa quy tắc đó hoặc chọn khoảng khác.");
        }
    }
}
