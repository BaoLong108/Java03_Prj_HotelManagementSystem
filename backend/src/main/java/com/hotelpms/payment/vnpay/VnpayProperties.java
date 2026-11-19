package com.hotelpms.payment.vnpay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VnpayProperties.Settings.class)
class VnpayProperties {
    @ConfigurationProperties("app.vnpay")
    record Settings(boolean enabled, String tmnCode, String hashSecret,
                    String paymentUrl, String returnUrl, String ipnUrl) {
        boolean available() {
            return enabled && hasMerchantCredentials()
                    && validPaymentUrl(paymentUrl) && validReturnUrl(returnUrl) && validIpnUrl(ipnUrl);
        }

        boolean hasMerchantCredentials() {
            return tmnCode != null && !tmnCode.isBlank() && hashSecret != null && !hashSecret.isBlank();
        }
        private static boolean validPaymentUrl(String value) {
            try { var uri = java.net.URI.create(value); return "https".equals(uri.getScheme())
                    && "sandbox.vnpayment.vn".equals(uri.getHost()) && uri.getUserInfo() == null && uri.getFragment() == null
                    && uri.getQuery() == null && (uri.getPort() == -1 || uri.getPort() == 443) && "/paymentv2/vpcpay.html".equals(uri.getPath()); }
            catch (RuntimeException error) { return false; }
        }
        private static boolean validReturnUrl(String value) {
            try { var uri = java.net.URI.create(value); return uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                    && ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost()))); }
            catch (RuntimeException error) { return false; }
        }
        private static boolean validIpnUrl(String value) {
            try { var uri = java.net.URI.create(value); return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && !java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost())
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && "/api/v1/payments/vnpay/ipn".equals(uri.getPath()); }
            catch (RuntimeException error) { return false; }
        }
    }
}
