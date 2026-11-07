package com.hotelpms.loyalty;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;
@Component
public class VoucherCodeGenerator {
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private final SecureRandom random = new SecureRandom();
    public String generate() {
        while (true) {
            var code = new StringBuilder(6);
            for (int index = 0; index < 6; index++) code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            String value = code.toString();
            if (value.matches(".*[A-Z].*") && value.matches(".*[0-9].*")) return value;
        }
    }
}
