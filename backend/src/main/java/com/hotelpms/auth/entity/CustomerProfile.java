package com.hotelpms.auth.entity;

import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.shared.model.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "customer_profiles")
public class CustomerProfile extends AuditedEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "eligible_spend_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal eligibleSpendTotal = BigDecimal.ZERO;

    @Column(name = "points_balance", nullable = false)
    private long pointsBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier_code", nullable = false, length = 20)
    private LoyaltyTier tierCode = LoyaltyTier.STANDARD;

    protected CustomerProfile() { }

    public CustomerProfile(User user, String phoneNumber) {
        this.user = user;
        this.phoneNumber = phoneNumber;
    }

    public Long getUserId() { return userId; }
    public String getPhoneNumber() { return phoneNumber; }
    public BigDecimal getEligibleSpendTotal() { return eligibleSpendTotal; }
    public long getPointsBalance() { return pointsBalance; }
    public LoyaltyTier getTierCode() { return tierCode; }
}
