package org.akaza.openclinica.modern;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.annotation.Propagation;

import org.akaza.openclinica.modern.model.ConfigurationDraft;
import org.akaza.openclinica.modern.service.ConfigurationDraftService;
import org.akaza.openclinica.service.clinical.UnifiedWorkflowEnforcementService;
import org.akaza.openclinica.service.clinical.WorkflowTransactionCallback;

import org.akaza.openclinica.domain.datamap.StudySubject;
import org.akaza.openclinica.domain.datamap.StudyEvent;
import org.akaza.openclinica.domain.datamap.EventCrf;
import org.akaza.openclinica.domain.datamap.ItemData;
import org.akaza.openclinica.domain.datamap.StudyEventDefinition;
import org.akaza.openclinica.domain.datamap.Item;
import org.akaza.openclinica.domain.datamap.Study;
import org.akaza.openclinica.domain.datamap.Subject;
import org.akaza.openclinica.domain.datamap.CrfVersion;
import org.akaza.openclinica.domain.datamap.CompletionStatus;
import org.akaza.openclinica.domain.Status;
import org.akaza.openclinica.domain.user.UserAccount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Service
public class InteropService {

    private static final Logger log = LoggerFactory.getLogger(InteropService.class);
    private static final String DRAFT_TYPE = "CLINICAL_RECORD";
    private static final String MAPPINGS_ID = "field-mappings-singleton";

    private Map<String, String> recordsInStaging = new ConcurrentHashMap<>();

    private static class ResizableSemaphore extends Semaphore {
        public ResizableSemaphore(int permits) {
            super(permits);
        }
        public void reducePermits(int reduction) {
            super.reducePermits(reduction);
        }
    }

    private ResizableSemaphore semaphore = new ResizableSemaphore(getConcurrencyLimit());
    private int currentSemaphoreLimit = getConcurrencyLimit();

    private int getConcurrencyLimit() {
        try {
            return Integer.parseInt(System.getProperty("interop.batch.concurrency.limit", "5"));
        } catch (Exception e) {
            return 5;
        }
    }

    private int getChunkSize() {
        try {
            return Integer.parseInt(System.getProperty("interop.batch.chunk.size", "1000"));
        } catch (Exception e) {
            return 1000;
        }
    }

    private void syncSemaphoreLimit() {
        int newLimit = getConcurrencyLimit();
        synchronized (this) {
            if (newLimit > currentSemaphoreLimit) {
                semaphore.release(newLimit - currentSemaphoreLimit);
                currentSemaphoreLimit = newLimit;
            } else if (newLimit < currentSemaphoreLimit) {
                semaphore.reducePermits(currentSemaphoreLimit - newLimit);
                currentSemaphoreLimit = newLimit;
            }
        }
    }

    private static class BatchRecord {
        String recordId;
        String subjectId;
        String eventId;
        String value;
        public BatchRecord(String recordId, String subjectId, String eventId, String value) {
            this.recordId = recordId;
            this.subjectId = subjectId;
            this.eventId = eventId;
            this.value = value;
        }
    }

    @Autowired
    private ConfigurationDraftService draftService;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private UnifiedWorkflowEnforcementService workflowService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private HapiContext hl7Context = new DefaultHapiContext();

