package com.hotelpms.payment.config;

import com.hotelpms.payment.gateway.DemoPaymentGateway;
import com.hotelpms.payment.port.BookingPaymentPort;
import com.hotelpms.payment.port.PaymentGatewayPort;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Đăng ký bean giả lập cho module {@code payment}.
 *
 * <p><b>Điều kiện kích hoạt — phải thỏa CẢ HAI:</b>
 * <ol>
 *   <li>Profile đang chạy là {@code dev} hoặc {@code test} — enforced bằng
 *       {@link Profile} và, ở mức bean, bằng {@link AllowedDemoProfileCondition}
 *       (guard runtime độc lập).</li>
 *   <li>Property {@code app.payment.use-stub-ports=true} — enforced bằng
 *       {@link ConditionalOnProperty}. Property này <b>không</b> được khai báo trong
 *       bất kỳ file cấu hình nào của M4-04, nên mặc định là <b>tắt</b>. Bật thủ
 *       công bằng biến môi trường {@code APP_PAYMENT_USE_STUB_PORTS=true}.</li>
 * </ol>
 *
 * <p>Không có stub nào được tạo ở môi trường mặc định hay production: thiếu profile
 * {@code dev}/{@code test} thì {@link PaymentConfiguration} không được load; thiếu
 * property thì không có bean.
 *
 * <p><b>Không có adapter {@link BookingPaymentPort} trong production M4-04.</b>
 * Module Booking chưa có entity Java trên nhánh này, nên luồng tạo attempt mới
 * sẽ trả HTTP 503 {@code PAYMENT_NOT_AVAILABLE} cho tới khi adapter thật được
 * đăng ký. Xem {@code docs/payment/README.md}.
 *
 * <p><b>Lưu ý về CI:</b> {@code .github/workflows/ci.yml} khởi động app không có
 * profile {@code dev} và không bật property này, nên ở CI các endpoint payment trả
 * 503 — an toàn và không ảnh hưởng build. M4-04 <b>không</b> sửa CI.
 *
 * @author Nguyễn Đình Đạt
 */
@Configuration
@Profile({"dev", "test"})
@ConditionalOnProperty(
        prefix = "app.payment",
        name = "use-stub-ports",
        havingValue = "true")
public class PaymentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfiguration.class);

    /**
     * Cổng thanh toán giả lập DEMO.
     *
     * <p>Bean này chỉ được tạo khi profile đang active chứa {@code dev} hoặc
     * {@code test} (guard runtime {@link AllowedDemoProfileCondition}), kể cả khi
     * property đã được bật.
     *
     * @return bean {@link PaymentGatewayPort} cho cổng DEMO
     */
    @Bean
    @Conditional(AllowedDemoProfileCondition.class)
    PaymentGatewayPort paymentGatewayPort() {
        log.warn("Đang dùng cổng thanh toán DEMO giả lập — KHÔNG dùng cho dữ liệu production.");
        return new DemoPaymentGateway();
    }

    /**
     * Guard runtime: chỉ cho phép tạo stub khi profile đang active thuộc tập
     * được phép ({@code dev} hoặc {@code test}).
     *
     * <p>Đây là lớp kiểm tra thứ hai, độc lập với {@link Profile}: ngay cả khi
     * property {@code app.payment.use-stub-ports} bị bật nhầm, stub vẫn không
     * được tạo ở môi trường không thuộc tập cho phép.
     */
    static class AllowedDemoProfileCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean allowed = Arrays.stream(context.getEnvironment().getActiveProfiles())
                    .anyMatch(profile -> "dev".equals(profile) || "test".equals(profile));
            if (!allowed) {
                log.warn("app.payment.use-stub-ports=true nhưng profile đang chạy không phải dev/test "
                        + "— KHÔNG tạo stub payment. Các endpoint payment sẽ trả 503 PAYMENT_NOT_AVAILABLE.");
            }
            return allowed;
        }
    }
}
