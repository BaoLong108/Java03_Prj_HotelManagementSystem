package com.hotelpms.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelpms.payment.port.PaymentGatewayPort.GatewayRequest;
import com.hotelpms.payment.port.PaymentGatewayPort.GatewayResult;
import com.hotelpms.payment.port.PaymentGatewayPort.Outcome;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test cho {@link DemoPaymentGateway} — M4-04.
 *
 * <p>Trọng tâm: kết quả <b>tất định</b> (không random) và mã giao dịch <b>tất định</b>
 * theo {@code (bookingId, idempotencyKey)} — bắt buộc vì cột
 * {@code provider_transaction_id} có partial unique index
 * {@code uq_payment_attempts_provider_transaction}.
 *
 * @author Nguyễn Đình Đạt
 */
class DemoPaymentGatewayTest {

    private static final Long BOOKING_ID = 123L;
    private static final String KEY = "idem-demo-001";
    private static final BigDecimal AMOUNT = new BigDecimal("500000.00");

    private final DemoPaymentGateway gateway = new DemoPaymentGateway();

    private GatewayRequest request(Outcome outcome) {
        return new GatewayRequest(BOOKING_ID, AMOUNT, KEY, outcome);
    }

    @Test
    @DisplayName("Cổng DEMO hỗ trợ mô phỏng kết quả")
    void supportsOutcomeSimulation() {
        assertThat(gateway.supportsOutcomeSimulation()).isTrue();
    }

    @Test
    @DisplayName("Không chỉ định outcome → SUCCESS (mặc định)")
    void defaultsToSuccess() {
        GatewayResult result = gateway.charge(request(null));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.providerTransactionId()).isNotBlank();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    @DisplayName("SUCCESS → outcome SUCCESS")
    void explicitSuccessIsHonoured() {
        GatewayResult result = gateway.charge(request(Outcome.SUCCESS));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    @DisplayName("FAILURE → outcome FAILURE, vẫn trả mã giao dịch để đối chiếu")
    void explicitFailureIsHonoured() {
        GatewayResult result = gateway.charge(request(Outcome.FAILURE));

        assertThat(result.outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(result.providerTransactionId()).isNotBlank();
        assertThat(result.message()).contains("thất bại");
    }

    @Test
    @DisplayName("Mã giao dịch tất định: DEMO-{bookingId}-{idempotencyKey}")
    void transactionIdIsDeterministic() {
        GatewayResult first = gateway.charge(request(Outcome.SUCCESS));
        GatewayResult second = gateway.charge(request(Outcome.SUCCESS));

        assertThat(first.providerTransactionId()).isEqualTo("DEMO-123-idem-demo-001");
        assertThat(second.providerTransactionId()).isEqualTo(first.providerTransactionId());
    }

    @Test
    @DisplayName("Cùng đầu vào nhưng outcome khác → mã giao dịch VẪN giống nhau")
    void transactionIdIgnoresOutcome() {
        GatewayResult success = gateway.charge(request(Outcome.SUCCESS));
        GatewayResult failure = gateway.charge(request(Outcome.FAILURE));

        assertThat(success.providerTransactionId()).isEqualTo(failure.providerTransactionId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a",
            "0123456789012345678901234567890123456789012345678901234567890123456789",
            "01234567890123456789012345678901234567890123456789012345678901234567890123456789"
    })
    @DisplayName("Mã giao dịch luôn ≤ 100 ký tự (khớt cột VARCHAR(100))")
    void transactionIdNeverExceedsColumnLength(String key) {
        String transactionId = DemoPaymentGateway.transactionId(BOOKING_ID, key);

        assertThat(transactionId).hasSizeLessThanOrEqualTo(DemoPaymentGateway.MAX_TX_ID_LENGTH);
        assertThat(transactionId).startsWith(DemoPaymentGateway.TX_PREFIX);
    }

    @Test
    @DisplayName("Key quá dài: mã giao dịch vẫn TẤT ĐỊNH (cùng đầu vào ⇒ cùng mã)")
    void longKeyStillProducesDeterministicTransactionId() {
        String longKey = "k".repeat(200);

        String first = DemoPaymentGateway.transactionId(BOOKING_ID, longKey);
        String second = DemoPaymentGateway.transactionId(BOOKING_ID, longKey);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSizeLessThanOrEqualTo(DemoPaymentGateway.MAX_TX_ID_LENGTH);
    }

    @Test
    @DisplayName("Hai key dài khác nhau: mã giao dịch KHÔNG bị cắt thành trùng nhau")
    void differentLongKeysDoNotCollide() {
        String keyA = "a".repeat(150) + "-AAA";
        String keyB = "a".repeat(150) + "-BBB";

        String idA = DemoPaymentGateway.transactionId(BOOKING_ID, keyA);
        String idB = DemoPaymentGateway.transactionId(BOOKING_ID, keyB);

        assertThat(idA).isNotEqualTo(idB);
    }

    @Test
    @DisplayName("Mã giao dịch chỉ chứa ký tự an toàn cho cột VARCHAR (không khoảng trắng/ký tự đặc biệt)")
    void transactionIdUsesSafeCharacters() {
        String transactionId = DemoPaymentGateway.transactionId(BOOKING_ID, "idem-demo-001");

        assertThat(transactionId).startsWith(DemoPaymentGateway.TX_PREFIX);
        assertThat(transactionId).doesNotContain(" ", "\t", "\n", "\r", "'", "\"");
        assertThat(transactionId).matches("[A-Za-z0-9-]+");
    }
}
