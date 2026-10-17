package dev.hashmark.common.exception;

import dev.hashmark.common.metrics.AppMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new AppMetrics(registry));

    @Test
    void statementTimeoutBecomes503AndIsCounted() {
        ResponseEntity<ErrorResponse> response = handler.handleDbTimeout(new QueryTimeoutException("canceling statement"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        assertEquals(1.0, registry.get("hashmark.db.timeout").tag("kind", "statement").counter().count());
    }

    @Test
    void uncategorizedLockNotAvailableBecomes503() {
        var ex = new org.springframework.jdbc.UncategorizedSQLException("q", "SELECT 1",
                new java.sql.SQLException("canceling statement due to lock timeout", "55P03"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, handler.handleUncategorizedSql(ex).getStatusCode());
        assertEquals(1.0, registry.get("hashmark.db.timeout").tag("kind", "lock").counter().count());
    }

    @Test
    void otherUncategorizedSqlStays500() {
        var ex = new org.springframework.jdbc.UncategorizedSQLException("q", "SELECT 1",
                new java.sql.SQLException("boom", "XX000"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, handler.handleUncategorizedSql(ex).getStatusCode());
    }

    @Test
    void lockTimeoutBecomes503AndIsCounted() {
        ResponseEntity<ErrorResponse> response = handler.handleDbTimeout(new CannotAcquireLockException("lock timeout"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(1.0, registry.get("hashmark.db.timeout").tag("kind", "lock").counter().count());
    }

    @Test
    void rejectedExecutionBecomes503WithRetryAfter() {
        ResponseEntity<ErrorResponse> response = handler.handleOverloaded(new RejectedExecutionException("queue full"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void exhaustedDbPoolBecomes503() {
        var ex = new org.springframework.jdbc.CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection", new java.sql.SQLTransientConnectionException("timeout"));
        ResponseEntity<ErrorResponse> response = handler.handleOverloaded(ex);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void taskRejectedBecomes503() {
        ResponseEntity<ErrorResponse> response = handler.handleOverloaded(new TaskRejectedException("full"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void unknownPathIs404NotServerError() {
        var ex = new org.springframework.web.servlet.resource.NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/actuator/health", "No static resource");
        assertEquals(HttpStatus.NOT_FOUND, handler.handleNotFound(ex).getStatusCode());
    }

    @Test
    void wrongMethodIs405() {
        var ex = new org.springframework.web.HttpRequestMethodNotSupportedException("PUT");
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, handler.handleMethodNotAllowed(ex).getStatusCode());
    }
}
