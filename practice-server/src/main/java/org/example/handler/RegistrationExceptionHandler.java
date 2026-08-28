package org.example.handler;

import org.example.exception.RegistrationException;
import org.example.vo.ApiV1Response;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "org.example.controller.registration")
public class RegistrationExceptionHandler {

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ApiV1Response<Object>> handleRegistrationException(RegistrationException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiV1Response.error(ex.getStatus().value(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiV1Response<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiV1Response.error(400, ex.getMessage()));
    }
}
