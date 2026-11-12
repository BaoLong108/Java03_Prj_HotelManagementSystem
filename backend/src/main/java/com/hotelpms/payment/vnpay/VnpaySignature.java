package com.hotelpms.payment.vnpay;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class VnpaySignature {
    private VnpaySignature() {}

    static String query(Map<String, String> fields) {
        return new TreeMap<>(fields).entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty()
                        && entry.getKey().startsWith("vnp_") && !entry.getKey().equals("vnp_SecureHash")
                        && !entry.getKey().equals("vnp_SecureHashType"))
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    static String sign(Map<String, String> fields, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(query(fields).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException(error); }
    }

    static boolean valid(Map<String, String> fields, String secret) {
        var signature = fields.get("vnp_SecureHash");
        if (signature == null || !signature.matches("[a-fA-F0-9]{128}")) return false;
        return MessageDigest.isEqual(HexFormat.of().parseHex(signature), HexFormat.of().parseHex(sign(fields, secret)));
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
