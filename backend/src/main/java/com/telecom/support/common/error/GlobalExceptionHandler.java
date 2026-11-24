package com.telecom.support.common.error;

import com.telecom.support.common.error.ApiExceptions.ApiException;
import com.telecom.support.common.error.ProblemDetailFactory.FieldViolation;
import com.telecom.support.common.error.ProblemDetailFactory.Problem;
import com.telecom.support.common.security.AccessDeniedListener;
import com.telecom.support.common.web.RequestSizeLimitAdvice.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception raised in a controller call to a 00-overview §4 {@link ProblemDetail} built by
 * {@link ProblemDetailFactory}. Framework errors keep their status (405 keeps its {@code Allow} header) but get a
 * user-safe detail; any unmapped exception is a 500 {@code internal} whose stack trace is only logged (01 backend §6).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemDetailFactory problems;
    private final ObjectProvider<AccessDeniedListener> deniedAccess;

    public GlobalExceptionHandler(ProblemDetailFactory problems, ObjectProvider<AccessDeniedListener> deniedAccess) {
        this.problems = problems;
        this.deniedAccess = deniedAccess;
    }

    /** {@code @PreAuthorize} (method-level) denial: one sampled ACCESS_DENIED row, 403 {@code forbidden} (§4.4, §7). */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        deniedAccess.orderedStream().forEach(listener -> listener.onAccessDenied(request, ex));
        return respond(problems.create(Problem.FORBIDDEN, request.getRequestURI()), HttpHeaders.EMPTY);
    }

    /** {@link ApiExceptions} (409 conflict, 422 business-rule / idempotency-mismatch): the row's user-safe detail. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        log.debug("{} on {}: {}", ex.problem(), request.getRequestURI(), ex.getMessage());
        return respond(problems.create(ex.problem(), request.getRequestURI()), HttpHeaders.EMPTY);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ProblemDetail> handlePayloadTooLarge(PayloadTooLargeException ex, HttpServletRequest request) {
        return respond(problems.create(Problem.PAYLOAD_TOO_LARGE, request.getRequestURI()), HttpHeaders.EMPTY);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(problems.create(Problem.INTERNAL, request.getRequestURI()), HttpHeaders.EMPTY);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldViolation(fe.getField(), fe.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.add(new FieldViolation(ge.getObjectName(), ge.getDefaultMessage())));
        return respond(problems.validation(errors, path(request)), headers);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(@NonNull HandlerMethodValidationException ex,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(new FieldViolation(field, error.getDefaultMessage()));
            }
        });
        return respond(problems.validation(errors, path(request)), headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(@NonNull HttpMessageNotReadableException ex,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        Problem problem = hasCause(ex, PayloadTooLargeException.class) ? Problem.PAYLOAD_TOO_LARGE : Problem.BAD_REQUEST;
        return respond(problems.create(problem, path(request)), headers);
    }

    /** Every other framework exception handled by the base class (405, 415, 404, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(@NonNull Exception ex, Object body,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode statusCode, @NonNull WebRequest request) {
        Problem problem = Problem.forStatus(statusCode);
        if (problem == Problem.INTERNAL) {
            log.error("Framework error {} on {}", statusCode.value(), path(request), ex);
        }
        return respond(problems.create(problem, statusCode, path(request)), headers);
    }

    private static <T> ResponseEntity<T> respond(ProblemDetail pd, HttpHeaders headers) {
        @SuppressWarnings("unchecked")
        T body = (T) pd;
        return ResponseEntity.status(pd.getStatus()).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
