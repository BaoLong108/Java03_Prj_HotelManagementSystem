package com.hotelpms.loyalty.dto;
import java.util.List;
public record VoucherPage(List<VoucherResponse> content, int page, int totalPages, long totalElements) { }
