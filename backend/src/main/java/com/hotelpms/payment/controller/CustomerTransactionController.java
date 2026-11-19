package com.hotelpms.payment.controller;

import com.hotelpms.payment.dto.CustomerTransaction;
import com.hotelpms.payment.dto.CustomerTransaction.Sort;
import com.hotelpms.payment.dto.CustomerTransaction.Status;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.service.CustomerTransactionService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/transactions")
public class CustomerTransactionController {
    private final CustomerTransactionService transactions;

    public CustomerTransactionController(CustomerTransactionService transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    public CustomerTransaction.Page list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) PaymentProvider provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "NEWEST") Sort sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return transactions.list(Long.parseLong(jwt.getSubject()), status, provider, from, to, search, sort, page, size);
    }

    @GetMapping("/{id}")
    public CustomerTransaction.Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return transactions.get(Long.parseLong(jwt.getSubject()), id);
    }
}
