package com.hotelpms.room.service;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class RoomAvailabilityService {
 private final JdbcTemplate jdbc;
 public RoomAvailabilityService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 public record AvailableRoomType(Long id,String code,String name,String description,int maxOccupancy,BigDecimal basePricePerNight,long availableRoomCount) {}
 @Transactional(readOnly=true)
 public List<AvailableRoomType> search(Instant startAt,Instant endAt,int guestCount) {
  validate(startAt,endAt,guestCount);
  return jdbc.query("""
   SELECT t.id,t.code,t.name,t.description,t.max_occupancy,t.base_price_per_night,COUNT(r.id) available
   FROM room_types t JOIN physical_rooms r ON r.room_type_id=t.id AND r.is_active=true
   WHERE t.is_active=true AND t.max_occupancy>=?
    AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.physical_room_id=r.id AND b.start_at<? AND b.end_at>?
     AND (b.status IN ('CONFIRMED','COMPLETED') OR (b.status='PENDING_PAYMENT' AND b.hold_expires_at>?)))
    AND NOT EXISTS (SELECT 1 FROM room_blocks x WHERE x.physical_room_id=r.id AND x.released_at IS NULL AND x.start_at<? AND x.end_at>?)
   GROUP BY t.id ORDER BY t.id
   """,(rs,n)->new AvailableRoomType(rs.getLong("id"),rs.getString("code"),rs.getString("name"),rs.getString("description"),
       rs.getInt("max_occupancy"),rs.getBigDecimal("base_price_per_night"),rs.getLong("available")),
   guestCount,java.sql.Timestamp.from(endAt),java.sql.Timestamp.from(startAt),java.sql.Timestamp.from(Instant.now()),
   java.sql.Timestamp.from(endAt),java.sql.Timestamp.from(startAt));
 }
 public static void validate(Instant start,Instant end,int guests) {
  var zone=ZoneId.of("Asia/Ho_Chi_Minh");
  if(start==null||end==null||!start.isBefore(end)||guests<1||guests>20
   ||ChronoUnit.DAYS.between(start.atZone(zone).toLocalDate(),end.atZone(zone).toLocalDate())<1)
   throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PERIOD","Khoảng ở phải có ít nhất một đêm và số khách từ 1 đến 20");
 }
}
