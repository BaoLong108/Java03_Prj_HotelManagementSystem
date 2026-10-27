package com.hotelpms.payment.service;
import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.booking.service.BookingService;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import java.io.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@Transactional(readOnly=true)
public class BookingConfirmationService {
 private final BookingService bookings;
 private final PaymentAttemptRepository payments;
 public BookingConfirmationService(BookingService bookings,PaymentAttemptRepository payments){this.bookings=bookings;this.payments=payments;}
 public byte[] pdf(String code,Long actor,boolean operational){
  var booking=bookings.getBookingByCode(code,actor,operational);
  var payment=payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.id(),PaymentStatus.SUCCEEDED)
   .orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"PAYMENT_REQUIRED","Chưa có thanh toán thành công"));
  var time=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
  List<String> lines=List.of("HOTEL PMS - BOOKING CONFIRMATION","Demo confirmation - not a tax invoice",
   "Booking: "+booking.bookingCode(),"Status: "+booking.status(),"Arrival: "+time.format(booking.startAt())+" (UTC+07)",
   "Departure: "+time.format(booking.endAt())+" (UTC+07)","Guests: "+booking.guestCount(),
   "Base amount: "+booking.baseAmount().toPlainString()+" VND",
   "Discount: "+booking.discountAmount().toPlainString()+" VND",
   "Payable: "+booking.payableAmount().toPlainString()+" VND",
   "Paid: "+payment.getRequestedAmount().toPlainString()+" VND",
   "Payment: "+payment.getProvider()+" #"+payment.getId(),"Paid at: "+time.format(payment.getSucceededAt())+" (UTC+07)");
  try(var document=new PDDocument();var out=new ByteArrayOutputStream()){
   var page=new PDPage();document.addPage(page);
   try(var content=new PDPageContentStream(document,page)){
    content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
    content.setLeading(25);content.newLineAtOffset(48,735);
    for(String line:lines){content.showText(line);content.newLine();}
    content.endText();
   }
   document.save(out);return out.toByteArray();
  }catch(IOException error){throw new UncheckedIOException(error);}
 }
}
