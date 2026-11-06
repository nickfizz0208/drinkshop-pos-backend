package com.example.drinkshop.exception;
import org.springframework.http.HttpStatus;

// 業務流程主動拋出的錯誤：400 輸入錯誤、401 需重新登入、403 等級不足、404 找不到、409 狀態或資料衝突。
public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    public static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, message); }
    public static ApiException unauthorized(String message) { return new ApiException(HttpStatus.UNAUTHORIZED, message); }
    public static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, message); }
}
