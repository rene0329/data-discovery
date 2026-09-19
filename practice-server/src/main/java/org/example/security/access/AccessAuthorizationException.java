package org.example.security.access;

import org.springframework.http.HttpStatus;

public class AccessAuthorizationException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    public AccessAuthorizationException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
}
