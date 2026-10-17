package com.hotelpms.pricing.config;

import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.pricing.port.LoyaltyPricingPort;
import com.hotelpms.pricing.port.RoomTypePricingPort;
import com.hotelpms.shared.api.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

/**
 * Bean tạm khi Room/Loyalty chưa implement port.
 *
 * <p>Đây là bean dành cho <strong>dev/test</strong>, không phải dữ liệu production.
 *
 * <p>Kích hoạt: phải đặt property {@code app.pricing.use-stub-ports=true}
 * (mặc định {@code false}). Production giữ {@code false} để Spring fail-fast
 * khi thiếu adapter thật – đúng nguyên tắc M4-01 "không dùng stub làm dữ liệu
 * production". Trong dev profile, bật stub qua biến môi trường
 * {@code PRICING_USE_STUB_PORTS=true} hoặc chỉnh {@code application-dev.yml}.
 *
 * <p>Stub Room <strong>không trả giả</strong>: luôn ném 404 {@code ROOM_TYPE_NOT_FOUND}.
 * Stub Loyalty luôn trả {@link LoyaltyTier#STANDARD} (giá gốc).
 *
 * <p>Khi module Room/Loyalty đăng ký bean thật (Sang/Long), Spring sẽ ưu tiên bean
 * thật nhờ {@code @ConditionalOnMissingBean} và stub này tự động bị bỏ qua.
 *
 * @author Nguyễn Đình Đạt
 */
@Configuration
@ConditionalOnProperty(
        prefix = "app.pricing",
        name = "use-stub-ports",
        havingValue = "true")
public class PricingPortConfiguration {

    @Bean
    @ConditionalOnMissingBean(RoomTypePricingPort.class)
    RoomTypePricingPort roomTypePricingPortStub() {
        return roomTypeId -> {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Loại phòng không tồn tại");
        };
    }

    @Bean
    @ConditionalOnMissingBean(LoyaltyPricingPort.class)
    LoyaltyPricingPort loyaltyPricingPortStub() {
        return userId -> LoyaltyTier.STANDARD;
    }
}
