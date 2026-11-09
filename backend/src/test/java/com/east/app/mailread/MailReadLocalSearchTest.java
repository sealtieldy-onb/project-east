package com.east.app.mailread;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MailReadLocalSearchTest {

    @Test
    void onlyPlainTextTermsUseTheCachedMetadataSearch() {
        assertTrue(MailReadService.isCacheSearchableQuery("project update"));
        assertTrue(MailReadService.isCacheSearchableQuery("sender.name@example.invalid"));

        assertFalse(MailReadService.isCacheSearchableQuery("from:sender@example.invalid"));
        assertFalse(MailReadService.isCacheSearchableQuery("\"quoted phrase\""));
        assertFalse(MailReadService.isCacheSearchableQuery("foo_bar"));
        assertFalse(MailReadService.isCacheSearchableQuery("foo%bar"));
        assertFalse(MailReadService.isCacheSearchableQuery("foo\\bar"));
    }
}
