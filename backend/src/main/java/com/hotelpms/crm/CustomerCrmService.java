package com.hotelpms.crm;

import static com.hotelpms.booking.service.BookingService.HOTEL_ZONE;

import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.crm.CustomerCrm.*;
import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.loyalty.MembershipAccountService;
import com.hotelpms.payment.dto.CustomerTransaction;
import com.hotelpms.payment.service.CustomerTransactionService;
import com.hotelpms.shared.api.ApiException;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class CustomerCrmService {
    private final CustomerCrmRepository reads;
    private final MembershipAccountService memberships;
    private final CustomerTransactionService transactions;

    CustomerCrmService(CustomerCrmRepository reads, MembershipAccountService memberships, CustomerTransactionService transactions) {
        this.reads = reads;
        this.memberships = memberships;
        this.transactions = transactions;
    }

    public Directory directory(String search, LoyaltyTier tier, Boolean active, Sort sort, int page, int size) {
        validatePage(page, size);
        String term = search.strip().toLowerCase(Locale.ROOT);
        if (term.length() > 120) throw badRequest();
        return new Directory(reads.statistics(), reads.customers(term, tier, active, sort, page, size));
    }

    public Profile profile(long id) {
        var customer = customer(id);
        var totals = reads.totals(id);
        return new Profile(customer, memberships.summary(id), totals.completed(), totals.verified(), totals.earned(), totals.redeemed());
    }

    public Page<Booking> bookings(long id, BookingStatus status, LocalDate from, LocalDate to, int page, int size) {
        validatePage(page, size);
        if (invalidDate(from) || invalidDate(to) || (from != null && to != null && from.isAfter(to))) throw badRequest();
        customer(id);
        return reads.bookings(id, status, from == null ? null : from.atStartOfDay(HOTEL_ZONE).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(HOTEL_ZONE).toInstant(), page, size);
    }

    public CustomerTransaction.Page transactions(long id, int page, int size) {
        customer(id);
        return transactions.list(id, null, null, null, null, "", CustomerTransaction.Sort.NEWEST, page, size);
    }

    public CustomerTransaction.Detail transaction(long id, long attempt) {
        customer(id);
        return transactions.get(id, attempt);
    }

    public Page<Point> points(long id, int page, int size) {
        validatePage(page, size);
        customer(id);
        return reads.points(id, page, size);
    }

    public Page<Voucher> vouchers(long id, int page, int size) {
        validatePage(page, size);
        customer(id);
        return reads.vouchers(id, page, size);
    }

    public Page<CheckIn> checkIns(long id, int page, int size) {
        validatePage(page, size);
        customer(id);
        return reads.checkIns(id, page, size);
    }

    private Customer customer(long id) {
        return reads.customer(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Hồ sơ khách hàng không tồn tại"));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw badRequest();
    }

    private static boolean invalidDate(LocalDate date) {
        return date != null && (date.getYear() < 1 || date.getYear() > 9999);
    }

    private static ApiException badRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Bộ lọc hoặc phân trang không hợp lệ");
    }
}
