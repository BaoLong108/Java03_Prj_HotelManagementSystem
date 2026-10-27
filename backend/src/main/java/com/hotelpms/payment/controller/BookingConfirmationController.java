package com.hotelpms.payment.controller;
import com.hotelpms.payment.service.BookingConfirmationService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
public class BookingConfirmationController {
 private final BookingConfirmationService confirmations;
 public BookingConfirmationController(BookingConfirmationService confirmations){this.confirmations=confirmations;}
 @GetMapping(value="/api/v1/bookings/{code}/confirmation.pdf",produces=MediaType.APPLICATION_PDF_VALUE)
 public ResponseEntity<byte[]> pdf(@PathVariable String code,@AuthenticationPrincipal Jwt jwt,Authentication auth){
  boolean operational=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_STAFF")||a.getAuthority().equals("ROLE_ADMIN"));
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("booking-"+code+".pdf").build().toString())
   .body(confirmations.pdf(code,Long.valueOf(jwt.getSubject()),operational));
 }
}
