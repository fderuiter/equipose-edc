package org.akaza.openclinica.service.clinical;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.dao.login.UserAccountDAO;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.dao.managestudy.StudyDAO;
import org.akaza.openclinica.bean.managestudy.StudyEventDefinitionBean;
import org.akaza.openclinica.dao.managestudy.StudyEventDefinitionDAO;
import org.akaza.openclinica.bean.submit.CRFVersionBean;
import org.akaza.openclinica.dao.submit.CRFVersionDAO;
import org.akaza.openclinica.bean.submit.EventCRFBean;
import org.akaza.openclinica.dao.submit.EventCRFDAO;
import org.akaza.openclinica.service.clinical.exception.CRFLockedException;
import org.akaza.openclinica.service.clinical.exception.ClinicalWorkflowException;
import org.akaza.openclinica.core.CRFLocker;
import org.akaza.openclinica.dao.hibernate.DiscrepancyNoteDao;
import org.akaza.openclinica.dao.hibernate.DiscrepancyNoteTypeDao;
import org.akaza.openclinica.dao.hibernate.DnItemDataMapDao;
import org.akaza.openclinica.dao.hibernate.EventDefinitionCrfDao;
import org.akaza.openclinica.dao.hibernate.ItemDataDao;
import org.akaza.openclinica.dao.hibernate.ResolutionStatusDao;
import org.akaza.openclinica.domain.Status;
import org.akaza.openclinica.domain.datamap.DiscrepancyNote;
import org.akaza.openclinica.domain.datamap.DnItemDataMap;
import org.akaza.openclinica.domain.datamap.DnItemDataMapId;
import org.akaza.openclinica.domain.datamap.EventCrf;
import org.akaza.openclinica.domain.datamap.EventDefinitionCrf;
import org.akaza.openclinica.domain.datamap.ItemData;
import org.akaza.openclinica.domain.datamap.Study;
import org.akaza.openclinica.domain.datamap.StudySubject;
import org.akaza.openclinica.dao.hibernate.EventCrfDao;
import org.akaza.openclinica.dao.hibernate.StudyEventDao;
import org.akaza.openclinica.patterns.ocobserver.StudyEventContainer;
import org.akaza.openclinica.domain.datamap.StudyEvent;
import org.akaza.openclinica.domain.rule.RuleSetBean;
import org.akaza.openclinica.domain.rule.action.RuleActionRunBean.Phase;
import org.akaza.openclinica.domain.user.UserAccount;
import org.akaza.openclinica.service.rule.RuleSetServiceInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnifiedWorkflowEnforcementService {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedWorkflowEnforcementService.class);

    @Autowired
    @Qualifier("crfLocker")
    private CRFLocker crfLocker;

    @Autowired
    private EventDefinitionCrfDao eventDefinitionCrfDao;

    @Autowired
    private RuleSetServiceInterface ruleSetService;

    @Autowired
    private ItemDataDao itemDataDao;

    @Autowired
    private DiscrepancyNoteDao discrepancyNoteDao;

    @Autowired
    private ResolutionStatusDao resolutionStatusDao;

    @Autowired
    private DiscrepancyNoteTypeDao discrepancyNoteTypeDao;

    @Autowired
    private DnItemDataMapDao dnItemDataMapDao;

    private DataSource dataSource;

    @Autowired
    public void setDataSource(DataSource dataSource) {
        if (dataSource instanceof org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy) {
            this.dataSource = dataSource;
        } else {
            this.dataSource = new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(dataSource);
        }
    }
    @Autowired
    @Lazy
    private org.akaza.openclinica.service.streamer.AIEventStreamerService aiEventStreamerService;

    @Autowired
    private EventCrfDao eventCrfDao;

    @Autowired
    private StudyEventDao studyEventDao;

    @Autowired
    private org.akaza.openclinica.dao.hibernate.StudyDao studyDao;

    @Autowired
    private org.akaza.openclinica.dao.hibernate.UserAccountDao userAccountDao;

    @Autowired
    private org.akaza.openclinica.dao.hibernate.StudySubjectDao studySubjectDao;

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRED, rollbackFor = Exception.class)
    public <T> T executeWorkflowTransaction(
            Long userId, 
            org.akaza.openclinica.model.ClinicalPayload payload, 
            WorkflowTransactionCallback<T> callback) {

        // Validations
        if (payload == null || payload.getSubjectId() == null || payload.getEventId() == null || payload.getValue() == null) {
            throw new ClinicalWorkflowException("Invalid clinical payload: Missing required fields");
        }
        
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        
        Long studyId = 1L;
        // Pessimistic Locking
        try {
            jdbcTemplate.execute("SELECT study_id FROM study WHERE study_id = " + studyId + " FOR UPDATE");
        } catch (Exception e) {
            logger.warn("Could not lock study " + studyId, e);
        }

        // Execute transaction callback
        T result = null;
        if (callback != null) {
            result = callback.doInTransaction();
        }

        // Cache eviction
        try {
            net.sf.ehcache.CacheManager manager = net.sf.ehcache.CacheManager.getInstance();
            if (manager != null) {
                net.sf.ehcache.Cache cache = manager.getCache("studyCache");
                if (cache != null) {
                    cache.remove(studyId.intValue());
                }
            }
        } catch (Exception e) {
            logger.warn("Cache eviction failed", e);
        }

        // Audit Logging
        try {
            org.akaza.openclinica.service.audit.AuditService auditService = new org.akaza.openclinica.service.audit.AuditService(dataSource);
            org.akaza.openclinica.bean.admin.AuditEventBean auditEvent = new org.akaza.openclinica.bean.admin.AuditEventBean();
            auditEvent.setAuditDate(new Date());
            auditEvent.setAuditTable("item_data");
            auditEvent.setEntityId(userId != null ? userId.intValue() : 0);
            auditEvent.setReasonForChange("Automated integration data update");
            auditEvent.setActionMessage("Imported clinical payload for subject: " + payload.getSubjectId());
            auditEvent.setUpdaterId(userId != null ? userId.intValue() : 0);
            auditService.logEvent(auditEvent, null);
        } catch (Exception e) {
            logger.warn("Audit logging failed", e);
        }

        return result;
    }

    public void validateLock(int eventCrfId) {
        EventCrf eventCrf = eventCrfDao.findById(eventCrfId);
        validateLock(eventCrf, null);
    }

    public void validateLock(int eventCrfId, Integer userId) {
        EventCrf eventCrf = eventCrfDao.findById(eventCrfId);
        validateLock(eventCrf, userId);
    }

    public void validateLock(EventCrf eventCrf) {
        validateLock(eventCrf, null);
    }

    public void validateLock(EventCrf eventCrf, Integer userId) {
        if (eventCrf != null && eventCrf.getEventCrfId() != 0) {
            if (eventCrf.getStatusId() == Status.LOCKED.getCode() || eventCrf.getStatusId() == Status.SIGNED.getCode()) {
                throw new ClinicalWorkflowException("CRF is locked or signed.");
            }
            if (eventCrf.getStudyEvent() != null) {
                int subjectEventStatusId = eventCrf.getStudyEvent().getSubjectEventStatusId();
                if (subjectEventStatusId == 7 || subjectEventStatusId == 8) {
                    throw new ClinicalWorkflowException("Study Event is locked or signed.");
                }
            }

            if (userId != null) {
                if (!crfLocker.lock(eventCrf.getEventCrfId(), userId)) {
                    throw new CRFLockedException("Record is locked by a web user.");
                }
            } else {
                if (crfLocker.isLocked(eventCrf.getEventCrfId())) {
                    throw new CRFLockedException("Record is locked by a web user.");
                }
            }
        }
    }

    public void unlock(EventCrf eventCrf) {
        if (eventCrf != null && eventCrf.getEventCrfId() != 0) {
            crfLocker.unlock(eventCrf.getEventCrfId());
        }
    }

    public void unlock(int eventCrfId) {
        if (eventCrfId != 0) {
            crfLocker.unlock(eventCrfId);
        }
    }

    public void verifyDDEStatus(Integer studyEventDefinitionId, Integer crfId) {
        List<EventDefinitionCrf> edcs = eventDefinitionCrfDao.findByStudyEventDefinitionId(studyEventDefinitionId);
        for (EventDefinitionCrf edc : edcs) {
            if (edc.getCrf().getCrfId() == crfId.intValue() && Boolean.TRUE.equals(edc.getDoubleEntry())) {
                throw new ClinicalWorkflowException("Double Data Entry is required. Bypassing verification workflow is not permitted via API.");
            }
        }
    }

    @Transactional
    public ItemData saveItemData(ItemData itemData, EventCrf eventCrf, Study study, UserAccount user, StudySubject studySubject) {
        // Validation check for locked records
        validateLock(eventCrf);

        // Discrepancy Note Type 4 (Reason for Change) capture for completed records
        boolean requiresRfc = (eventCrf.getStatusId() == Status.UNAVAILABLE.getCode() && itemData.getItemDataId() != 0);

        ItemData savedData = itemDataDao.saveOrUpdate(itemData);

        if (requiresRfc) {
            createReasonForChangeNote(savedData, study, user, studySubject);
        }

        return savedData;
    }

    @Transactional
    public void captureReasonForChange(int itemDataId, int eventCrfId, int studyId, int userId, int studySubjectId) {
        ItemData itemData = itemDataDao.findById(itemDataId);
        EventCrf eventCrf = eventCrfDao.findById(eventCrfId);
        Study study = studyDao.findById(studyId);
        UserAccount user = userAccountDao.findById(userId);
        StudySubject studySubject = studySubjectDao.findById(studySubjectId);
        
        boolean requiresRfc = (eventCrf.getStatusId() == Status.UNAVAILABLE.getCode() && itemData != null && itemData.getItemDataId() != 0);
        if (requiresRfc) {
            createReasonForChangeNote(itemData, study, user, studySubject);
        }
    }

    private void createReasonForChangeNote(ItemData itemData, Study study, UserAccount user, StudySubject studySubject) {
        DiscrepancyNote dn = new DiscrepancyNote();
        dn.setStudy(study);
        dn.setEntityType("itemData");
        dn.setDescription("Reason for Change");
        dn.setDetailedNotes("API submission updated a completed or verified record.");
        dn.setDiscrepancyNoteType(discrepancyNoteTypeDao.findByDiscrepancyNoteTypeId(4)); // Type 4
        dn.setResolutionStatus(resolutionStatusDao.findByResolutionStatusId(1));
        dn.setUserAccount(user);
        dn.setUserAccountByOwnerId(user);
        dn.setDateCreated(new Date());
        dn = discrepancyNoteDao.saveOrUpdate(dn);

        DnItemDataMapId dnItemDataMapId = new DnItemDataMapId();
        dnItemDataMapId.setDiscrepancyNoteId(dn.getDiscrepancyNoteId());
        dnItemDataMapId.setItemDataId(itemData.getItemDataId());
        dnItemDataMapId.setStudySubjectId(studySubject.getStudySubjectId());
        dnItemDataMapId.setColumnName("value");

        DnItemDataMap mapping = new DnItemDataMap();
        mapping.setDnItemDataMapId(dnItemDataMapId);
        mapping.setItemData(itemData);
        mapping.setStudySubject(studySubject);
        mapping.setActivated(false);
        mapping.setDiscrepancyNote(dn);
        dnItemDataMapDao.saveOrUpdate(mapping);
    }

    @Transactional
    public void executeRulesAndMetadata(EventCrf eventCrf, Study study, UserAccount user) {
        try {
            EventCRFDAO ecdao = new EventCRFDAO(dataSource);
            EventCRFBean ecb = (EventCRFBean) ecdao.findByPK(eventCrf.getEventCrfId());

            StudyDAO sdao = new StudyDAO(dataSource);
            StudyBean studyBean = (StudyBean) sdao.findByPK(study.getStudyId());

            UserAccountDAO udao = new UserAccountDAO(dataSource);
            UserAccountBean ub = (UserAccountBean) udao.findByPK(user.getUserId());

            StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
            StudyEventDefinitionBean sed = (StudyEventDefinitionBean) sedDao.findByPK(eventCrf.getStudyEvent().getStudyEventDefinition().getStudyEventDefinitionId());

            CRFVersionDAO cvDao = new CRFVersionDAO(dataSource);
            CRFVersionBean crfVersion = (CRFVersionBean) cvDao.findByPK(eventCrf.getCrfVersion().getCrfVersionId());

            List<RuleSetBean> ruleSets = ruleSetService.getRuleSetsByCrfStudyAndStudyEventDefinition(studyBean, sed, crfVersion);
            ruleSets = ruleSetService.filterByStatusEqualsAvailable(ruleSets);

            if (ruleSets != null && !ruleSets.isEmpty()) {
                HashMap<String, String> variableAndValue = new HashMap<>();
                Map<String, Object> request = new HashMap<>();
                ruleSetService.runRulesInDataEntry(ruleSets, false, studyBean, ub, variableAndValue, Phase.IMPORT, ecb, request);
                logger.info("Executed rules engine for API submission. Dynamic metadata updated.");
            }
        } catch (Exception e) {
            logger.error("Failed to execute rules engine.", e);
        }
    }

    @Transactional
    public EventCrf saveEventCrf(EventCrf eventCrf) {
        validateLock(eventCrf);
        EventCrf saved = eventCrfDao.saveOrUpdate(eventCrf);
        try {
            if (aiEventStreamerService != null && saved.getEventCrfId() > 0) {
                aiEventStreamerService.streamEventCrfAsync(saved.getEventCrfId());
            }
        } catch (Exception e) {
            logger.warn("Could not trigger AIEventStreamerService", e);
        }
        return saved;
    }

    @Transactional
    public StudyEvent saveStudyEvent(StudyEventContainer container) {
        return studyEventDao.saveOrUpdateTransactional(container);
    }
}
