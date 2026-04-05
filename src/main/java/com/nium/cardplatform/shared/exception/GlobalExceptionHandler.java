package com.nium.cardplatform.shared.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Value("${app.error.base-uri}")
    private String baseUri;

    @ExceptionHandler(CardPlatformException.class)
    public ProblemDetail handleCardPlatformException(CardPlatformException e) {
        return problem(e.getStatus(), e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException e) {
        if ("Idempotency-Key".equals(e.getHeaderName())) {
            return problem(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required for this operation");
        }
        return problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                "Required header missing: " + e.getHeaderName());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (a, b) -> a));

        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed");
        pd.setProperty("fieldErrors", fieldErrors);
        return pd;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = String.format(
                "Invalid value '%s' for parameter '%s' — expected %s",
                ex.getValue(),
                ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "valid value"
        );
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableMessage(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY",
                "Request body is missing or contains invalid JSON");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                e.getMethod() + " is not supported for this endpoint");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "No endpoint found for " + e.getHttpMethod() + " " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unexpected exception: {}", ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "An unexpected error occurred");
    }

    private ProblemDetail problem(HttpStatus status, String errorCode, String message) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setType(URI.create(baseUri + errorCode.toLowerCase().replace('_', '-')));
        pd.setTitle(toTitle(errorCode));
        pd.setProperty("errorCode", errorCode);
        return pd;
    }

    private String toTitle(String errorCode) {
        String s = errorCode.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
