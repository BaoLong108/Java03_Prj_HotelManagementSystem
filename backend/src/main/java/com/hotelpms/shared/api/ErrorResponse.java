package com.hotelpms.shared.api;

import java.time.Instant;

/**
 * Body chuẩn trả về khi có lỗi.
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp
) {
    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, Instant.now());
    }
}