    @PostConstruct
    public void init() {
        try {
            List<ConfigurationDraft> drafts = draftService.getDraftsByType(DRAFT_TYPE);
            for (ConfigurationDraft draft : drafts) {
                recordsInStaging.put(draft.getId(), draft.getDraftData());
            }
            log.info("Loaded {} clinical records from configuration_drafts", drafts.size());
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            log.warn("Table configuration_drafts not found. Skipping draft initialization for clinical records.");
        } catch (Exception e) {
            log.error("Failed to load clinical records from drafts", e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void validate(String recordId, String payload) {
        recordsInStaging.put(recordId, payload);
        draftService.saveDraftWithId(recordId, "system", DRAFT_TYPE, payload);
        log.info("Ingested and staged clinical record: {}", recordId);
    }

    public List<String> getReviewQueue() {
        return new ArrayList<>(recordsInStaging.keySet());
    }

    @Transactional(rollbackFor = Exception.class)
    public void commit(String recordId) {
        String payload = recordsInStaging.get(recordId);
        if (payload == null) {
            throw new IllegalArgumentException("Record not found in staging: " + recordId);
        }

        try {
            ConfigurationDraft mappingDraft = draftService.getDraft(MAPPINGS_ID);
            if (mappingDraft == null || mappingDraft.getDraftData() == null) {
                throw new IllegalStateException("Active field mappings not found.");
            }

            Map<String, String> mappings = objectMapper.readValue(mappingDraft.getDraftData(), new TypeReference<Map<String, String>>() {});
            
            String subjectIdPath = mappings.get("subject_id");
            String eventIdPath = mappings.get("event_id");
            String itemValuePath = mappings.get("item_value");

            String subjectId = null;
            String eventId = null;
            String value = null;

            if (payload.trim().startsWith("{")) {
                JsonNode rootNode = objectMapper.readTree(payload);
                subjectId = extractJson(rootNode, subjectIdPath);
                eventId = extractJson(rootNode, eventIdPath);
                value = extractJson(rootNode, itemValuePath);
            } else if (payload.trim().startsWith("MSH")) {
                Message message = hl7Context.getPipeParser().parse(payload);
                Terser terser = new Terser(message);
                subjectId = extractHl7(terser, subjectIdPath);
                eventId = extractHl7(terser, eventIdPath);
                value = extractHl7(terser, itemValuePath);
            } else {
                throw new IllegalArgumentException("Unknown payload format.");
            }

            if (subjectId == null || eventId == null || value == null) {
                throw new IllegalStateException("Failed to extract required clinical fields based on dynamic mapping selectors.");
            }

            log.info("Extracted mapped values: subjectId={}, eventId={}, value={}", subjectId, eventId, value);

            org.akaza.openclinica.model.ClinicalPayload payloadObj = new org.akaza.openclinica.model.ClinicalPayload(subjectId, eventId, value);
            final String fSubjectId = subjectId;
            final String fEventId = eventId;
            final String fValue = value;

            String targetStudy = mappings.get("target_study");
            String targetFormVersion = mappings.get("target_form_version");
            String targetField = mappings.get("target_field");

            final String fTargetStudy = targetStudy;
            final String fTargetFormVersion = targetFormVersion;
            final String fTargetField = targetField;

            workflowService.executeWorkflowTransaction(1L, payloadObj, new WorkflowTransactionCallback<Void>() {
                @Override
                public Void doInTransaction() {
                    processClinicalRecord(fSubjectId, fEventId, fValue, fTargetStudy, fTargetFormVersion, fTargetField);
                    return null;
                }
            });

            draftService.deleteDraft(recordId);
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    recordsInStaging.remove(recordId);
                }
            });
            log.info("Committed clinical record: {}", recordId);
        } catch (Exception e) {
            log.error("Failed to execute dynamic mapping or persist clinical data. Transaction rolled back for recordId: {}", recordId, e);
            throw new RuntimeException("Commit aborted: " + e.getMessage(), e);
        }
    }

    public void batchCommit(List<String> recordIds) {
        syncSemaphoreLimit();
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring permit", e);
        }

