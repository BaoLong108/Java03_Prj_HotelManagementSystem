package com.hotelpms.booking.controller;
import com.hotelpms.booking.service.StaffReadService;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/staff")
public class StaffReadController {
 private final StaffReadService reads;
 public StaffReadController(StaffReadService reads){this.reads=reads;}
 @GetMapping("/room-calendar")
 public Map<String,Object> calendar(@RequestParam Instant from,@RequestParam Instant to,
   @RequestParam(required=false) Long roomTypeId){return reads.calendar(from,to,roomTypeId);}
 @GetMapping("/bookings/search")
 public Map<String,Object> search(@RequestParam(required=false) String bookingCode,@RequestParam(required=false) String phone){
  return reads.search(bookingCode,phone);
 }
}
