package org.example.handler;

import org.example.exception.RegistrationException;
import org.example.vo.ApiV1Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "org.example.controller.registration")
@Slf4j
public class RegistrationExceptionHandler {

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ApiV1Response<Object>> handleRegistrationException(RegistrationException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiV1Response.error(ex.getStatus().value(), ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiV1Response<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiV1Response.error(400, "INVALID_ARGUMENT", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiV1Response<Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiV1Response.error(400, "INVALID_JSON", "invalid JSON request body"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiV1Response<Object>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(415)
                .body(ApiV1Response.error(415, "UNSUPPORTED_MEDIA_TYPE",
                        "Content-Type must be application/json, or multipart/form-data for dataset upload"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiV1Response<Object>> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405)
                .body(ApiV1Response.error(405, "METHOD_NOT_ALLOWED",
                        "HTTP method is not supported for this endpoint"));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<ApiV1Response<Object>> handleRequestParameter(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ApiV1Response.error(400, "INVALID_ARGUMENT", "invalid request parameter"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiV1Response<Object>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(413)
                .body(ApiV1Response.error(413, "UPLOAD_TOO_LARGE",
                        "dataset file exceeds the 4096 MB upload limit"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiV1Response<Object>> handleInvalidMultipart(MultipartException ex) {
        return ResponseEntity.badRequest()
                .body(ApiV1Response.error(400, "INVALID_MULTIPART", "invalid multipart request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiV1Response<Object>> handleUnexpected(Exception ex) {
        log.error("Unexpected registration API error", ex);
        return ResponseEntity.status(500)
                .body(ApiV1Response.error(500, "INTERNAL_ERROR", "internal server error"));
    }
}
