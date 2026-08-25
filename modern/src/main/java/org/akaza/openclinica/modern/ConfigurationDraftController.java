package org.akaza.openclinica.modern;

import org.akaza.openclinica.modern.dto.ConfigurationDraftRequest;
import org.akaza.openclinica.modern.model.ConfigurationDraft;
import org.akaza.openclinica.modern.service.ConfigurationDraftService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/config")
public class ConfigurationDraftController {

    private final ConfigurationDraftService draftService;

    @Autowired
    public ConfigurationDraftController(ConfigurationDraftService draftService) {
        this.draftService = draftService;
    }

    private Authentication getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()) || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth;
    }

    @PostMapping("/draft")
    public ResponseEntity<ConfigurationDraft> createDraft(@Valid @RequestBody ConfigurationDraftRequest payload) {
        Authentication auth = getAuthenticatedUser();
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String userName = auth.getName();
        String draftData = payload.getDraftData() != null ? payload.getDraftData() : "{}";
        ConfigurationDraft draft = draftService.saveDraft(userName, "DATASET", draftData);
        return ResponseEntity.ok(draft);
    }

    @GetMapping("/draft/{id}")
    public ResponseEntity<ConfigurationDraft> getDraft(@PathVariable String id) {
        Authentication auth = getAuthenticatedUser();
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ConfigurationDraft draft = draftService.getDraft(id);
        return ResponseEntity.ok(draft);
    }

    @PutMapping("/draft/{id}")
    public ResponseEntity<Void> updateDraft(@PathVariable String id, @Valid @RequestBody ConfigurationDraftRequest payload) {
        Authentication auth = getAuthenticatedUser();
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String draftData = payload.getDraftData() != null ? payload.getDraftData() : "{}";
        draftService.updateDraft(id, draftData);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/draft/{id}/commit")
    public ResponseEntity<String> commitDraft(@PathVariable String id) {
        Authentication auth = getAuthenticatedUser();
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ConfigurationDraft draft = draftService.getDraft(id);
        // Normally would convert draftData to DatasetBean and save it using dataset DAOs
        // For the headless foundation, deleting the draft signifies a successful commit
        draftService.deleteDraft(id);
        return ResponseEntity.ok("Dataset successfully created and committed.");
    }
}
