package com.hotelpms.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import com.hotelpms.shared.api.ApiException;
import org.springframework.http.HttpStatus;

@Component
public class PaymentCheckoutPolicy {
    private final boolean demo;
    public PaymentCheckoutPolicy(Environment environment, @Value("${app.payment.demo-enabled:false}") boolean enabled) {
        demo = enabled && environment.acceptsProfiles(Profiles.of("dev | test"))
                && !environment.acceptsProfiles(Profiles.of("prod | production | staging"));
    }
    public void requireDemoAdapter() {
        if (!demo) throw new ApiException(HttpStatus.GONE, "DEMO_PAYMENT_DISABLED", "Thanh toán demo đã được tắt. Vui lòng sử dụng VNPay.");
    }
}
