package com.hotelpms.loyalty.adapter;

import com.hotelpms.auth.entity.CustomerProfile;
import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.pricing.port.LoyaltyPricingPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Supplies the persisted membership tier; staff/admin without a customer profile use STANDARD. */
@Component
public class LoyaltyPricingAdapter implements LoyaltyPricingPort {
    private final CustomerProfileRepository profiles;

    public LoyaltyPricingAdapter(CustomerProfileRepository profiles) { this.profiles = profiles; }

    @Override
    @Transactional(readOnly = true)
    public LoyaltyTier findTierByUserId(Long userId) {
        return profiles.findById(userId).map(CustomerProfile::getTierCode).orElse(LoyaltyTier.STANDARD);
    }
}
