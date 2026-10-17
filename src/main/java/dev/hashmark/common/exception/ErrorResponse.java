package dev.hashmark.common.exception;

import java.time.LocalDateTime;

public class ErrorResponse {
    private final LocalDateTime timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String code;

    public ErrorResponse(LocalDateTime timestamp, int status, String error, String message) {
        this(timestamp, status, error, message, null);
    }

    public ErrorResponse(LocalDateTime timestamp, int status, String error, String message, String code) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.message = message;
        this.code = code;
    }

    /** Makine-okur hata kodu (ör. CONSENT_REQUIRED); yoksa null. */
    public String getCode() {
        return code;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }
}
