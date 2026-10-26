package com.hotelpms.pricing.config;
import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.pricing.port.*;
import com.hotelpms.shared.api.ApiException;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
/** Real database adapters shared by quote and booking snapshots. */
@Configuration
public class DatabasePricingConfiguration {
 @Bean RoomTypePricingPort roomTypePricingPort(JdbcTemplate jdbc){
  return id->jdbc.query("SELECT id,base_price_per_night,max_occupancy,is_active FROM room_types WHERE id=? AND is_active=true",
   (rs,n)->new RoomTypePricingPort.RoomTypeInfo(rs.getLong("id"),rs.getBigDecimal("base_price_per_night"),
   rs.getInt("max_occupancy"),rs.getBoolean("is_active")),id).stream().findFirst()
   .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"ROOM_TYPE_NOT_FOUND","Loại phòng không tồn tại"));
 }
 @Bean LoyaltyPricingPort loyaltyPricingPort(CustomerProfileRepository profiles){
  return id->profiles.findById(id).map(p->p.getTierCode()==null?LoyaltyTier.STANDARD:p.getTierCode()).orElse(LoyaltyTier.STANDARD);
 }
}
