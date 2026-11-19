package com.hotelpms.payment.vnpay;

import static com.hotelpms.booking.service.BookingService.HOTEL_ZONE;

import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.loyalty.VoucherService;
import com.hotelpms.payment.entity.*;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VnpayService {
    private final BookingRepository bookings;
    private final PaymentAttemptRepository payments;
    private final VoucherService vouchers;
    private final JdbcTemplate jdbc;
    private final VnpayProperties.Settings config;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(HOTEL_ZONE);

    VnpayService(BookingRepository bookings, PaymentAttemptRepository payments, VoucherService vouchers,
            JdbcTemplate jdbc, VnpayProperties.Settings config) {
        this.bookings = bookings; this.payments = payments; this.vouchers = vouchers; this.jdbc = jdbc; this.config = config;
    }

    public record CheckoutRequest(@NotBlank String bookingCode, @NotBlank @Size(max = 100) String idempotencyKey) {}
    public record Checkout(long paymentAttemptId, String bookingCode, String paymentUrl, Instant expiresAt) {}

    @Transactional(noRollbackFor = ApiException.class)
    public Checkout create(CheckoutRequest request, long actor, String ip) {
        if (!config.available()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VNPAY_NOT_CONFIGURED", "VNPay Sandbox chưa được cấu hình.");
        var booking = bookings.findByBookingCodeForUpdate(request.bookingCode()).orElseThrow(VnpayService::notFound);
        if (!booking.getCustomerId().equals(actor)) throw notFound();
        var now = Instant.now();
        if (!booking.isHoldActive(now)) throw conflict("BOOKING_EXPIRED", "Booking không còn trong thời gian giữ chỗ.");
        if (payments.existsByBookingIdAndStatus(booking.getId(), PaymentStatus.SUCCEEDED)) throw conflict("BOOKING_ALREADY_PAID", "Booking đã thanh toán.");
        if (booking.getPayableAmount().signum() == 0) throw conflict("ZERO_PAYABLE", "Booking 0 ₫ dùng bước xác nhận không thu tiền.");
        if (!cents(booking.getPayableAmount()).matches("[1-9][0-9]{0,11}")) throw conflict("VNPAY_AMOUNT_UNSUPPORTED", "Số tiền vượt giới hạn giao dịch của cổng thanh toán.");
        String key = request.idempotencyKey().strip();
        var prior = payments.findByBookingIdAndProviderAndIdempotencyKey(booking.getId(), PaymentProvider.VNPAY, key);
        if (prior.isPresent()) {
            if (prior.get().getRequestedAmount().compareTo(booking.getPayableAmount()) != 0) throw conflict("PAYMENT_AMOUNT_CHANGED", "Giá booking đã thay đổi. Vui lòng tạo lượt thanh toán mới.");
            return checkout(prior.get().getId(), booking.getBookingCode());
        }
        var pending = payments.findFirstByBookingIdAndProviderAndStatusOrderByCreatedAtDescIdDesc(
                booking.getId(), PaymentProvider.VNPAY, PaymentStatus.PENDING);
        if (pending.isPresent()) {
            if (pending.get().getRequestedAmount().compareTo(booking.getPayableAmount()) != 0) {
                throw conflict("PAYMENT_IN_PROGRESS", "Đã có lượt thanh toán VNPay cần đối soát.");
            }
            return checkout(pending.get().getId(), booking.getBookingCode());
        }
        var payment = payments.saveAndFlush(new PaymentAttempt(booking.getId(), PaymentProvider.VNPAY,
                key, booking.getPayableAmount()));
        String reference = "PMS" + payment.getId() + UUID.randomUUID().toString().replace("-", "");
        var fields = new TreeMap<String, String>();
        fields.put("vnp_Version", "2.1.0"); fields.put("vnp_Command", "pay");
        fields.put("vnp_TmnCode", config.tmnCode()); fields.put("vnp_Amount", cents(payment.getRequestedAmount()));
        fields.put("vnp_CurrCode", "VND"); fields.put("vnp_TxnRef", reference);
        fields.put("vnp_OrderInfo", "Booking " + booking.getBookingCode()); fields.put("vnp_OrderType", "other");
        fields.put("vnp_Locale", "vn"); fields.put("vnp_ReturnUrl", org.springframework.web.util.UriComponentsBuilder
                .fromUriString(config.returnUrl()).queryParam("bookingCode", booking.getBookingCode()).build().toUriString());
        fields.put("vnp_IpAddr", ip); fields.put("vnp_CreateDate", TIME.format(now));
        fields.put("vnp_ExpireDate", TIME.format(booking.getHoldExpiresAt()));
        String url = config.paymentUrl() + "?" + VnpaySignature.query(fields) + "&vnp_SecureHash=" + VnpaySignature.sign(fields, config.hashSecret());
        jdbc.update("INSERT INTO vnpay_checkouts(payment_attempt_id,reference,checkout_url,expires_at) VALUES (?,?,?,?)",
                payment.getId(), reference, url, Timestamp.from(booking.getHoldExpiresAt()));
        return new Checkout(payment.getId(), booking.getBookingCode(), url, booking.getHoldExpiresAt());
    }

    @Transactional
    public Map<String, String> ipn(Map<String, String> fields) {
        // A disabled checkout must still record signed notifications for transactions already issued.
        if (!config.hasMerchantCredentials() || !VnpaySignature.valid(fields, config.hashSecret())
                || !config.tmnCode().equals(fields.get("vnp_TmnCode"))) return reply("97", "Invalid signature");
        // PAY 2.1.0 is VND-only and its standard IPN does not echo the currency.
        if (!"VND".equals(fields.getOrDefault("vnp_CurrCode", "VND"))) return reply("99", "Invalid currency");
        var ids = jdbc.queryForList("""
                SELECT p.booking_id FROM vnpay_checkouts c JOIN payment_attempts p ON p.id=c.payment_attempt_id
                WHERE c.reference=?
                """, Long.class, fields.get("vnp_TxnRef"));
        if (ids.isEmpty()) return reply("01", "Order not found");
        var booking = bookings.findByIdForUpdate(ids.getFirst()).orElseThrow(VnpayService::notFound);
        var rows = jdbc.queryForList("SELECT * FROM vnpay_checkouts WHERE reference=? FOR UPDATE", fields.get("vnp_TxnRef"));
        var row = rows.getFirst();
        var payment = payments.findById(((Number) row.get("payment_attempt_id")).longValue()).orElseThrow();
        if (!cents(payment.getRequestedAmount()).equals(fields.get("vnp_Amount"))) return reply("04", "Invalid amount");
        if (!"PENDING".equals(row.get("outcome"))) return reply("02", "Order already confirmed");
        var transaction = fields.get("vnp_TransactionNo");
        if (transaction == null || !transaction.matches("[0-9]{1,15}")
                || !fields.getOrDefault("vnp_ResponseCode", "").matches("[0-9]{2}")
                || !fields.getOrDefault("vnp_TransactionStatus", "").matches("[0-9]{2}")) return reply("99", "Invalid transaction");
        boolean success = "00".equals(fields.get("vnp_ResponseCode")) && "00".equals(fields.get("vnp_TransactionStatus"));
        if (success && new java.math.BigInteger(transaction).signum() == 0) return reply("99", "Invalid transaction");
        var duplicate = transaction.equals("0") ? java.util.Optional.<PaymentAttempt>empty()
                : payments.findByProviderAndProviderTransactionId(PaymentProvider.VNPAY, transaction);
        if (duplicate.isPresent() && !duplicate.get().getId().equals(payment.getId())) return reply("99", "Transaction already assigned");
        var now = Instant.now();
        String outcome;
        if (!success) {
            payment.markFailed(); outcome = "FAILED";
        } else if (!booking.isHoldActive(now) || payment.getRequestedAmount().compareTo(booking.getPayableAmount()) != 0
                || payments.existsByBookingIdAndStatus(booking.getId(), PaymentStatus.SUCCEEDED)) {
            // A received success after expiry or a changed snapshot requires manual reconciliation.
            // Preserve the provider notification without assigning an unavailable room.
            outcome = "REVIEW_REQUIRED";
        } else {
            try {
                vouchers.consumeForPayment(booking, now);
                payment.markSucceeded(now); booking.confirm(now);
                bookings.saveAndFlush(booking); outcome = "SUCCEEDED";
            } catch (ApiException invalidVoucher) {
                outcome = "REVIEW_REQUIRED";
            }
        }
        if (!transaction.equals("0")) payment.setProviderTransactionId(transaction);
        payments.saveAndFlush(payment);
        jdbc.update("""
                UPDATE vnpay_checkouts SET outcome=?,response_code=?,transaction_status=?,
                    provider_transaction_id=?,received_at=? WHERE payment_attempt_id=?
                """, outcome, fields.get("vnp_ResponseCode"), fields.get("vnp_TransactionStatus"), transaction, Timestamp.from(now), payment.getId());
        return reply("00", "Confirm success");
    }

    public boolean available() { return config.available(); }

    public record PaymentState(String bookingCode, String bookingStatus, String paymentStatus, String provider,
            String outcome, String responseCode, Instant holdExpiresAt, BigDecimal payableAmount) {}

    @Transactional(readOnly = true)
    public PaymentState status(String code, String reference, long actor) {
        Long attemptId = null;
        if (reference != null) {
            if (!reference.matches("[0-9A-Za-z]{1,100}")) throw notFound();
            var attempt = jdbc.queryForList("""
                SELECT b.booking_code, p.id FROM vnpay_checkouts c JOIN payment_attempts p ON p.id=c.payment_attempt_id
                JOIN bookings b ON b.id=p.booking_id WHERE c.reference=? AND b.customer_id=?
                """, reference, actor).stream().findFirst().orElseThrow(VnpayService::notFound);
            var referenceCode = (String) attempt.get("booking_code");
            if (code != null && !code.equals(referenceCode)) throw notFound();
            code = referenceCode;
            attemptId = ((Number) attempt.get("id")).longValue();
        }
        if (code == null || !code.matches("BK[0-9A-Z-]{1,28}")) throw notFound();
        var booking = bookings.findByBookingCodeAndCustomerId(code, actor).orElseThrow(VnpayService::notFound);
        var payment = attemptId != null ? payments.findById(attemptId)
                : payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.SUCCEEDED)
                  .or(() -> payments.findFirstByBookingIdOrderByCreatedAtDescIdDesc(booking.getId()));
        var checkout = payment.isEmpty() ? java.util.List.<Map<String,Object>>of()
                : jdbc.queryForList("SELECT outcome,response_code FROM vnpay_checkouts WHERE payment_attempt_id=?", payment.get().getId());
        return new PaymentState(booking.getBookingCode(), booking.getStatus().name(), payment.map(p -> p.getStatus().name()).orElse("NONE"),
                payment.map(p -> p.getProvider().name()).orElse(null), checkout.isEmpty() ? null : (String) checkout.getFirst().get("outcome"),
                checkout.isEmpty() ? null : (String) checkout.getFirst().get("response_code"), booking.getHoldExpiresAt(), booking.getPayableAmount());
    }

    private Checkout checkout(long id, String code) {
        return jdbc.queryForObject("SELECT checkout_url,expires_at,outcome FROM vnpay_checkouts WHERE payment_attempt_id=?",
                (rs, n) -> {
                    if (!"PENDING".equals(rs.getString(3)) || !rs.getTimestamp(2).toInstant().isAfter(Instant.now()))
                        throw conflict("PAYMENT_RETRY_REQUIRED", "Lượt thanh toán cũ đã kết thúc. Vui lòng thử lại.");
                    return new Checkout(id, code, rs.getString(1), rs.getTimestamp(2).toInstant());
                }, id);
    }
    private static String cents(BigDecimal amount) { return amount.movePointRight(2).toBigIntegerExact().toString(); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Không tìm thấy booking."); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static Map<String, String> reply(String code, String message) { return Map.of("RspCode", code, "Message", message); }
}
