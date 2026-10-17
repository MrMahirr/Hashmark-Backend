package dev.hashmark.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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
