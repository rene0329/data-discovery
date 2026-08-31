package org.example.handler;

import org.example.vo.ApiV1Response;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegistrationExceptionHandlerTest {
    private final RegistrationExceptionHandler handler = new RegistrationExceptionHandler();

    @Test
    void mapsOversizedUploadToStandard413Response() {
        ResponseEntity<ApiV1Response<Object>> response =
                handler.handleUploadTooLarge(new MaxUploadSizeExceededException(1024));

        assertEquals(413, response.getStatusCodeValue());
        assertEquals(413, response.getBody().getCode());
        assertEquals("UPLOAD_TOO_LARGE", response.getBody().getErrorCode());
    }

    @Test
    void mapsMissingMultipartPartToStandard400Response() {
        ResponseEntity<ApiV1Response<Object>> response =
                handler.handleRequestParameter(new MissingServletRequestPartException("file"));

        assertEquals(400, response.getStatusCodeValue());
        assertEquals(400, response.getBody().getCode());
        assertEquals("INVALID_ARGUMENT", response.getBody().getErrorCode());
    }
}
