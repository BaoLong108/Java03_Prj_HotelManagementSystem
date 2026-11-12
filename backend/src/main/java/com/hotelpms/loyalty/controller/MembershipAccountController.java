package com.hotelpms.loyalty.controller;

import com.hotelpms.loyalty.MembershipAccountService;
import com.hotelpms.loyalty.dto.LoyaltyHistoryResponse;
import com.hotelpms.loyalty.dto.MembershipSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** No customerId input: the persisted account is always selected by the JWT subject. */
@RestController
@RequestMapping("/api/v1/customers/me")
public class MembershipAccountController {
    private final MembershipAccountService accounts;

    public MembershipAccountController(MembershipAccountService accounts) { this.accounts = accounts; }

    @GetMapping("/membership")
    public MembershipSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return accounts.summary(Long.valueOf(jwt.getSubject()));
    }

    @GetMapping("/loyalty-awards")
    public LoyaltyHistoryResponse history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return accounts.history(Long.valueOf(jwt.getSubject()), page, size);
    }
}
