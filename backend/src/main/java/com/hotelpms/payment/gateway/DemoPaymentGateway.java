package com.hotelpms.payment.gateway;

import com.hotelpms.payment.port.PaymentGatewayPort;
import java.nio.charset.StandardCharsets;

/**
 * Cổng thanh toán giả lập (DEMO) cho môi trường dev/test.
 *
 * <p><b>Không</b> gọi ra ngoài, <b>không</b> ghi bảng khác, <b>không</b> có hiệu ứng
 * phụ. Chỉ dùng để chứng minh luồng thanh toán end-to-end mà không cần cổng thật.
 *
 * <p><b>Tính tất định:</b> kết quả không random, không băm từ khóa — client chỉ định
 * {@code simulatedOutcome} (mặc định {@code SUCCESS}). Mã giao dịch được sinh
 * <b>tất định</b> theo {@code (bookingId, idempotencyKey)}: bắt buộc, vì cột
 * {@code provider_transaction_id} có partial unique index
 * {@code uq_payment_attempts_provider_transaction} — nếu sinh ngẫu nhiên thì mọi
 * lần gọi lại gateway sẽ vi phạm ràng buộc DB.
 *
 * @author Nguyễn Đình Đạt
 */
public class DemoPaymentGateway implements PaymentGatewayPort {

    /** Tiền tố mã giao dịch nội bộ của cổng DEMO. */
    static final String TX_PREFIX = "DEMO-";

    /** Độ dài tối đa của cột {@code provider_transaction_id} trong schema V1. */
    static final int MAX_TX_ID_LENGTH = 100;

    @Override
    public boolean supportsOutcomeSimulation() {
        return true;
    }

    @Override
    public GatewayResult charge(GatewayRequest request) {
        Outcome outcome = request.simulatedOutcome() == null
                ? Outcome.SUCCESS
                : request.simulatedOutcome();
        String transactionId = transactionId(request.bookingId(), request.idempotencyKey());
        String message = outcome == Outcome.SUCCESS
                ? "Thanh toán demo thành công"
                : "Thanh toán demo thất bại theo yêu cầu mô phỏng";
        return new GatewayResult(outcome, transactionId, message);
    }

    /**
     * Sinh mã giao dịch tất định: {@code DEMO-{bookingId}-{idempotencyKey}}.
     *
     * <p>Nếu chuỗi thô vượt quá độ dài cột {@code VARCHAR(100)}, phần
     * {@code idempotencyKey} được thay bằng <b>hash tất định</b> (SHA-512, hex)
     * thay vì cắt bớt. Nếu cắt bớt, hai key khác nhau có thể cho ra cùng một
     * mã giao dịch → vi phạm partial unique index
     * {@code uq_payment_attempts_provider_transaction}. Hash giữ tính tất định
     * (cùng đầu vào ⇒ cùng mã) và gần như không va chạm.
     *
     * @param bookingId      ID booking
     * @param idempotencyKey khóa chống ghi lặp
     * @return mã giao dịch không vượt quá {@value #MAX_TX_ID_LENGTH} ký tự
     */
    static String transactionId(Long bookingId, String idempotencyKey) {
        String raw = TX_PREFIX + bookingId + "-" + idempotencyKey;
        if (raw.length() <= MAX_TX_ID_LENGTH) {
            return raw;
        }
        String digest = sha512Hex((bookingId + "|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        String prefix = TX_PREFIX + bookingId + "-";
        int room = MAX_TX_ID_LENGTH - prefix.length();
        return prefix + digest.substring(0, Math.min(room, digest.length()));
    }

    /**
     * Tính SHA-512 và trả về chuỗi hex.
     *
     * @param bytes dữ liệu cần băm
     * @return chuỗi hex (128 ký tự)
     */
    private static String sha512Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-512").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            // SHA-512 là thuật toán bắt buộc của mọi JRE — không xảy ra.
            throw new IllegalStateException("Không tìm thấy thuật toán SHA-512", exception);
        }
    }
}
