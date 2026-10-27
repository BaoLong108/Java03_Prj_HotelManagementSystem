package com.hotelpms.booking.service;
import com.hotelpms.shared.api.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@Transactional(readOnly=true)
public class StaffReadService {
 private final JdbcTemplate jdbc;
 public StaffReadService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record CalendarRoom(Long physicalRoomId,String roomNumber,Integer floorNumber,Long roomTypeId,
  String roomTypeName,boolean isActive,List<Map<String,Object>> entries) {}
 public Map<String,Object> calendar(Instant from,Instant to,Long roomTypeId){
  if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0
    ||(roomTypeId!=null&&roomTypeId<1))
   throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PERIOD","Lịch phải có khoảng hợp lệ tối đa 31 ngày");
  var rooms=jdbc.query("""
   SELECT r.id,r.room_number,r.floor_number,r.room_type_id,t.name,r.is_active
   FROM physical_rooms r JOIN room_types t ON t.id=r.room_type_id
   WHERE (?::bigint IS NULL OR t.id=?) ORDER BY t.id,r.id
   """,(rs,n)->new CalendarRoom(rs.getLong("id"),rs.getString("room_number"),rs.getObject("floor_number",Integer.class),
    rs.getLong("room_type_id"),rs.getString("name"),rs.getBoolean("is_active"),new ArrayList<>()),roomTypeId,roomTypeId);
  for(var room:rooms){
   room.entries().addAll(jdbc.query("""
    SELECT id,booking_code,status,start_at,end_at,hold_expires_at FROM bookings
    WHERE physical_room_id=? AND start_at<? AND end_at>?
      AND (status IN ('CONFIRMED','COMPLETED') OR (status='PENDING_PAYMENT' AND hold_expires_at>?))
    ORDER BY start_at,id
    """,(rs,n)->{
     Map<String,Object> e=new LinkedHashMap<>();e.put("id",rs.getLong("id"));e.put("kind","BOOKING");
     e.put("bookingCode",rs.getString("booking_code"));e.put("status",rs.getString("status"));
     e.put("startAt",rs.getTimestamp("start_at").toInstant());e.put("endAt",rs.getTimestamp("end_at").toInstant());
     e.put("holdExpiresAt",rs.getTimestamp("hold_expires_at").toInstant());return e;
    },room.physicalRoomId(),java.sql.Timestamp.from(to),java.sql.Timestamp.from(from),java.sql.Timestamp.from(Instant.now())));
   room.entries().addAll(jdbc.query("""
    SELECT id,start_at,end_at,reason FROM room_blocks WHERE physical_room_id=? AND released_at IS NULL
    AND start_at<? AND end_at>? ORDER BY start_at,id
    """,(rs,n)->Map.<String,Object>of("id",rs.getLong("id"),"kind","BLOCK","startAt",rs.getTimestamp("start_at").toInstant(),
     "endAt",rs.getTimestamp("end_at").toInstant(),"reason",rs.getString("reason")),
     room.physicalRoomId(),java.sql.Timestamp.from(to),java.sql.Timestamp.from(from)));
  }
  var types=jdbc.query("SELECT id,name FROM room_types ORDER BY id",
    (rs,n)->Map.<String,Object>of("id",rs.getLong("id"),"name",rs.getString("name")));
  return Map.of("from",from,"to",to,"rooms",rooms,"roomTypes",types);
 }
 public Map<String,Object> search(String bookingCode,String phone){
  boolean byCode=bookingCode!=null&&!bookingCode.isBlank(),byPhone=phone!=null&&!phone.isBlank();
  if(byCode==byPhone ||(byCode&&bookingCode.length()>30)||(byPhone&&!phone.matches("\\+?[0-9]{7,15}")))
   throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_SEARCH","Nhập đúng mã booking hoặc SĐT đầy đủ");
  String filter=byCode?"b.booking_code=?":"regexp_replace(COALESCE(cp.phone_number,''),'[^0-9]','','g')=?";
  String value=byCode?bookingCode.strip():phone.replace("+","");
  var items=jdbc.query("""
   SELECT b.*,t.name AS room_type_name,r.room_number,u.email,cp.phone_number
   FROM bookings b JOIN app_users u ON u.id=b.customer_id JOIN customer_profiles cp ON cp.user_id=u.id JOIN room_types t ON t.id=b.room_type_id
   JOIN physical_rooms r ON r.id=b.physical_room_id WHERE
   """+filter+" ORDER BY b.created_at DESC,b.id DESC LIMIT 50",(rs,n)->{
    Map<String,Object> b=new LinkedHashMap<>();
    b.put("bookingId",rs.getLong("id"));b.put("bookingCode",rs.getString("booking_code"));b.put("status",rs.getString("status"));
    b.put("roomTypeName",rs.getString("room_type_name"));b.put("roomNumber",rs.getString("room_number"));
    b.put("guestCount",rs.getInt("guest_count"));b.put("startAt",rs.getTimestamp("start_at").toInstant());
    b.put("endAt",rs.getTimestamp("end_at").toInstant());b.put("holdExpiresAt",rs.getTimestamp("hold_expires_at").toInstant());
    for(String field:List.of("base_amount","discount_rate","payable_amount"))
     b.put(switch(field){case "base_amount"->"baseAmount";case "discount_rate"->"discountRate";default->"payableAmount";},rs.getBigDecimal(field).toPlainString());
    String digits=Objects.toString(rs.getString("phone_number"),"").replaceAll("[^0-9]","");
    String email=rs.getString("email");int at=email.indexOf('@');
    b.put("customer",Map.of("phoneMasked","*******"+digits.substring(Math.max(0,digits.length()-3)),
     "emailMasked",email.substring(0,1)+"***"+email.substring(at)));
    return b;
   },value);
  return Map.of("items",items);
 }
}
