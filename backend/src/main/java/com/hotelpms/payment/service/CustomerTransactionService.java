package com.hotelpms.payment.service;

import static com.hotelpms.booking.service.BookingService.HOTEL_ZONE;

import com.hotelpms.payment.dto.CustomerTransaction;
import com.hotelpms.payment.dto.CustomerTransaction.Sort;
import com.hotelpms.payment.dto.CustomerTransaction.Status;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.repository.CustomerTransactionRepository;
import com.hotelpms.payment.repository.CustomerTransactionRepository.Filter;
import com.hotelpms.shared.api.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerTransactionService {
    private final CustomerTransactionRepository transactions;

    public CustomerTransactionService(CustomerTransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CustomerTransaction.Page list(long customerId, Status status, PaymentProvider provider, LocalDate from,
            LocalDate to, String search, Sort sort, int page, int size) {
        String term = search.strip().toLowerCase(Locale.ROOT);
        if (page < 0 || size < 1 || size > 50 || term.length() > 100 || invalidDate(from) || invalidDate(to)
                || (from != null && to != null && from.isAfter(to))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Bộ lọc giao dịch không hợp lệ");
        }
        var filter = new Filter(status, provider, startOfDay(from), to == null ? null : startOfDay(to.plusDays(1)), term, sort);
        return transactions.findPage(customerId, filter, page, size, Instant.now());
    }

    @Transactional(readOnly = true)
    public CustomerTransaction.Detail get(long customerId, long id) {
        return transactions.findOwned(customerId, id, Instant.now()).orElseThrow(CustomerTransactionService::notFound);
    }

    @Transactional(readOnly = true)
    public void checkReceipt(long customerId, long id, String bookingCode) {
        var transaction = get(customerId, id).transaction();
        if (!transaction.bookingCode().equals(bookingCode)) throw notFound();
        if (!transaction.receiptAvailable()) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_REQUIRED", "Lượt giao dịch này chưa có PDF xác nhận");
        }
    }

    private static boolean invalidDate(LocalDate date) {
        return date != null && (date.getYear() < 1 || date.getYear() > 9999);
    }

    private static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(HOTEL_ZONE).toInstant();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Không tìm thấy giao dịch");
    }
}
