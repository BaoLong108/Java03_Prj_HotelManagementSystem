package com.hotelpms.loyalty;

import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.booking.dto.BookingResponse;
import com.hotelpms.booking.entity.Booking;
import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.loyalty.dto.*;
import com.hotelpms.loyalty.repository.LoyaltyVoucherRepository;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class VoucherService {
    private static final List<VoucherOffer> OFFERS = List.of(new VoucherOffer(50, new BigDecimal("400000.00")),
            new VoucherOffer(100, new BigDecimal("1000000.00")), new VoucherOffer(200, new BigDecimal("2200000.00")));
    private final CustomerProfileRepository profiles;
    private final BookingRepository bookings;
    private final LoyaltyVoucherRepository vouchers;
    private final JdbcTemplate jdbc;
    private final VoucherCodeGenerator codes;

    public VoucherService(CustomerProfileRepository profiles, BookingRepository bookings, LoyaltyVoucherRepository vouchers, JdbcTemplate jdbc,
            VoucherCodeGenerator codes) {
        this.profiles = profiles;
        this.bookings = bookings;
        this.vouchers = vouchers;
        this.jdbc = jdbc;
        this.codes = codes;
    }

    public List<VoucherOffer> offers() { return OFFERS; }

    @Transactional
    public VoucherResponse redeem(Long actor, RedeemVoucherRequest request) {
        var offer = OFFERS.stream().filter(value -> value.pointsCost() == request.pointsCost()).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VOUCHER_OFFER", "Chỉ đổi được gói 50, 100 hoặc 200 điểm"));
        var profile = profiles.findByIdForUpdate(actor).orElseThrow(() -> error("CUSTOMER_NOT_FOUND", "Không có hồ sơ khách hàng"));
        var existing = vouchers.findByCustomerIdAndIdempotencyKey(actor, request.idempotencyKey());
        if (existing.isPresent()) {
            if (existing.get().getPointsCost() != offer.pointsCost()) throw error("IDEMPOTENCY_KEY_REUSED", "Yêu cầu này đã dùng cho gói đổi khác");
            return VoucherResponse.from(existing.get());
        }
        if (profile.getPointsBalance() < offer.pointsCost()) throw error("INSUFFICIENT_POINTS", "Bạn chưa đủ điểm để đổi gói này");
        for (int retry = 0; retry < 10; retry++) {
            // ON CONFLICT does not poison the transaction when random codes collide.
            var ids = jdbc.query("""
                insert into loyalty_vouchers(code,customer_id,points_cost,discount_amount,idempotency_key)
                values(?,?,?,?,?) on conflict do nothing returning id
                """, (rs, index) -> rs.getLong(1), codes.generate(), actor, offer.pointsCost(), offer.discountAmount(), request.idempotencyKey());
            if (!ids.isEmpty()) {
                profile.spendPoints(offer.pointsCost());
                profiles.saveAndFlush(profile);
                return VoucherResponse.from(vouchers.findById(ids.getFirst()).orElseThrow());
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VOUCHER_CODE_UNAVAILABLE", "Chưa tạo được mã, vui lòng thử lại cùng yêu cầu");
    }

    @Transactional(readOnly = true)
    public VoucherPage wallet(Long actor, int page) {
        if (page < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Trang phải từ 0");
        var result = vouchers.findByCustomerIdOrderByCreatedAtDescIdDesc(actor, PageRequest.of(page, 10));
        return new VoucherPage(result.stream().map(VoucherResponse::from).toList(), page, result.getTotalPages(), result.getTotalElements());
    }

    @Transactional
    public BookingResponse apply(String bookingCode, String code, Long actor) {
        var booking = lockOwnedHold(bookingCode, actor);
        if (booking.getVoucherCode() != null && !booking.getVoucherCode().equals(code))
            throw error("REMOVE_CURRENT_VOUCHER", "Vui lòng gỡ mã hiện tại trước khi dùng mã khác");
        var voucher = vouchers.lockByCode(code).filter(value -> value.getCustomerId().equals(actor))
                .orElseThrow(() -> error("VOUCHER_NOT_AVAILABLE", "Mã giảm giá không khả dụng cho tài khoản này"));
        if (voucher.getUsedAt() != null) throw error("VOUCHER_ALREADY_USED", "Mã giảm giá đã được sử dụng");
        if (voucher.getBookingId() != null && !voucher.getBookingId().equals(booking.getId()))
            throw error("VOUCHER_RESERVED", "Mã đang được áp dụng cho một booking khác");
        if (!booking.isHoldActive(Instant.now())) throw error("BOOKING_EXPIRED", "Đã hết hạn giữ phòng");
        voucher.reserve(booking.getId());
        booking.applyVoucher(code, voucher.getDiscountAmount());
        vouchers.saveAndFlush(voucher);
        return BookingResponse.from(bookings.saveAndFlush(booking));
    }

    @Transactional
    public BookingResponse remove(String bookingCode, Long actor) {
        var booking = lockOwnedHold(bookingCode, actor);
        releaseForBooking(booking.getId());
        booking.removeVoucher();
        return BookingResponse.from(bookings.saveAndFlush(booking));
    }

    /** Caller already holds the booking lock. Validate before any writes on the payment path. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    public void consumeForPayment(Booking booking, Instant now) {
        if (booking.getVoucherCode() == null) return;
        var voucher = vouchers.lockByCode(booking.getVoucherCode()).orElseThrow(() -> error("VOUCHER_NOT_AVAILABLE", "Mã không còn khả dụng"));
        if (!booking.getCustomerId().equals(voucher.getCustomerId()) || !booking.getId().equals(voucher.getBookingId())
                || voucher.getUsedAt() != null) throw error("VOUCHER_NOT_AVAILABLE", "Mã không còn khả dụng cho booking này");
        if (!booking.isHoldActive(Instant.now())) throw error("BOOKING_EXPIRED", "Đã hết hạn giữ phòng");
        if (booking.getVoucherDiscountAmount().compareTo(booking.getAfterMembershipAmount().min(voucher.getDiscountAmount())) != 0)
            throw error("VOUCHER_PRICE_MISMATCH", "Giá trị giảm không khớp, vui lòng tải lại booking");
        voucher.consume(now);
        vouchers.saveAndFlush(voucher);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForBooking(Long bookingId) {
        vouchers.lockReservedForBooking(bookingId).ifPresent(voucher -> { voucher.release(); vouchers.saveAndFlush(voucher); });
    }

    @Transactional
    public void releaseExpiredReservation(Long bookingId, Instant now) {
        var booking = bookings.findByIdForUpdate(bookingId).orElse(null);
        if (booking == null) return;
        if (booking.getStatus() == BookingStatus.CANCELLED || booking.getStatus() == BookingStatus.EXPIRED
                || (booking.getStatus() == BookingStatus.PENDING_PAYMENT && !booking.getHoldExpiresAt().isAfter(now)))
            releaseForBooking(bookingId);
    }

    private Booking lockOwnedHold(String code, Long actor) {
        var booking = bookings.findByBookingCodeForUpdate(code).orElseThrow(() -> error("BOOKING_NOT_FOUND", "Không tìm thấy booking"));
        if (!booking.getCustomerId().equals(actor)) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Chỉ chủ booking được dùng mã giảm giá");
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) throw error("INVALID_BOOKING_STATUS", "Chỉ áp dụng mã cho booking đang chờ thanh toán");
        if (!booking.isHoldActive(Instant.now())) throw error("BOOKING_EXPIRED", "Đã hết hạn giữ phòng");
        return booking;
    }

    private ApiException error(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
