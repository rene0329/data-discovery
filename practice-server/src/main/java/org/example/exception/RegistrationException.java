package org.example.exception;

import org.springframework.http.HttpStatus;

public class RegistrationException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    public RegistrationException(HttpStatus status, String message) {
        this(status, defaultCode(status), message);
    }

    public RegistrationException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() { return errorCode; }

    public static RegistrationException notFound(String message) {
        return new RegistrationException(HttpStatus.NOT_FOUND, message);
    }

    public static RegistrationException notFound(String errorCode, String message) {
        return new RegistrationException(HttpStatus.NOT_FOUND, errorCode, message);
    }

    public static RegistrationException conflict(String message) {
        return new RegistrationException(HttpStatus.CONFLICT, message);
    }

    public static RegistrationException conflict(String errorCode, String message) {
        return new RegistrationException(HttpStatus.CONFLICT, errorCode, message);
    }

    public static RegistrationException invalid(String message) {
        return new RegistrationException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    public static RegistrationException invalid(String errorCode, String message) {
        return new RegistrationException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }

    private static String defaultCode(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) return "RESOURCE_NOT_FOUND";
        if (status == HttpStatus.CONFLICT) return "RESOURCE_CONFLICT";
        if (status == HttpStatus.UNPROCESSABLE_ENTITY) return "INVALID_ARGUMENT";
        return "REQUEST_FAILED";
    }
}
