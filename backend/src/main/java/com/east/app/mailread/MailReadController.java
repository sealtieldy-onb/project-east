package com.east.app.mailread;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.mailread.MailReadModels.MailboxLabelResponse;
import com.east.app.mailread.MailReadModels.ThreadDetailResponse;
import com.east.app.mailread.MailReadModels.ThreadPageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MailReadController {

    private final MailReadService mailReadService;

    public MailReadController(MailReadService mailReadService) {
        this.mailReadService = mailReadService;
    }

    @GetMapping("/threads")
    public ThreadPageResponse listThreads(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @RequestParam(required = false) UUID mailboxId,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Boolean starred,
            @RequestParam(required = false) UUID labelId,
            @RequestParam(required = false) String pageToken,
            @RequestParam(defaultValue = "30") int pageSize) {
        return mailReadService.listThreads(
                principal.appUserId(),
                new MailReadService.ThreadListQuery(
                        mailboxId, folder, category, query, unread, starred, labelId, pageToken, pageSize));
    }

    @GetMapping("/threads/{threadId}")
    public ThreadDetailResponse getThread(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID threadId) {
        return mailReadService.getThread(principal.appUserId(), threadId);
    }

    @GetMapping("/mailboxes/{mailboxId}/labels")
    public List<MailboxLabelResponse> getMailboxLabels(
            @AuthenticationPrincipal EastUserPrincipal principal,
            @PathVariable UUID mailboxId) {
        return mailReadService.getLabels(principal.appUserId(), mailboxId);
    }
}
