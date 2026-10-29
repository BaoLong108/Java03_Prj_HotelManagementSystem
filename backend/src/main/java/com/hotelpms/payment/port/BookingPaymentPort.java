package com.hotelpms.payment.port;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Port (giao diện) giữa module Payment và module Booking.
 *
 * <p>Module Payment cần biết <b>số tiền phải trả</b> của một booking để đối chiếu
 * với {@code requested_amount} mà khách yêu cầu thanh toán. Module Booking chưa
 * có entity Java trên nhánh {@code NguyenDinhDat} (chỉ có {@code package-info.java}),
 * nên Payment <b>tự định nghĩa</b> interface này — đúng nguyên tắc M4-01
 * "port do module sử dụng tự định nghĩa".
 *
 * <p><b>Trách nhiệm của người implement (Phan Thanh Bình — module booking):</b>
 * <ul>
 *   <li>Tra cứu booking theo {@code bookingId}.</li>
 *   <li>Trả {@link BookingPaymentInfo} chứa <b>số tiền phải trả</b> của booking
 *       (snapshot giá phải trả, {@code NUMERIC(15,2)}).</li>
 *   <li>Trả {@link Optional#empty()} nếu booking không tồn tại — Payment sẽ trả
 *       lỗi nghiệp vụ 404 {@code BOOKING_NOT_FOUND}.</li>
 * </ul>
 *
 * <p><b>Hiện trạng M4-04:</b> chưa có adapter nào trong production. Khi thiếu bean
 * này, {@link com.hotelpms.payment.service.PaymentService} trả HTTP 503
 * {@code PAYMENT_NOT_AVAILABLE}. Chỉ khi adapter thật được đăng ký thì luồng tạo
 * attempt thanh toán mới chạy được.
 *
 * <p><b>Phối hợp cần thiết (Phan Thanh Bình):</b> xem {@code docs/payment/README.md}.
 *
 * @author Nguyễn Đình Đạt
 */
public interface BookingPaymentPort {

    /**
     * Tìm thông tin thanh toán của một booking.
     *
     * @param bookingId ID booking cần tra, không được null
     * @return thông tin booking hoặc {@link Optional#empty()} nếu không tồn tại
     */
    Optional<BookingPaymentInfo> findPayableBooking(Long bookingId);

    /**
     * Thông tin tối thiểu về booking mà Payment cần.
     *
     * <p>Chỉ gồm các trường Payment <b>thực sự dùng</b>. Payment không cần và
     * không giả định trạng thái của booking: nguồn sự thật cho "booking đã được
     * thanh toán" là bảng {@code payment_attempts} (partial unique index
     * {@code uq_payment_attempts_one_success_per_booking} + kiểm tra
     * {@code existsByBookingIdAndStatus(SUCCEEDED)}).
     *
     * @param bookingId     ID booking
     * @param bookingCode   mã booking hiển thị (có thể dùng cho thông báo lỗi)
     * @param customerId    ID khách sở hữu booking (dùng cho kiểm tra quyền ở task tích hợp)
     * @param payableAmount số tiền khách phải trả cho booking này
     */
    record BookingPaymentInfo(
            Long bookingId,
            String bookingCode,
            Long customerId,
            BigDecimal payableAmount
    ) {}
}
