package com.hotelpms.loyalty;

import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.loyalty.dto.LoyaltyHistoryResponse;
import com.hotelpms.loyalty.dto.MembershipSummary;
import com.hotelpms.loyalty.repository.LoyaltyAwardRepository;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MembershipAccountService {
    private final CustomerProfileRepository profiles;
    private final LoyaltyAwardRepository awards;
    private final LoyaltyService rules;

    public MembershipAccountService(CustomerProfileRepository profiles, LoyaltyAwardRepository awards, LoyaltyService rules) {
        this.profiles = profiles;
        this.awards = awards;
        this.rules = rules;
    }

    public MembershipSummary summary(Long customerId) {
        var profile = profiles.findById(customerId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Hồ sơ khách hàng không tồn tại"));
        var tier = profile.getTierCode();
        LoyaltyTier nextTier = switch (tier) {
            case STANDARD -> LoyaltyTier.VIP;
            case VIP -> LoyaltyTier.DIAMOND;
            case DIAMOND -> null;
        };
        BigDecimal threshold = nextTier == null ? null : new BigDecimal(nextTier == LoyaltyTier.VIP ? "10000000.00" : "30000000.00");
        BigDecimal remaining = threshold == null ? BigDecimal.ZERO : threshold.subtract(profile.getEligibleSpendTotal()).max(BigDecimal.ZERO);
        return new MembershipSummary(tier, profile.getEligibleSpendTotal(), profile.getPointsBalance(),
                rules.getDiscountRate(tier), nextTier, threshold, remaining);
    }

    public LoyaltyHistoryResponse history(Long customerId, int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw new ApiException(HttpStatus.BAD_REQUEST,
                "INVALID_PAGINATION", "Trang phải từ 0; số bản ghi mỗi trang từ 1 đến 50");
        var result = awards.findHistory(customerId, PageRequest.of(page, size));
        return new LoyaltyHistoryResponse(result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }
}
