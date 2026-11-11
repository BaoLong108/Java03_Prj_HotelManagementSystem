package com.hotelpms.payment.vnpay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VnpayProperties.Settings.class)
class VnpayProperties {
    @ConfigurationProperties("app.vnpay")
    record Settings(boolean enabled, String tmnCode, String hashSecret,
                    String paymentUrl, String returnUrl) {
        boolean available() {
            return enabled && tmnCode != null && !tmnCode.isBlank() && hashSecret != null && !hashSecret.isBlank()
                    && paymentUrl != null && paymentUrl.startsWith("https://sandbox.vnpayment.vn/")
                    && returnUrl != null && !returnUrl.isBlank();
        }
    }
}
