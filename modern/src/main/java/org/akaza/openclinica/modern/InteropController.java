package org.akaza.openclinica.modern;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.akaza.openclinica.sdk.dto.ApiResponse;
import org.akaza.openclinica.modern.service.ConfigurationDraftService;
import org.akaza.openclinica.modern.model.ConfigurationDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/interop")
public class InteropController {

    private static final Logger log = LoggerFactory.getLogger(InteropController.class);
    private static final String MAPPINGS_ID = "field-mappings-singleton";
    private static final String MAPPINGS_DRAFT_TYPE = "FIELD_MAPPINGS";

    private FhirContext fhirContext = FhirContext.forR4();
    private HapiContext hl7Context = new DefaultHapiContext();
    private Map<String, String> mappings = new ConcurrentHashMap<>();
    private ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private InteropService interopService;

    @Autowired
    private ConfigurationDraftService draftService;

    @PostConstruct
    public void init() {
        try {
            ConfigurationDraft draft = draftService.getDraft(MAPPINGS_ID);
            if (draft != null && draft.getDraftData() != null) {
                Map<String, String> loaded = objectMapper.readValue(draft.getDraftData(), new TypeReference<Map<String, String>>() {});
                mappings.putAll(loaded);
                log.info("Loaded field mapping configurations from configuration_drafts");
            }
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            log.info("No existing field mapping configurations found");
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            log.warn("Table configuration_drafts not found. Skipping field mappings initialization.");
        } catch (Exception e) {
            log.error("Failed to load field mappings", e);
        }
    }

    @PostMapping("/fhir")
    public ResponseEntity<ApiResponse<String>> ingestFhir(@RequestBody String payload) {
        try {
            IParser parser = fhirContext.newJsonParser();
            Patient patient = parser.parseResource(Patient.class, payload);
            interopService.validate(patient.getIdBase(), payload);
            log.info("Ingestion action: FHIR R4 resource received by user 'system', recordId: {}", patient.getIdBase());
            return ResponseEntity.ok(new ApiResponse<>("FHIR R4 resource received: " + patient.getIdBase()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiResponse<>("Invalid FHIR payload"));
        }
    }

    @PostMapping("/hl7")
    public ResponseEntity<ApiResponse<String>> ingestHl7(@RequestBody String payload) {
        try {
            PipeParser parser = hl7Context.getPipeParser();
            Message message = parser.parse(payload);
            interopService.validate(message.getName(), payload);
            log.info("Ingestion action: HL7 v2 message parsed by user 'system', recordId: {}", message.getName());
            return ResponseEntity.ok(new ApiResponse<>("HL7 v2 message parsed: " + message.getName()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiResponse<>("Invalid HL7 payload"));
        }
    }

    @GetMapping("/mapping/data")
    public ResponseEntity<ApiResponse<Map<String, String>>> getMappingInterface() {
        return ResponseEntity.ok(new ApiResponse<>(mappings));
    }

    @PostMapping("/mapping/data")
    public ResponseEntity<ApiResponse<String>> saveMapping(@jakarta.validation.Valid @RequestBody org.akaza.openclinica.modern.dto.MappingDataRequest newMappings) {
        try {
            interopService.validateTargetIdentifiers(
                newMappings.getTargetStudy(),
                newMappings.getTargetFormVersion(),
                newMappings.getTargetField()
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiResponse<>(e.getMessage()));
        }

        mappings.put("subject_id", newMappings.getSubjectId());
        mappings.put("event_id", newMappings.getEventId());
        mappings.put("item_value", newMappings.getItemValue());
        mappings.put("target_study", newMappings.getTargetStudy() != null ? newMappings.getTargetStudy() : "");
        mappings.put("target_form_version", newMappings.getTargetFormVersion() != null ? newMappings.getTargetFormVersion() : "");
        mappings.put("target_field", newMappings.getTargetField() != null ? newMappings.getTargetField() : "");

        try {
            String json = objectMapper.writeValueAsString(mappings);
            draftService.saveDraftWithId(MAPPINGS_ID, "system", MAPPINGS_DRAFT_TYPE, json);
            log.info("Mapping change action: user 'system' updated field mappings");
        } catch (Exception e) {
            log.error("Failed to persist field mappings", e);
        }
        return ResponseEntity.ok(new ApiResponse<>("Mapping saved"));
    }
    
    @GetMapping("/pipeline/review")
    public ResponseEntity<ApiResponse<List<org.akaza.openclinica.modern.dto.StagedRecordDto>>> pipelineReview() {
        return ResponseEntity.ok(new ApiResponse<>(interopService.getReviewQueueWithMetadata()));
    }

    @PostMapping("/pipeline/commit")
    public ResponseEntity<ApiResponse<String>> pipelineCommit(@RequestParam String recordId) {
        interopService.commit(recordId);
        log.info("Commit action: user 'system' committed recordId: {}", recordId);
        return ResponseEntity.ok(new ApiResponse<>("Data committed"));
    }

    @PostMapping("/pipeline/batch-commit")
    public ResponseEntity<ApiResponse<String>> pipelineBatchCommit(@RequestBody List<String> recordIds) {
        interopService.batchCommit(recordIds);
        log.info("Commit action: user 'system' committed batch of {} records", recordIds.size());
        return ResponseEntity.ok(new ApiResponse<>(recordIds.size() + " records batch committed"));
    }
}
