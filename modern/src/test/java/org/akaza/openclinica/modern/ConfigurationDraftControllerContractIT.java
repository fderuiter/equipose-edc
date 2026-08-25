package org.akaza.openclinica.modern;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.akaza.openclinica.modern.model.ConfigurationDraft;
import org.akaza.openclinica.modern.service.ConfigurationDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class ConfigurationDraftControllerContractIT extends AbstractIntegrationTest {

    @MockBean
    private ConfigurationDraftService draftService;

    @org.junit.jupiter.api.BeforeEach
    public void setUp() {
        org.springframework.security.core.Authentication auth = 
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("testUser", "N/A", java.util.Collections.emptyList());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    public void testGetDraftMatchesContract() throws Exception {
        ConfigurationDraft draft = new ConfigurationDraft();
        draft.setId("123");
        draft.setUserName("testUser");
        draft.setDraftType("DATASET");
        draft.setDraftData("{\"key\":\"value\"}");
        draft.setCreatedAt(new java.util.Date());
        draft.setExpiresAt(new java.util.Date());

        when(draftService.getDraft(anyString())).thenReturn(draft);

        String openapiJson = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/test/resources/established-contract.json")));
        com.atlassian.oai.validator.OpenApiInteractionValidator validator = com.atlassian.oai.validator.OpenApiInteractionValidator
                .createForInlineApiSpecification(openapiJson)
                .build();

        mockMvc.perform(get("/api/config/draft/123")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(OpenApiValidationMatchers.openApi().isValid(validator));
    }
}
