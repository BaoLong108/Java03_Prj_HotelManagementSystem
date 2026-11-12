package com.hotelpms.loyalty.dto;

import java.util.List;

public record LoyaltyHistoryResponse(List<LoyaltyAwardResponse> content, int page, int size, long totalElements, int totalPages) { }
