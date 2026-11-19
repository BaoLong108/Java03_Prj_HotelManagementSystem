package com.hotelpms.support;

import static com.hotelpms.support.SupportTypes.*;

import com.hotelpms.shared.api.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class HotelFaqs {
    private static final Set<String> STOP = Set.of("toi", "co", "cho", "la", "may", "nhu", "the", "nao", "khong", "xin", "hoi", "va", "gi", "cua");
    private final JdbcTemplate jdbc;

    HotelFaqs(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<Faq> list(long before, String query) {
        return jdbc.query("""
            SELECT * FROM hotel_faqs WHERE id<? AND (strpos(lower(question),lower(?))>0
                OR strpos(lower(category),lower(?))>0) ORDER BY id DESC LIMIT 40
            """, HotelFaqs::map, before, query, query);
    }

    List<Faq> relevant(String text, int limit) {
        var terms = terms(text);
        return jdbc.query("SELECT * FROM hotel_faqs WHERE active ORDER BY updated_at DESC,id DESC LIMIT 200", HotelFaqs::map)
                .stream().sorted(Comparator.comparingLong((Faq faq) -> score(terms, faq)).reversed()).limit(limit).toList();
    }

    Faq matching(String text, List<Faq> choices) {
        var terms = terms(text);
        return choices.stream().filter(faq -> score(terms, faq) >= (terms.size() == 1 ? 1 : 2))
                .filter(faq -> score(terms, faq) * 2 >= terms.size()).findFirst().orElse(null);
    }

    Faq active(long id) {
        return jdbc.query("SELECT * FROM hotel_faqs WHERE id=? AND active", HotelFaqs::map, id).stream().findFirst().orElse(null);
    }

    Faq save(Long id, FaqInput input, long actor) {
        var values = new Object[]{input.category().strip(), input.question().strip(), input.answer().strip(), input.active(), actor};
        if (id == null) return jdbc.queryForObject("""
            INSERT INTO hotel_faqs(category,question,answer,active,updated_by) VALUES (?,?,?,?,?) RETURNING *
            """, HotelFaqs::map, values);
        return jdbc.query("""
            UPDATE hotel_faqs SET category=?,question=?,answer=?,active=?,updated_by=?,updated_at=now() WHERE id=? RETURNING *
            """, HotelFaqs::map, values[0], values[1], values[2], values[3], values[4], id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FAQ_NOT_FOUND", "Không tìm thấy mục FAQ."));
    }

    private static Faq map(ResultSet rs, int index) throws SQLException {
        return new Faq(rs.getLong("id"), rs.getString("category"), rs.getString("question"), rs.getString("answer"),
                rs.getBoolean("active"), rs.getTimestamp("updated_at").toInstant());
    }

    static String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd');
    }

    private static Set<String> terms(String value) {
        return Arrays.stream(normalize(value).split("[^a-z0-9]+"))
                .filter(term -> term.length() > 1 && !STOP.contains(term)).collect(Collectors.toSet());
    }

    private static long score(Set<String> query, Faq faq) {
        var words = terms(faq.question());
        return query.stream().filter(words::contains).count();
    }
}