        try {
            List<BatchRecord> validatedRecords = new ArrayList<>();
            ConfigurationDraft mappingDraft = draftService.getDraft(MAPPINGS_ID);
            if (mappingDraft == null || mappingDraft.getDraftData() == null) {
                throw new IllegalStateException("Active field mappings not found.");
            }
            
            Map<String, String> mappings;
            try {
                mappings = objectMapper.readValue(mappingDraft.getDraftData(), new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse active field mappings.", e);
            }
            
            String subjectIdPath = mappings.get("subject_id");
            String eventIdPath = mappings.get("event_id");
            String itemValuePath = mappings.get("item_value");

            for (String recordId : recordIds) {
                String payload = recordsInStaging.get(recordId);
                if (payload == null) {
                    continue; // Skip silently or handle? Keep original behavior mostly
                }

                String subjectId = null;
                String eventId = null;
                String value = null;

                try {
                    if (payload.trim().startsWith("{")) {
                        JsonNode rootNode = objectMapper.readTree(payload);
                        subjectId = extractJson(rootNode, subjectIdPath);
                        eventId = extractJson(rootNode, eventIdPath);
                        value = extractJson(rootNode, itemValuePath);
                    } else if (payload.trim().startsWith("MSH")) {
                        Message message = hl7Context.getPipeParser().parse(payload);
                        Terser terser = new Terser(message);
                        subjectId = extractHl7(terser, subjectIdPath);
                        eventId = extractHl7(terser, eventIdPath);
                        value = extractHl7(terser, itemValuePath);
                    }
                } catch (Exception e) {
                    log.error("Error parsing payload for recordId {}", recordId, e);
                    continue;
                }

                if (subjectId != null && eventId != null && value != null) {
                    validatedRecords.add(new BatchRecord(recordId, subjectId, eventId, value));
                }
            }

            int count = validatedRecords.size();
            if (count == 0) return;

            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

            int chunkSize = getChunkSize();
            for (int i = 0; i < count; i += chunkSize) {
                int end = Math.min(i + chunkSize, count);
                List<BatchRecord> chunk = validatedRecords.subList(i, end);

                for (BatchRecord br : chunk) {
                    try {
                        tt.execute(new TransactionCallbackWithoutResult() {
                            @Override
                            protected void doInTransactionWithoutResult(TransactionStatus status) {
                                org.akaza.openclinica.model.ClinicalPayload payloadObj = new org.akaza.openclinica.model.ClinicalPayload(br.subjectId, br.eventId, br.value);

                                String targetStudy = mappings.get("target_study");
                                String targetFormVersion = mappings.get("target_form_version");
                                String targetField = mappings.get("target_field");

                                workflowService.executeWorkflowTransaction(1L, payloadObj, new WorkflowTransactionCallback<Void>() {
                                    @Override
                                    public Void doInTransaction() {
                                        processClinicalRecord(br.subjectId, br.eventId, br.value, targetStudy, targetFormVersion, targetField);
                                        return null;
                                    }
                                });

                                draftService.deleteDraft(br.recordId);
                                TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronizationAdapter() {
                                    @Override
                                    public void afterCommit() {
                                        recordsInStaging.remove(br.recordId);
                                    }
                                });
                            }
                        });
                    } catch (Exception e) {
                        log.error("Failed to commit batch record: {}", br.recordId, e);
                    }
                }
            }
            log.info("Committed batch of clinical records: count={}", count);

        } finally {
            semaphore.release();
        }
    }

    private void processClinicalRecord(String fSubjectId, String fEventId, String fValue, String targetStudyId, String targetFormVersionId, String targetFieldId) {
        StudySubject ss = null;
        try {
            ss = entityManager.createQuery("SELECT ss FROM StudySubject ss WHERE ss.ocOid = :id OR ss.label = :id", StudySubject.class)
                    .setParameter("id", fSubjectId)
                    .setMaxResults(1)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            Subject subject = new Subject();
            subject.setUniqueIdentifier(fSubjectId);
            entityManager.persist(subject);

            ss = new StudySubject();
            ss.setLabel(fSubjectId);
            ss.setOcOid(fSubjectId);
            Study study = findStudy(targetStudyId);
            if (study == null) {
                study = entityManager.find(Study.class, 1);
            }
            ss.setStudy(study);
            ss.setSubject(subject);
            Status stat = Status.getByCode(1);
            ss.setStatus(stat);
            ss.setDateCreated(new java.util.Date());
            UserAccount owner = entityManager.find(UserAccount.class, 1);
            ss.setUserAccount(owner);
            entityManager.persist(ss);
        }

        StudyEventDefinition sed = null;
        try {
            sed = entityManager.createQuery("SELECT sed FROM StudyEventDefinition sed WHERE sed.oc_oid = :oid", StudyEventDefinition.class)
                    .setParameter("oid", fEventId)
                    .setMaxResults(1)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            sed = entityManager.find(StudyEventDefinition.class, 1);
        }

        StudyEvent se = new StudyEvent();
        se.setStudySubject(ss);
        se.setStudyEventDefinition(sed);
        se.setStatusId(1);
        se.setDateCreated(new java.util.Date());
        UserAccount owner = entityManager.find(UserAccount.class, 1);
        se.setUserAccount(owner);
        entityManager.persist(se);

        EventCrf ec = new EventCrf();
        ec.setStudyEvent(se);
        ec.setStudySubject(ss);
        ec.setStatusId(1);
        ec.setDateCreated(new java.util.Date());
        ec.setUserAccount(owner);
        CrfVersion cv = findCrfVersion(targetFormVersionId);
        if (cv == null) {
            cv = entityManager.find(CrfVersion.class, 1);
        }
        ec.setCrfVersion(cv);
        CompletionStatus cs = entityManager.find(CompletionStatus.class, 1);
        ec.setCompletionStatus(cs);
        ec.setSdvStatus(false);
        entityManager.persist(ec);

        ItemData idata = new ItemData();
        idata.setEventCrf(ec);
        Item item = findItem(targetFieldId);
        if (item == null) {
            item = entityManager.find(Item.class, 1);
        }
        idata.setItem(item);
        Status stat = Status.getByCode(1);
        idata.setStatus(stat);
        idata.setValue(fValue);
        idata.setUserAccount(owner);
        idata.setDateCreated(new java.util.Date());
        entityManager.persist(idata);
    }

    @Transactional(readOnly = true)
    public Study findStudy(String id) {
        if (id == null || id.trim().isEmpty()) {
            return entityManager.find(Study.class, 1);
        }
        try {
            if (id.trim().matches("\\d+")) {
                Study study = entityManager.find(Study.class, Integer.parseInt(id.trim()));
                if (study != null) return study;
            }
        } catch (Exception e) {}
        try {
            return entityManager.createQuery("SELECT s FROM Study s WHERE s.oc_oid = :id OR s.name = :id", Study.class)
                    .setParameter("id", id.trim())
                    .setMaxResults(1)
                    .getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public CrfVersion findCrfVersion(String id) {
        if (id == null || id.trim().isEmpty()) {
            return entityManager.find(CrfVersion.class, 1);
        }
        try {
            if (id.trim().matches("\\d+")) {
                CrfVersion cv = entityManager.find(CrfVersion.class, Integer.parseInt(id.trim()));
                if (cv != null) return cv;
            }
        } catch (Exception e) {}
        try {
            return entityManager.createQuery("SELECT cv FROM CrfVersion cv WHERE cv.ocOid = :id OR cv.name = :id", CrfVersion.class)
                    .setParameter("id", id.trim())
                    .setMaxResults(1)
                    .getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public Item findItem(String id) {
        if (id == null || id.trim().isEmpty()) {
            return entityManager.find(Item.class, 1);
        }
        try {
            if (id.trim().matches("\\d+")) {
                Item item = entityManager.find(Item.class, Integer.parseInt(id.trim()));
                if (item != null) return item;
            }
        } catch (Exception e) {}
        try {
            return entityManager.createQuery("SELECT i FROM Item i WHERE i.ocOid = :id OR i.name = :id", Item.class)
                    .setParameter("id", id.trim())
                    .setMaxResults(1)
                    .getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public void validateTargetIdentifiers(String studyId, String formVersionId, String fieldId) {
        if (studyId != null && !studyId.trim().isEmpty()) {
            if (findStudy(studyId) == null) {
                throw new IllegalArgumentException("Target Study identifier '" + studyId + "' does not exist in the database.");
            }
        }
        if (formVersionId != null && !formVersionId.trim().isEmpty()) {
            if (findCrfVersion(formVersionId) == null) {
                throw new IllegalArgumentException("Target Form Version identifier '" + formVersionId + "' does not exist in the database.");
            }
        }
        if (fieldId != null && !fieldId.trim().isEmpty()) {
            if (findItem(fieldId) == null) {
                throw new IllegalArgumentException("Target Field identifier '" + fieldId + "' does not exist in the database.");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<org.akaza.openclinica.modern.dto.StagedRecordDto> getReviewQueueWithMetadata() {
        List<org.akaza.openclinica.modern.dto.StagedRecordDto> result = new ArrayList<>();
        
        String targetStudyIdentifier = "";
        String targetFormVersionIdentifier = "";
        try {
            ConfigurationDraft mappingDraft = draftService.getDraft(MAPPINGS_ID);
            if (mappingDraft != null && mappingDraft.getDraftData() != null) {
                Map<String, String> mappings = objectMapper.readValue(mappingDraft.getDraftData(), new TypeReference<Map<String, String>>() {});
                targetStudyIdentifier = mappings.getOrDefault("target_study", "");
                targetFormVersionIdentifier = mappings.getOrDefault("target_form_version", "");
            }
        } catch (Exception e) {
            log.error("Failed to load mappings for review queue metadata", e);
        }

        // Get actual study name
        String studyName = "Unknown";
        Study study = findStudy(targetStudyIdentifier);
        if (study != null) {
            studyName = study.getName() != null ? study.getName() : study.getOc_oid();
        }

        // Get actual crf version name
        String formVersionName = "Unknown";
        CrfVersion cv = findCrfVersion(targetFormVersionIdentifier);
        if (cv != null) {
            formVersionName = cv.getName() != null ? cv.getName() : cv.getOcOid();
        }

        for (String recordId : recordsInStaging.keySet()) {
            result.add(new org.akaza.openclinica.modern.dto.StagedRecordDto(recordId, studyName, formVersionName));
        }
        return result;
    }

    private String extractJson(JsonNode rootNode, String jsonPointer) {
        if (jsonPointer == null || jsonPointer.isEmpty()) return null;
        if (!jsonPointer.startsWith("/")) jsonPointer = "/" + jsonPointer;
        JsonNode node = rootNode.at(jsonPointer);
        if (node.isMissingNode() || node.isNull()) return null;
        String val = node.asText();
        return (val == null || val.isEmpty()) ? null : val;
    }

    private String extractHl7(Terser terser, String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            String val = terser.get(path);
            return (val == null || val.isEmpty()) ? null : val;
        } catch (Exception e) {
            log.warn("Failed to extract HL7 path: {}", path);
            return null;
        }
    }
}
