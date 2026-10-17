package dev.hashmark.common.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus httpStatus;
    /** İstemcinin dallanabileceği makine-okur kod (ör. CONSENT_REQUIRED); çoğu hatada null. */
    private final String code;
    /** 429/503 için istemciye önerilen bekleme (sn); null ise Retry-After başlığı yazılmaz. */
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus httpStatus, String message) {
        this(httpStatus, null, message);
    }

    public ApiException(HttpStatus httpStatus, String code, String message) {
        this(httpStatus, code, message, null);
    }

    public ApiException(HttpStatus httpStatus, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Geçici durum: tekrar denemek anlamlı (aşırı yük, kota penceresi, dış servis kesintisi). */
    public boolean isTransient() {
        return httpStatus == HttpStatus.SERVICE_UNAVAILABLE || httpStatus == HttpStatus.TOO_MANY_REQUESTS;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static ApiException tooManyRequests(String code, String message, long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message, retryAfterSeconds);
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static ApiException serviceUnavailable(String code, String message, long retryAfterSeconds) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, retryAfterSeconds);
    }

    public static ApiException badGateway(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, message);
    }
}
