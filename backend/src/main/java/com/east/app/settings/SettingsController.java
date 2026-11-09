package com.east.app.settings;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/ai-provider")
    public AIProviderSettingsResponse getAIProvider() {
        return settingsService.getAIProvider();
    }

    @PutMapping("/ai-provider")
    public AIProviderSettingsResponse putAIProvider(@Valid @RequestBody AIProviderUpdateRequest request) {
        return settingsService.putAIProvider(request);
    }

    @DeleteMapping("/ai-provider")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAIProvider() {
        settingsService.deleteAIProvider();
    }

    @GetMapping("/retention")
    public RetentionSettingsResponse getRetention() {
        return new RetentionSettingsResponse(settingsService.getBodyCacheDays());
    }

    @PatchMapping("/retention")
    public RetentionSettingsResponse updateRetention(@Valid @RequestBody RetentionSettingsRequest request) {
        return new RetentionSettingsResponse(settingsService.updateBodyCacheDays(request.bodyCacheDays()));
    }

    public record RetentionSettingsResponse(int bodyCacheDays) {
    }
}
