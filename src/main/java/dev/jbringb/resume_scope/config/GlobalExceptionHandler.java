package dev.jbringb.resume_scope.config;

import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jooq.exception.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Renders every error as RFC 7807 {@code application/problem+json}, stamping a stable {@code type}.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} because in WebFlux the framework exceptions
 * (validation, {@link ResponseStatusException}, …) are turned into {@code ProblemDetail} by the
 * built-in handler and bypass a plain {@code @RestControllerAdvice}; subclassing this is the
 * supported way to customize them. We override only validation (to add field-level {@code errors});
 * the rest keep Spring's defaults (status + reason). The two {@code @ExceptionHandler} methods below
 * cover what the base class does <em>not</em>: a jOOQ {@link DataAccessException} and any other
 * unexpected exception, which would otherwise leak as a non-problem 500 body.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String TYPE_BASE = "https://resume-scope.dev/errors/";

    /** Bean-validation failure on a {@code @Valid @RequestBody} — surface which fields failed and why. */
    @Override
    protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(
            WebExchangeBindException ex, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        ProblemDetail body = ex.getBody();
        body.setTitle("Validation failed");
        body.setType(URI.create(TYPE_BASE + "validation"));
        List<FieldViolation> errors = ex.getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, exchange);
    }

    /** A query/SQL failure — never expose the SQL or driver message; log it, return a generic 500. */
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail handleDataAccess(DataAccessException ex) {
        log.error("Database access error", ex);
        return internalError("database");
    }

    /** Anything the base class and the handlers above don't cover — generic 500. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return internalError("internal");
    }

    private ProblemDetail internalError(String slug) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again later.");
        problem.setTitle("Internal server error");
        problem.setType(URI.create(TYPE_BASE + slug));
        return problem;
    }

    /** One field's validation message, embedded in the problem's {@code errors} array. */
    public record FieldViolation(String field, String message) {}
}
