package com.hotelpms.payment.service;

import com.hotelpms.payment.dto.CreatePaymentRequest;
import com.hotelpms.payment.entity.PaymentAttempt;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.entity.PaymentStatus;
import com.hotelpms.payment.port.BookingPaymentPort;
import com.hotelpms.payment.port.BookingPaymentPort.BookingPaymentInfo;
import com.hotelpms.payment.port.PaymentGatewayPort;
import com.hotelpms.payment.port.PaymentGatewayPort.GatewayRequest;
import com.hotelpms.payment.port.PaymentGatewayPort.GatewayResult;
import com.hotelpms.payment.port.PaymentGatewayPort.Outcome;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tầng service của luồng thanh toán demo (M4-04).
 *
 * <h2>Thứ tự kiểm tra (bắt buộc)</h2>
 * <ol>
 *   <li><b>Idempotency</b> — tra cứu attempt theo
 *       {@code (bookingId, provider, idempotencyKey)} <b>trước</b> mọi kiểm tra
 *       khác, và <b>trước</b> kiểm tra "booking đã thanh toán".</li>
 *   <li>Nếu attempt đã tồn tại và <b>cùng số tiền</b> ⇒ kiểm tra quyền sở hữu,
 *       rồi trả lại attempt cũ, <b>không</b> tạo attempt mới và <b>không</b> gọi
 *       lại gateway.</li>
 *   <li>Nếu attempt đã tồn tại nhưng <b>khác số tiền</b> ⇒ 409
 *       {@code IDEMPOTENCY_AMOUNT_CONFLICT}.</li>
 *   <li>Kiểm tra gateway có khả dụng không ⇒ nếu thiếu thì 503
 *       {@code PAYMENT_NOT_AVAILABLE}.</li>
 *   <li>Tra booking và <b>kiểm tra chủ sở hữu</b> ⇒ booking không tồn tại hoặc
 *       không thuộc về người gọi thì 404 {@code BOOKING_NOT_FOUND}.</li>
 *   <li>Đối chiếu {@code amount} với số tiền phải trả của booking ⇒ lệch thì 409
 *       {@code PAYMENT_AMOUNT_MISMATCH}.</li>
 *   <li>Kiểm tra booking đã có attempt {@code SUCCEEDED} chưa ⇒ có thì 409
 *       {@code BOOKING_ALREADY_PAID}.</li>
 * </ol>
 *
 * <h2>Quyền sở hữu (fail closed)</h2>
 * <p>Mọi thao tác đọc/ghi thanh toán đều phải xác minh rằng người gọi (user ID
 * lấy từ JWT, do server ký) là chủ sở hữu của booking. Nếu <b>không</b> xác minh
 * được — thiếu adapter, booking không tồn tại, hoặc {@code customerId} không có —
 * thì <b>không</b> trả attempt mà <b>fail closed</b> (503/404).
 *
 * <p>Thông báo lỗi dùng mã 404 {@code BOOKING_NOT_FOUND} /
 * {@code PAYMENT_ATTEMPT_NOT_FOUND} để không tiết lộ sự tồn tại của dữ liệu của
 * người khác.
 *
 * <h2>Transaction và race condition</h2>
 * <p>{@link #pay} <b>không</b> được đánh dấu {@code @Transactional}: luồng gồm
 * nhiều transaction tách biệt, điều khiển bằng {@link TransactionTemplate} để
 * tránh vấn đề self-invocation của proxy {@code @Transactional}.
 *
 * <ul>
 *   <li><b>P1 reserve</b> — insert attempt {@code PENDING} trong transaction riêng,
 *       {@code saveAndFlush} để lỗi constraint nổi lên ngay tại P1.</li>
 *   <li><b>P2 gateway</b> — gọi cổng thanh toán <b>ngoài transaction</b>, không giữ
 *       khóa ghi trong lúc chờ cổng phản hồi.</li>
 *   <li><b>P3 finalize</b> — ghi mã giao dịch và chuyển trạng thái trong
 *       <b>một</b> transaction riêng, để trạng thái và mã giao dịch luôn nhất quán.</li>
 * </ul>
 *
 * <p><b>Race: hai request đồng thời cùng idempotency key.</b> Cả hai cùng thấy
 * "chưa có" rồi cùng INSERT; một thắng, một bị DB từ chối bởi
 * {@code uq_payment_attempts_idempotency} và nhận
 * {@link DataIntegrityViolationException} <b>tại thời điểm flush</b>. Lỗi này được
 * bắt <b>ngoài ranh giới transaction</b> (quanh lời gọi
 * {@link TransactionTemplate#execute}), nên transaction con rollback sạch. Sau đó
 * mở <b>một transaction mới</b> chỉ để đọc lại attempt mà request song song vừa
 * tạo — <b>không</b> truy vấn lại trong transaction đã rollback-only, vì mọi
 * truy vấn bên trong đó đều không đáng tin cậy.
 *
 * <p>Chỉ constraint <b>đúng là</b> {@code uq_payment_attempts_idempotency} mới
 * được coi là race. Mọi lỗi DB khác (FK, kết nối, hạ tầng…) được xử lý như lỗi
 * hạ tầng, <b>không</b> biến thành 409 {@code IDEMPOTENCY_CONFLICT}.
 *
 * <p>Không dùng lock {@code PESSIMISTIC_WRITE}, không {@code SELECT ... FOR UPDATE},
 * không advisory lock: ràng buộc unique của DB đã là cơ chế phòng tranh chấp
 * chuẩn, chỉ cần bắt lỗi và đọc lại ở transaction mới.
 *
 * <h2>Phục hồi trạng thái khi finalize thất bại</h2>
 * <p>Nếu P3 thất bại sau khi gateway đã phản hồi, attempt vẫn ở
 * {@code PENDING} trong DB và <b>kết quả thanh toán không được xác định</b>.
 * <ul>
 *   <li>Không tuyên bố {@code SUCCEEDED} — response trả 503
 *       {@code PAYMENT_CONFIRMATION_PENDING}.</li>
 *   <li>Không gọi lại gateway cho attempt {@code PENDING}: không có bằng chứng
 *       rằng cổng chưa xử lý, nên gọi lại có thể thu tiền hai lần.</li>
 *   <li>Không suy luận "gateway đã SUCCESS" chỉ từ việc attempt có
 *       {@code providerTransactionId}: mã giao dịch không mang thông tin kết
 *       quả, nên schema V1 chưa lưu được outcome của gateway.</li>
 * </ul>
 * <p>Việc đối soát các attempt {@code PENDING} cần một cơ chế riêng (cột lưu
 * outcome hoặc job quét đối soát) — <b>ngoài phạm vi M4-04</b> vì không được
 * thêm migration. Xem {@code docs/payment/README.md}.
 *
 * <h2>Phạm vi</h2>
 * <p>Service này <b>không</b> cập nhật booking và <b>không</b> chuyển booking sang
 * {@code CONFIRMED}. Nguồn sự thật cho "đã thanh toán" ở M4-04 là
 * {@code payment_attempts.status = SUCCEEDED}.
 *
 * @author Nguyễn Đình Đạt
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Tên constraint unique của cặp {@code (booking_id, provider, idempotency_key)}. */
    static final String CONSTRAINT_IDEMPOTENCY = "uq_payment_attempts_idempotency";

    /** Tên partial unique index bảo đảm mỗi booking chỉ có một attempt SUCCEEDED. */
    static final String CONSTRAINT_ONE_SUCCESS_PER_BOOKING = "uq_payment_attempts_one_success_per_booking";

    private final PaymentAttemptRepository attempts;
    private final ObjectProvider<PaymentGatewayPort> gatewayProvider;
    private final ObjectProvider<BookingPaymentPort> bookingPortProvider;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(
            PaymentAttemptRepository attempts,
            ObjectProvider<PaymentGatewayPort> gatewayProvider,
            ObjectProvider<BookingPaymentPort> bookingPortProvider,
            PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.gatewayProvider = gatewayProvider;
        this.bookingPortProvider = bookingPortProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Tạo (hoặc tái sử dụng) một lần thử thanh toán demo.
     *
     * @param request        yêu cầu thanh toán
     * @param requesterUserId ID người gọi, lấy từ {@code sub} của JWT (server ký).
     *                      Bắt buộc để kiểm tra quyền sở hữu booking.
     * @return kết quả gồm attempt và cờ {@code replay} cho biết đây là attempt
     *         được tái sử dụng (idempotent) hay attempt mới
     */
    public PaymentOutcome pay(CreatePaymentRequest request, Long requesterUserId) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Thiếu dữ liệu yêu cầu");
        }
        Long bookingId = request.bookingId();
        String idempotencyKey = request.idempotencyKey() == null ? null : request.idempotencyKey().trim();
        BigDecimal amount = request.amount();

        // ---- Bước 1: idempotency, TRƯỚC mọi kiểm tra khác ----------------
        Optional<PaymentAttempt> existing = findByIdempotencyKey(bookingId, idempotencyKey);
        if (existing.isPresent()) {
            PaymentAttempt attempt = existing.get();
            // Kiểm tra quyền sở hữu TRƯỚC khi so sánh số tiền: nếu không, một
            // người khác đoán được idempotencyKey của người này sẽ phân biệt
            // được "key chưa tồn tại" (bước tiếp theo) với "key đã tồn tại" —
            // tức là rò rỉ sự tồn tại của dữ liệu của người khác.
            requireOwnedBooking(
                    requireBookingPort(),
                    bookingId,
                    requesterUserId,
                    "BOOKING_NOT_FOUND",
                    "Không tìm thấy booking");
            if (!sameAmount(attempt.getRequestedAmount(), amount)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_AMOUNT_CONFLICT",
                        "idempotencyKey đã dùng với số tiền khác; không thể dùng lại key cho số tiền mới");
            }
            // Trả lại attempt cũ, KHÔNG tạo attempt mới, KHÔNG gọi lại gateway.
            return new PaymentOutcome(attempt, true);
        }

        // ---- Bước 2: gateway có khả dụng không? ---------------------------
        PaymentGatewayPort gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_NOT_AVAILABLE",
                    "Cổng thanh toán hiện không khả dụng");
        }

        // ---- Bước 3: client có được phép chỉ định kết quả không? -----------
        if (request.simulatedOutcome() != null && !gateway.supportsOutcomeSimulation()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "SIMULATED_OUTCOME_NOT_ALLOWED",
                    "Cổng thanh toán hiện tại không cho phép client chỉ định kết quả thanh toán");
        }

        // ---- Bước 4: adapter booking có khả dụng không? --------------------
        BookingPaymentPort bookingPort = requireBookingPort();

        // ---- Bước 5: tra booking và kiểm tra chủ sở hữu -------------------
        // Fail closed: nếu không xác minh được chủ booking thì không tạo attempt.
        BookingPaymentInfo booking = requireOwnedBooking(
                bookingPort, bookingId, requesterUserId, "BOOKING_NOT_FOUND", "Không tìm thấy booking");

        // ---- Bước 6: đối chiếu số tiền ------------------------------------
        if (booking.payableAmount() == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_NOT_AVAILABLE",
                    "Không đọc được số tiền phải trả của booking");
        }
        if (!sameAmount(booking.payableAmount(), amount)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PAYMENT_AMOUNT_MISMATCH",
                    "Số tiền thanh toán không khớp số tiền phải trả của booking");
        }

        // ---- Bước 7: booking đã có attempt SUCCEEDED chưa? -----------------
        if (attempts.existsByBookingIdAndStatus(bookingId, PaymentStatus.SUCCEEDED)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "BOOKING_ALREADY_PAID",
                    "Booking đã có một lần thanh toán thành công");
        }

        // ---- P1 reserve attempt PENDING ------------------------------------
        PaymentAttempt reserved;
        try {
            reserved = transactionTemplate.execute(status -> attempts.saveAndFlush(
                    new PaymentAttempt(bookingId, PaymentProvider.DEMO, idempotencyKey, amount)));
        } catch (DataIntegrityViolationException conflict) {
            // CHỈ coi là race/idempotency conflict khi xác định đúng constraint
            // uq_payment_attempts_idempotency. Mọi lỗi DB khác (FK, kết nối,
            // hạ tầng...) là lỗi hạ tầng — KHÔNG biến thành 409 IDEMPOTENCY_CONFLICT.
            if (!isViolationOf(conflict, CONSTRAINT_IDEMPOTENCY)) {
                log.error("Lỗi DB khi tạo attempt thanh toán cho booking {} — không phải xung đột idempotency",
                        bookingId, conflict);
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "PAYMENT_NOT_AVAILABLE",
                        "Không tạo được lần thử thanh toán do lỗi hệ thống");
            }
            // Race: request song song vừa tạo attempt cùng idempotency key.
            // Bắt ở NGOÀI ranh giới transaction để transaction con rollback sạch.
            log.info("Idempotency conflict khi tạo attempt cho booking {} — đọc lại attempt đã có",
                    bookingId);
            return resolveConcurrentConflict(bookingId, idempotencyKey, amount, requesterUserId);
        }
        if (reserved == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_NOT_AVAILABLE",
                    "Không tạo được lần thử thanh toán");
        }

        // ---- P2 gọi gateway, NGOÀI transaction ------------------------------
        GatewayResult result = chargeGateway(gateway, request, bookingId, idempotencyKey, amount);

        // ---- P3 finalize trong transaction riêng ---------------------------
        return finalizeAttempt(reserved.getId(), result);
    }

    /**
     * Lấy adapter booking, hoặc báo 503 nếu chưa có.
     *
     * @return adapter {@link BookingPaymentPort}
     */
    private BookingPaymentPort requireBookingPort() {
        BookingPaymentPort port = bookingPortProvider.getIfAvailable();
        if (port == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_NOT_AVAILABLE",
                    "Dịch vụ tra cứu booking hiện không khả dụng");
        }
        return port;
    }

    /**
     * Tra booking và xác minh người gọi là chủ sở hữu.
     *
     * <p><b>Fail closed:</b> mọi trường hợp không xác minh được quyền sở hữu đều
     * ném lỗi — không bao giờ trả dữ liệu của booking cho người không phải chủ.
     *
     * @param port             adapter booking
     * @param bookingId        ID booking
     * @param requesterUserId  ID người gọi (từ JWT)
     * @param notFoundCode     mã lỗi khi không tìm thấy / không sở hữu
     * @param notFoundMessage  thông điệp lỗi
     * @return thông tin booking đã xác minh quyền sở hữu
     */
    private BookingPaymentInfo requireOwnedBooking(
            BookingPaymentPort port,
            Long bookingId,
            Long requesterUserId,
            String notFoundCode,
            String notFoundMessage) {
        if (requesterUserId == null) {
            // Không xác định được người gọi → fail closed.
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "Cần đăng nhập để thực hiện thao tác này");
        }
        BookingPaymentInfo booking = port.findPayableBooking(bookingId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, notFoundCode, notFoundMessage));
        Long ownerId = booking.customerId();
        if (ownerId == null) {
            // Không xác minh được chủ sở hữu → fail closed, KHÔNG trả dữ liệu.
            log.warn("Booking {} không có customerId — không xác minh được quyền sở hữu", bookingId);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_NOT_AVAILABLE",
                    "Không xác minh được chủ sở hữu booking");
        }
        if (!ownerId.equals(requesterUserId)) {
            // Dùng mã "không tìm thấy" để không tiết lộ booking của người khác.
            log.info("Từ chối truy cập booking {} — người gọi không phải chủ sở hữu", bookingId);
            throw new ApiException(HttpStatus.NOT_FOUND, notFoundCode, notFoundMessage);
        }
        return booking;
    }

    /**
     * Tra cứu attempt theo khóa idempotency trong transaction riêng.
     *
     * @param bookingId      ID booking
     * @param idempotencyKey khóa chống ghi lặp (đã trim)
     * @return attempt nếu tồn tại
     */
    private Optional<PaymentAttempt> findByIdempotencyKey(Long bookingId, String idempotencyKey) {
        if (bookingId == null || idempotencyKey == null || idempotencyKey.isEmpty()) {
            return Optional.empty();
        }
        return transactionTemplate.execute(status ->
                attempts.findByBookingIdAndProviderAndIdempotencyKey(
                        bookingId, PaymentProvider.DEMO, idempotencyKey));
    }

    /**
     * Xử lý xung đột idempotency do hai request chạy đồng thời.
     *
     * <p>Được gọi sau khi {@link DataIntegrityViolationException} đã được bắt
     * <b>ngoài</b> transaction. Việc đọc lại thực hiện trong <b>transaction mới</b>
     * vì transaction đã bị đánh dấu rollback-only không thể truy vấn lại.
     *
     * @param bookingId       ID booking
     * @param idempotencyKey  khóa chống ghi lặp
     * @param amount          số tiền của request hiện tại
     * @param requesterUserId ID người gọi (từ JWT) để kiểm tra quyền sở hữu
     * @return kết quả tái sử dụng attempt đã có
     */
    private PaymentOutcome resolveConcurrentConflict(
            Long bookingId, String idempotencyKey, BigDecimal amount, Long requesterUserId) {
        Optional<PaymentAttempt> concurrent = findByIdempotencyKey(bookingId, idempotencyKey);
        if (concurrent.isEmpty()) {
            // Attempt song song đã bị rollback sau khi insert; không còn dữ liệu
            // để tái sử dụng. Báo lỗi tạm thời để client thử lại với cùng key.
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Có yêu cầu thanh toán khác đang xử lý với idempotencyKey này, vui lòng thử lại");
        }
        PaymentAttempt attempt = concurrent.get();
        // Kiểm tra quyền sở hữu TRƯỚC khi so sánh số tiền (lý do như ở nhánh replay
        // trong pay()): không rò rỉ sự tồn tại của attempt của người khác.
        requireOwnedBooking(
                requireBookingPort(),
                bookingId,
                requesterUserId,
                "BOOKING_NOT_FOUND",
                "Không tìm thấy booking");
        if (!sameAmount(attempt.getRequestedAmount(), amount)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_AMOUNT_CONFLICT",
                    "idempotencyKey đã dùng với số tiền khác; không thể dùng lại key cho số tiền mới");
        }
        return new PaymentOutcome(attempt, true);
    }

    /**
     * Gọi cổng thanh toán ngoài transaction.
     *
     * @param gateway        cổng thanh toán
     * @param request        yêu cầu gốc
     * @param bookingId      ID booking
     * @param idempotencyKey khóa chống ghi lặp
     * @param amount         số tiền
     * @return kết quả từ cổng
     */
    private GatewayResult chargeGateway(
            PaymentGatewayPort gateway,
            CreatePaymentRequest request,
            Long bookingId,
            String idempotencyKey,
            BigDecimal amount) {
        try {
            GatewayResult result = gateway.charge(
                    new GatewayRequest(bookingId, amount, idempotencyKey, request.simulatedOutcome()));
            if (result == null || result.outcome() == null) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "PAYMENT_CONFIRMATION_PENDING",
                        "Cổng thanh toán không trả về kết quả hợp lệ; trạng thái thanh toán chưa được xác định");
            }
            return result;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Không biết cổng đã xử lý giao dịch hay chưa → không tuyên bố thành công,
            // không gọi lại gateway. Attempt giữ nguyên PENDING để đối soát sau.
            log.warn("Cổng thanh toán lỗi khi xử lý booking {}", bookingId, exception);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_CONFIRMATION_PENDING",
                    "Không xác định được kết quả thanh toán; trạng thái sẽ được đối soát sau");
        }
    }

    /**
     * Ghi mã giao dịch và chuyển trạng thái attempt trong <b>một</b> transaction.
     *
     * <p>Gộp hai thao tác để trạng thái và mã giao dịch luôn nhất quán: nếu
     * transaction rollback thì attempt vẫn {@code PENDING} hoàn toàn, không có
     * mã giao dịch "mồ côi" khiến người khác tưởng rằng cổng đã thành công.
     *
     * @param attemptId ID attempt cần finalize
     * @param result    kết quả từ cổng thanh toán
     * @return kết quả thanh toán
     */
    private PaymentOutcome finalizeAttempt(Long attemptId, GatewayResult result) {
        try {
            PaymentAttempt finalized = transactionTemplate.execute(status -> {
                PaymentAttempt attempt = attempts.findById(attemptId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "PAYMENT_ATTEMPT_NOT_FOUND",
                                "Không tìm thấy lần thử thanh toán"));
                if (attempt.getStatus() != PaymentStatus.PENDING) {
                    // Đã có luồng khác xử lý terminal state → giữ nguyên, không ném lỗi.
                    return attempt;
                }
                attempt.setProviderTransactionId(result.providerTransactionId());
                if (result.outcome() == Outcome.SUCCESS) {
                    attempt.markSucceeded(Instant.now());
                } else {
                    attempt.markFailed();
                }
                return attempts.saveAndFlush(attempt);
            });
            if (finalized == null) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "PAYMENT_CONFIRMATION_PENDING",
                        "Không lưu được trạng thái thanh toán; trạng thái sẽ được đối soát sau");
            }
            return new PaymentOutcome(finalized, false);
        } catch (DataIntegrityViolationException conflict) {
            // Phân biệt hai nguyên nhân rất khác nhau:
            //  - uq_payment_attempts_one_success_per_booking ⇒ booking đã có một
            //    attempt SUCCEEDED khác ⇒ lỗi nghiệp vụ 409.
            //  - bất kỳ constraint/lỗi DB nào khác ⇒ lỗi hạ tầng, trạng thái thanh
            //    toán KHÔNG xác định được ⇒ 503 PAYMENT_CONFIRMATION_PENDING.
            if (isViolationOf(conflict, CONSTRAINT_ONE_SUCCESS_PER_BOOKING)) {
                log.info("Không lưu được trạng thái SUCCEEDED cho attempt {} — booking đã có lần thanh toán thành công khác",
                        attemptId);
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "BOOKING_ALREADY_PAID",
                        "Booking đã có một lần thanh toán thành công");
            }
            log.warn("Không lưu được trạng thái thanh toán cho attempt {} — sẽ cần đối soát sau",
                    attemptId, conflict);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_CONFIRMATION_PENDING",
                    "Không lưu được trạng thái thanh toán; trạng thái sẽ được đối soát sau");
        } catch (IllegalStateException illegalState) {
            // markSucceeded/markFailed bị từ chối vì attempt đã ở trạng thái terminal.
            // Chỉ coi là replay khi đọc lại được trạng thái THỰC TẾ và trạng thái đó hợp lệ.
            PaymentAttempt actual = readActualState(attemptId);
            if (actual == null
                    || (actual.getStatus() != PaymentStatus.SUCCEEDED
                        && actual.getStatus() != PaymentStatus.FAILED)) {
                log.warn("Finalize attempt {} gặp trạng thái không hợp lệ", attemptId, illegalState);
                throw illegalState;
            }
            return new PaymentOutcome(actual, true);
        } catch (ApiException exception) {
            // Lỗi nghiệp vụ do chính service ném (ví dụ attempt không còn tồn tại).
            throw exception;
        } catch (RuntimeException failure) {
            // Lỗi hạ tầng/DB khi finalize: gateway ĐÃ có thể đã xử lý giao dịch nhưng
            // trạng thái chưa lưu được. Không tuyên bố SUCCEEDED, không gọi lại gateway.
            log.warn("Không lưu được trạng thái thanh toán cho attempt {} — sẽ cần đối soát sau",
                    attemptId, failure);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_CONFIRMATION_PENDING",
                    "Không lưu được trạng thái thanh toán; trạng thái sẽ được đối soát sau");
        }
    }

    /**
     * Kiểm tra một {@link DataIntegrityViolationException} có do constraint
     * (hoặc index) đã cho đó bị vi phạm hay không.
     *
     * <p>PostgreSQL báo lỗi unique bằng mã {@code 23505} kèm tên ràng buộc. Ta dò
     * tên constraint qua {@link org.hibernate.exception.ConstraintViolationException}
     * và qua message của exception (message của Hibernate chứa nguyên văn lỗi
     * PostgreSQL). Nếu không xác định được thì coi là <b>không</b> phải constraint
     * đó — an toàn hơn: không phân loại nhầm lỗi hạ tầng thành lỗi nghiệp vụ.
     *
     * @param exception      lỗi vi phạm ràng buộc
     * @param constraintName tên constraint cần đối chiếu
     * @return {@code true} nếu lỗi do constraint này
     */
    static boolean isViolationOf(DataIntegrityViolationException exception, String constraintName) {
        if (exception == null || constraintName == null) {
            return false;
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && constraintName.equals(constraint.getConstraintName())) {
                return true;
            }
        }
        String message = exception.getMessage();
        return message != null && message.contains(constraintName);
    }

    /**
     * Đọc lại trạng thái thực tế của attempt trong transaction mới.
     *
     * @param attemptId ID attempt
     * @return attempt hoặc {@code null} nếu không đọc được
     */
    private PaymentAttempt readActualState(Long attemptId) {
        PaymentAttempt actual = transactionTemplate.execute(
                status -> attempts.findById(attemptId).orElse(null));
        return actual;
    }

    /**
     * So sánh hai số tiền.
     *
     * <p>Dùng {@link BigDecimal#compareTo} chứ không dùng {@code equals}: cột
     * {@code NUMERIC(15,2)} lưu {@code 1000000.00} còn client có thể gửi
     * {@code 1000000} — hai giá trị này là <b>khớp</b>.
     *
     * @param left  giá trị thứ nhất
     * @param right giá trị thứ hai
     * @return {@code true} nếu bằng nhau về mặt giá trị
     */
    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return false;
        }
        return left.compareTo(right) == 0;
    }

    /**
     * Tra cứu một attempt theo ID, có kiểm tra quyền sở hữu.
     *
     * <p><b>Fail closed:</b> nếu không xác minh được chủ sở hữu booking (thiếu
     * adapter, booking không tồn tại, {@code customerId} null) thì trả
     * {@code null} — controller chuyển thành 404 — chứ không trả attempt.
     *
     * @param attemptId       ID attempt
     * @param requesterUserId ID người gọi (từ JWT); {@code null} ⇒ không xác minh được
     * @return attempt nếu tồn tại <b>và</b> người gọi là chủ sở hữu; {@code null} nếu không
     */
    public PaymentAttempt findAttempt(Long attemptId, Long requesterUserId) {
        if (attemptId == null) {
            return null;
        }
        PaymentAttempt attempt = transactionTemplate.execute(
                status -> attempts.findById(attemptId).orElse(null));
        if (attempt == null) {
            return null;
        }
        // Ném ApiException (404/503) nếu không xác minh được quyền sở hữu.
        requireOwnedBooking(
                requireBookingPort(),
                attempt.getBookingId(),
                requesterUserId,
                "PAYMENT_ATTEMPT_NOT_FOUND",
                "Không tìm thấy lần thử thanh toán");
        return attempt;
    }

    /**
     * Kết quả một lần gọi {@link #pay}.
     *
     * @param attempt attempt được tạo hoặc tái sử dụng
     * @param replay  {@code true} nếu đây là attempt đã tồn tại được tái sử dụng
     *               (idempotent), {@code false} nếu là attempt mới
     */
    public record PaymentOutcome(PaymentAttempt attempt, boolean replay) {}
}
