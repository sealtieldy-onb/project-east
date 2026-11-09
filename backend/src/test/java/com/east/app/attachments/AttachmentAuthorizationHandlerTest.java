package com.east.app.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.east.app.auth.AuthFlowException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AttachmentAuthorizationHandlerTest {

    @Test
    void refreshTokenFailureRequestsMailboxReconnection() {
        AttachmentContentExceptionHandler handler = new AttachmentContentExceptionHandler();

        ResponseEntity<Map<String, String>> response = handler.handleMailboxAuthorization(
                new AuthFlowException(HttpStatus.UNAUTHORIZED, "synthetic token failure"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("MAILBOX_AUTH_REQUIRED", response.getBody().get("errorCode"));
    }
}
