package org.example.exception;

import org.springframework.http.HttpStatus;

public class RegistrationException extends RuntimeException {
    private final HttpStatus status;

    public RegistrationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static RegistrationException notFound(String message) {
        return new RegistrationException(HttpStatus.NOT_FOUND, message);
    }

    public static RegistrationException conflict(String message) {
        return new RegistrationException(HttpStatus.CONFLICT, message);
    }

    public static RegistrationException invalid(String message) {
        return new RegistrationException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
