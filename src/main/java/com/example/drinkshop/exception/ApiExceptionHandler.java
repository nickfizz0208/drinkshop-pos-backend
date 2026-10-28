package com.example.drinkshop.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "message", message));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException ex) { return error(ex.status.value(), ex.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception ex) { return error(400, "請檢查必填欄位、選項值及數量格式"); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> oversized(Exception ex) { return error(413, "照片不可超過 5 MB"); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missing(Exception ex) { return error(404, "找不到此 API"); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception ex) { return error(405, "此 API 不支援這個 HTTP 方法"); }
    @ExceptionHandler({DataIntegrityViolationException.class, TransientDataAccessException.class})
    ResponseEntity<?> database(Exception ex) {
        log.warn("Database constraint/concurrency conflict: {}", ex.getClass().getSimpleName());
        return error(409, "資料已變更或不符合限制，請重新查詢後再試");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex) {
        log.error("Unexpected POS request failure", ex);
        return error(500, "系統暫時無法處理，請稍後重試");
    }
}
