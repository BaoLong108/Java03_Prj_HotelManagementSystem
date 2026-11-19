package com.hotelpms.payment.controller;
import com.hotelpms.payment.service.BookingConfirmationService;
import com.hotelpms.payment.service.CustomerTransactionService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
public class BookingConfirmationController {
 private final BookingConfirmationService confirmations;
 private final CustomerTransactionService transactions;
 public BookingConfirmationController(BookingConfirmationService confirmations, CustomerTransactionService transactions){
  this.confirmations=confirmations; this.transactions=transactions;
 }
 @GetMapping(value="/api/v1/bookings/{code}/confirmation.pdf",params="!transactionId",produces=MediaType.APPLICATION_PDF_VALUE)
 public ResponseEntity<byte[]> pdf(@PathVariable String code,@AuthenticationPrincipal Jwt jwt,Authentication auth){
  boolean operational=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_STAFF")||a.getAuthority().equals("ROLE_ADMIN"));
  return attachment(code,confirmations.pdf(code,Long.valueOf(jwt.getSubject()),operational));
 }
 @PreAuthorize("hasRole('CUSTOMER')")
 @GetMapping(value="/api/v1/bookings/{code}/confirmation.pdf",params="transactionId",produces=MediaType.APPLICATION_PDF_VALUE)
 public ResponseEntity<byte[]> transactionPdf(@PathVariable String code, @RequestParam long transactionId,
     @AuthenticationPrincipal Jwt jwt) {
  long customerId = Long.parseLong(jwt.getSubject());
  transactions.checkReceipt(customerId, transactionId, code);
  return attachment(code, confirmations.pdf(code, customerId, false));
 }
 private static ResponseEntity<byte[]> attachment(String code, byte[] pdf) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("booking-"+code+".pdf").build().toString())
   .body(pdf);
 }
}
