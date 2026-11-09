package com.east.app.mailread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class MailHtmlSanitizerTest {

    @Test
    void matchingCidImagesUseOnlyTheLocalAttachmentEndpoint() {
        String sanitized = MailHtmlSanitizer.sanitize(
                "<img src='cid:logo%40example.test' alt='Logo' onerror='run()'>",
                Map.of("logo@example.test", "/api/v1/threads/thread/messages/message/attachments/image/inline"));

        Document document = Jsoup.parseBodyFragment(sanitized);
        var image = document.selectFirst("img");
        assertEquals("/api/v1/threads/thread/messages/message/attachments/image/inline", image.attr("src"));
        assertEquals("Logo", image.attr("alt"));
        assertFalse(image.hasAttr("onerror"));
        assertFalse(image.hasAttr("data-east-external-src"));
    }

    @Test
    void remoteImagesRemainInertAndBodySuppliedControlAttributesAreRemoved() {
        String sanitized = MailHtmlSanitizer.sanitize("""
                <img src="https://cdn.example.com/logo.png" alt="Remote" onerror="run()">
                <img src="http://127.0.0.1/private.png" alt="Loopback">
                <img src="http://2130706433/private.png" alt="Alternate loopback">
                <img src="http://192.168.1.4/private.png" alt="Private">
                <img src="http://[::1]/private.png" alt="IPv6 loopback">
                <img src="http://[fc00::1]/private.png" alt="IPv6 private">
                <img src="http://[fec0::1]/private.png" alt="IPv6 site local">
                <img src="http://printer.local/private.png" alt="Local name">
                <img data-east-external-src="https://attacker.example.test/track.png" alt="Forged">
                <script>run()</script>
                """);

        Document document = Jsoup.parseBodyFragment(sanitized);
        var remote = document.selectFirst("img[alt=Remote]");
        assertFalse(remote.hasAttr("src"));
        assertEquals("https://cdn.example.com/logo.png", remote.attr("data-east-external-src"));
        assertFalse(remote.hasAttr("onerror"));
        assertEquals(0, document.select("img[src^=http], img[src^=//]").size(), sanitized);
        for (var localImage : document.select(
                "img[alt=Loopback], img[alt='Alternate loopback'], img[alt=Private], "
                        + "img[alt='IPv6 loopback'], img[alt='IPv6 private'], "
                        + "img[alt='IPv6 site local'], img[alt='Local name']")) {
            assertFalse(localImage.hasAttr("src"));
            assertFalse(localImage.hasAttr("data-east-external-src"));
        }
        assertFalse(document.selectFirst("img[alt=Forged]").hasAttr("data-east-external-src"));
        assertEquals(0, document.select("script").size());
    }

    @Test
    void cidReferencesWithoutAnOwnedRasterMappingDoNotBecomeBrowserUrls() {
        String sanitized = MailHtmlSanitizer.sanitize(
                "<img src='cid:unknown@example.test' onload='run()'>");

        Document document = Jsoup.parseBodyFragment(sanitized);
        var image = document.selectFirst("img");
        assertFalse(image.hasAttr("src"));
        assertFalse(image.hasAttr("onload"));
        assertTrue(document.body().html().contains("<img"));
    }
}
