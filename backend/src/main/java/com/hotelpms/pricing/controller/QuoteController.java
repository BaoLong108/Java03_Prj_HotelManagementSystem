package com.hotelpms.pricing.controller;

import com.hotelpms.pricing.dto.QuoteRequest;
import com.hotelpms.pricing.dto.QuoteResponse;
import com.hotelpms.pricing.service.QuoteService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API báo giá công khai: {@code GET /api/v1/pricing/quote}.
 *
 * <p>Guest chưa đăng nhập được xem quote (hạng STANDARD). Nếu có JWT,
 * userId lấy từ token, không lấy từ query, để client không tự khai hạng.
 *
 * @author Nguyễn Đình Đạt
 */
@RestController
@RequestMapping("/api/v1/pricing")
public class QuoteController {

    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/quote")
    public QuoteResponse quote(@Valid QuoteRequest request, @AuthenticationPrincipal Jwt jwt) {
        Long customerId = jwt == null ? null : Long.valueOf(jwt.getSubject());
        return quotes.quote(request, customerId);
    }
}
