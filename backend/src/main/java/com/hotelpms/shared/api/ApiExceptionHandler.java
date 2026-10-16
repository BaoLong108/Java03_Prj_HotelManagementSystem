package com.hotelpms.shared.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> applicationError(ApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validationError(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", "Dữ liệu không hợp lệ", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedJson() {
        return ResponseEntity.badRequest().body(new ApiError("MALFORMED_JSON", "JSON không hợp lệ"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> dataConflict(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                if ("uq_app_users_email_lower".equals(constraint.getConstraintName())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new ApiError("EMAIL_ALREADY_EXISTS", "Email đã được đăng ký"));
                }
                if ("ex_bookings_room_overlap".equals(constraint.getConstraintName())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new ApiError("ROOM_NOT_AVAILABLE", "Phòng đã có người đặt trong khoảng thời gian đã chọn"));
                }
            }
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DATA_CONFLICT", "Dữ liệu xung đột với bản ghi hiện có"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "Không tìm thấy tài nguyên"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpectedError(Exception exception) {
        log.error("Unexpected API error", exception);
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_ERROR", "Có lỗi xử lý, vui lòng thử lại"));
    }
}
