package com.east.app.mailread;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.east.app.auth.AuthFlowException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class MailReadAuthorizationHandlerTest {

    @Test
    void refreshTokenFailureRequestsMailboxReconnection() {
        MailReadExceptionHandler handler = new MailReadExceptionHandler();

        ResponseEntity<Map<String, String>> response = handler.handleMailboxAuthorization(
                new AuthFlowException(HttpStatus.UNAUTHORIZED, "synthetic token failure"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("MAILBOX_AUTH_REQUIRED", response.getBody().get("errorCode"));
    }
}
