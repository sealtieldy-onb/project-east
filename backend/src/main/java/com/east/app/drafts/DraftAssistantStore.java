package com.east.app.drafts;

import com.east.app.auth.EastUserPrincipal;

/** Local draft operations required by the assistant; none perform Gmail mutations. */
public interface DraftAssistantStore {

    void validateAssistantRequest(EastUserPrincipal principal, DraftWriteRequest request);

    DraftResponse createAssistedDraft(
            EastUserPrincipal principal,
            DraftWriteRequest request,
            DraftSourceContext source);
}
