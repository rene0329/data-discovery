package org.example.auth;

import org.example.vo.ApiV1Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "org.example.auth")
public class AuthExceptionHandler {
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiV1Response<Object>> auth(AuthException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiV1Response.error(ex.getStatus().value(), ex.getErrorCode(), ex.getMessage()));
    }
}
