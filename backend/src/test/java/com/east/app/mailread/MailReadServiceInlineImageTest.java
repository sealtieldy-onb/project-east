package com.east.app.mailread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.mailread.MailReadRepository.AttachmentRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MailReadServiceInlineImageTest {

    private static final UUID THREAD_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");

    @Test
    void duplicateCidAcrossUnsupportedSvgAndRasterPartsIsNotRendered() {
        List<AttachmentRow> attachments = List.of(
                new AttachmentRow(
                        UUID.fromString("30000000-0000-4000-8000-000000000003"),
                        "vector.svg",
                        "image/svg+xml",
                        12L,
                        "<duplicate@example.test>"),
                new AttachmentRow(
                        UUID.fromString("30000000-0000-4000-8000-000000000004"),
                        "raster.png",
                        "image/png",
                        20L,
                        "<duplicate@example.test>"));

        assertTrue(MailReadService.inlineImageUrls(THREAD_ID, MESSAGE_ID, attachments).isEmpty());
    }

    @Test
    void uniqueSupportedRasterCidStillMapsToTheOwnedInlineEndpoint() {
        UUID attachmentId = UUID.fromString("30000000-0000-4000-8000-000000000005");
        List<AttachmentRow> attachments = List.of(
                new AttachmentRow(attachmentId, "unique.png", "image/png", 20L, "<unique@example.test>"));

        assertEquals(
                Map.of(
                        "unique@example.test",
                        "/api/v1/threads/" + THREAD_ID + "/messages/" + MESSAGE_ID
                                + "/attachments/" + attachmentId + "/inline"),
                MailReadService.inlineImageUrls(THREAD_ID, MESSAGE_ID, attachments));
    }
}
