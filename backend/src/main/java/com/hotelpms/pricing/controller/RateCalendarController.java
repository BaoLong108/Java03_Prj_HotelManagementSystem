package com.hotelpms.pricing.controller;

import com.hotelpms.pricing.dto.RateRuleRequest;
import com.hotelpms.pricing.model.RateRule;
import com.hotelpms.pricing.service.RateCalendarService;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class RateCalendarController {
    private final RateCalendarService rates;

    public RateCalendarController(RateCalendarService rates) { this.rates = rates; }

    @GetMapping("/rate-calendar")
    public RateCalendarService.Calendar calendar(@RequestParam long roomTypeId,
            @RequestParam @DateTimeFormat(pattern = "uuuu-MM") YearMonth month) {
        return rates.calendar(roomTypeId, month);
    }

    @PostMapping("/rate-calendar/preview")
    public List<RateCalendarService.Preview> preview(@Valid @RequestBody RateRuleRequest request,
                                                    @RequestParam(required = false) Long ruleId) {
        return rates.preview(request, ruleId);
    }

    @PatchMapping("/rate-calendar/{roomTypeId}/base-rate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void baseRate(@PathVariable long roomTypeId, @Valid @RequestBody RateRuleRequest.BaseRate request) {
        rates.setBaseRate(roomTypeId, request.nightlyRate());
    }

    @PostMapping("/rate-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RateRule create(@Valid @RequestBody RateRuleRequest request) { return rates.save(request, null); }

    @PutMapping("/rate-rules/{id}")
    public RateRule update(@PathVariable long id, @Valid @RequestBody RateRuleRequest request) {
        return rates.save(request, id);
    }

    @PatchMapping("/rate-rules/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable long id, @RequestParam long roomTypeId) { rates.deactivate(roomTypeId, id); }
}
