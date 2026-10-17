package dev.hashmark.common.exception;

import dev.hashmark.common.metrics.AppMetrics;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** PostgreSQL SQLSTATE: lock_timeout aşıldı ya da NOWAIT kilidi alınamadı. */
    static final String LOCK_NOT_AVAILABLE = "55P03";

    private final AppMetrics metrics;

    public GlobalExceptionHandler(AppMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                ex.getHttpStatus().value(),
                ex.getHttpStatus().getReasonPhrase(),
                ex.getMessage(),
                ex.getCode()
        );
        return new ResponseEntity<>(errorResponse, ex.getHttpStatus());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String defaultMessage = "Validation error";
        if (ex.getBindingResult().hasErrors()) {
            defaultMessage = "Validation error: " + ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        }
        
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                defaultMessage
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Malformed or missing request parameters"
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Async havuz/kuyruk dolu ya da DB bağlantı havuzu tükendi (Hikari zaman aşımı): iç hata (500) yerine
     * istemciye hızlıca "sonra tekrar dene" döner. Tek satır uyarı; yük altında yığın izi log basmaz.
     */
    @ExceptionHandler({
            java.util.concurrent.RejectedExecutionException.class,
            org.springframework.core.task.TaskRejectedException.class,
            org.springframework.jdbc.CannotGetJdbcConnectionException.class,
            java.sql.SQLTransientConnectionException.class
    })
    public ResponseEntity<ErrorResponse> handleOverloaded(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class).warn("Sistem yoğun, istek reddedildi (503): {}", ex.getClass().getSimpleName());
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                "Server is busy, please try again shortly"
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(errorResponse);
    }

    /**
     * Sorgu statement_timeout'a takıldı (57014) ya da kilit lock_timeout içinde alınamadı (55P03, ör. migrasyon
     * sırasında). Bunlar geçici aşırı yük belirtisidir: 500 yerine 503 + Retry-After, ayrıca sayılır (alarm).
     * Sorgu metni/parametreleri loglanmaz (içerik sızmasın).
     */
    @ExceptionHandler({QueryTimeoutException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleDbTimeout(Exception ex) {
        return dbTimeout(ex instanceof QueryTimeoutException ? "statement" : "lock");
    }

    /**
     * Spring 7 PostgreSQL için 55P03'ü (lock_not_available: lock_timeout / NOWAIT) sınıflandırmıyor ve
     * {@link UncategorizedSQLException} olarak bırakıyor (gerçek PG ile test edildi). Bu kod 503'e çevrilir,
     * diğerleri genel 500 yolundan geçer.
     */
    @ExceptionHandler(UncategorizedSQLException.class)
    public ResponseEntity<ErrorResponse> handleUncategorizedSql(UncategorizedSQLException ex) {
        if (ex.getSQLException() != null && LOCK_NOT_AVAILABLE.equals(ex.getSQLException().getSQLState())) {
            return dbTimeout("lock");
        }
        return handleGenericException(ex);
    }

    private ResponseEntity<ErrorResponse> dbTimeout(String kind) {
        metrics.dbTimeout(kind);
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class).warn("DB zaman aşımı ({}), istek reddedildi (503)", kind);
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                "Server is busy, please try again shortly"
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(errorResponse);
    }

    /** Bilinmeyen yol/yöntem istemci hatasıdır; 500 sayılırsa 5xx uyarıları ve hata toplama kirlenir. */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                "Not found"
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(Exception ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                "Method not allowed"
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        // Ayrıntı yalnızca günlükte kalır; istemciye iç hata mesajı sızdırılmaz.
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class).error("Beklenmeyen hata", ex);
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "Internal server error"
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
