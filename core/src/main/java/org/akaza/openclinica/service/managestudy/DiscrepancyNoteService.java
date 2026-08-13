/* 
 * GNU Lesser General Public License (GNU LGPL).
 * For details see: http://www.openclinica.org/license
 *
 * OpenClinica is distributed under the
 * Copyright 2003-2008 Akaza Research 
 */
package org.akaza.openclinica.service.managestudy;

import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.bean.managestudy.DiscrepancyNoteBean;
import org.akaza.openclinica.dao.hibernate.DiscrepancyNoteDao;
import org.akaza.openclinica.dao.hibernate.DiscrepancyNoteTypeDao;
import org.akaza.openclinica.dao.hibernate.ResolutionStatusDao;
import org.akaza.openclinica.dao.hibernate.StudyDao;
import org.akaza.openclinica.dao.hibernate.UserAccountDao;
import org.akaza.openclinica.domain.datamap.DiscrepancyNote;
import org.akaza.openclinica.domain.datamap.DiscrepancyNoteType;
import org.akaza.openclinica.domain.datamap.ResolutionStatus;
import org.akaza.openclinica.domain.datamap.Study;
import org.akaza.openclinica.domain.user.UserAccount;
import org.akaza.openclinica.domain.datamap.DnSubjectMap;
import org.akaza.openclinica.domain.datamap.DnSubjectMapId;
import org.akaza.openclinica.domain.datamap.DnStudySubjectMap;
import org.akaza.openclinica.domain.datamap.DnStudySubjectMapId;
import org.akaza.openclinica.domain.datamap.DnEventCrfMap;
import org.akaza.openclinica.domain.datamap.DnEventCrfMapId;
import org.akaza.openclinica.domain.datamap.DnStudyEventMap;
import org.akaza.openclinica.domain.datamap.DnStudyEventMapId;
import org.akaza.openclinica.domain.datamap.DnItemDataMap;
import org.akaza.openclinica.domain.datamap.DnItemDataMapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.akaza.openclinica.domain.rule.action.RuleActionRunLogBean;
import org.akaza.openclinica.dao.hibernate.RuleActionRunLogDao;

@Service("discrepancyNoteService")
public class DiscrepancyNoteService {

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    DataSource ds;
    
    @Autowired
    @Lazy
    private DiscrepancyNoteDao discrepancyNoteDao;
    
    @Autowired
    @Lazy
    private StudyDao studyDao;
    
    @Autowired
    @Lazy
    private UserAccountDao userAccountDao;
    
    @Autowired
    @Lazy
    private ResolutionStatusDao resolutionStatusDao;
    
    @Autowired
    @Lazy
    private DiscrepancyNoteTypeDao discrepancyNoteTypeDao;

    public DiscrepancyNoteService() {
    }

    public DiscrepancyNoteService(DataSource ds) {
        this.ds = ds;
    }

    @Transactional
    public void saveFieldNotes(final String description, final int entityId, final String entityType, final StudyBean sb, final UserAccountBean ub) {
        DiscrepancyNote parent = createDiscrepancyNote(description, entityId, entityType, sb, ub, null);
        createDiscrepancyNote(description, entityId, entityType, sb, ub, parent);
    }

    @Transactional
    public void saveFieldNotesAndRunLog(final String description, final int entityId, final String entityType, final StudyBean sb, final UserAccountBean ub, RuleActionRunLogBean ruleActionRunLog, RuleActionRunLogDao ruleActionRunLogDao) {
        saveFieldNotes(description, entityId, entityType, sb, ub);
        ruleActionRunLogDao.saveOrUpdate(ruleActionRunLog);
    }

    private DiscrepancyNote createDiscrepancyNote(String description, int entityId, String entityType, StudyBean sb, UserAccountBean ub,
            DiscrepancyNote parentNote) {
        DiscrepancyNote dnb = new DiscrepancyNote();
        
        Study study = getStudyDao().findById(sb.getId());
        UserAccount owner = getUserAccountDao().findById(ub.getId());
        ResolutionStatus rs = getResolutionStatusDao().findById(1);
        DiscrepancyNoteType dt = getDiscrepancyNoteTypeDao().findById(1);
        
        dnb.setStudy(study);
        dnb.setEntityType(entityType);
        dnb.setDescription(description);
        dnb.setResolutionStatus(rs);
        dnb.setDiscrepancyNoteType(dt);
        dnb.setUserAccountByOwnerId(owner);
        dnb.setDateCreated(new java.util.Date());
        
        if (parentNote != null) {
            dnb.setParentDiscrepancyNote(parentNote);
        }
        
        dnb = getDiscrepancyNoteDao().saveOrUpdate(dnb);
        getDiscrepancyNoteDao().createMapping(dnb, entityId, "value", entityType);
        return dnb;
    }

    @Transactional
    public DiscrepancyNoteBean findByPK(int id) {
        DiscrepancyNote dn = getDiscrepancyNoteDao().findById(id);
        return convertToBean(dn);
    }

    @Transactional
    public List<DiscrepancyNoteBean> findAllByEntityAndColumn(String entityType, int entityId, String column) {
        String tableName = "";
        String idColumnName = "";
        if ("subject".equalsIgnoreCase(entityType)) {
            tableName = "dn_subject_map";
            idColumnName = "subject_id";
        } else if ("studySub".equalsIgnoreCase(entityType)) {
            tableName = "dn_study_subject_map";
            idColumnName = "study_subject_id";
        } else if ("eventCrf".equalsIgnoreCase(entityType)) {
            tableName = "dn_event_crf_map";
            idColumnName = "event_crf_id";
        } else if ("studyEvent".equalsIgnoreCase(entityType)) {
            tableName = "dn_study_event_map";
            idColumnName = "study_event_id";
        } else if ("itemData".equalsIgnoreCase(entityType)) {
            tableName = "dn_item_data_map";
            idColumnName = "item_data_id";
        } else {
            return new ArrayList<DiscrepancyNoteBean>();
        }

        String queryStr = "SELECT dn.* FROM discrepancy_note dn, " + tableName + " m " +
                "WHERE m." + idColumnName + " = :entityId " +
                "AND m.column_name = :column " +
                "AND dn.discrepancy_note_id = m.discrepancy_note_id " +
                "ORDER BY dn.discrepancy_note_id ASC";

        jakarta.persistence.Query q = getDiscrepancyNoteDao().getEntityManager().createNativeQuery(queryStr, DiscrepancyNote.class);
        q.setParameter("entityId", entityId);
        q.setParameter("column", column);

        List<DiscrepancyNote> results = q.getResultList();
        List<DiscrepancyNoteBean> beans = new ArrayList<DiscrepancyNoteBean>();
        for (DiscrepancyNote dn : results) {
            beans.add(convertToBean(dn));
        }
        return beans;
    }

    @Transactional
    public DiscrepancyNoteBean create(DiscrepancyNoteBean noteBean) {
        if (noteBean.getParentDnId() == 0) {
            return saveNewThread(noteBean);
        } else {
            return updateThread(noteBean);
        }
    }

    @Transactional
    public void createMapping(DiscrepancyNoteBean noteBean) {
        // Handled inside saveNewThread / updateThread, but kept for backward compatibility
    }

    @Transactional
    public void update(DiscrepancyNoteBean noteBean) {
        DiscrepancyNote dn = getDiscrepancyNoteDao().findById(noteBean.getId());
        if (dn != null) {
            if (noteBean.getResolutionStatusId() > 0) {
                dn.setResolutionStatus(getResolutionStatusDao().findById(noteBean.getResolutionStatusId()));
            }
            if (noteBean.getAssignedUserId() > 0) {
                dn.setUserAccount(getUserAccountDao().findById(noteBean.getAssignedUserId()));
            } else {
                dn.setUserAccount(null);
            }
            getDiscrepancyNoteDao().saveOrUpdate(dn);
        }
    }

    @Transactional
    public void updateAssignedUser(DiscrepancyNoteBean noteBean) {
        update(noteBean);
    }

    @Transactional
    public void updateAssignedUserToNull(DiscrepancyNoteBean noteBean) {
        DiscrepancyNote dn = getDiscrepancyNoteDao().findById(noteBean.getId());
        if (dn != null) {
            dn.setUserAccount(null);
            getDiscrepancyNoteDao().saveOrUpdate(dn);
        }
    }

    @Transactional
    public DiscrepancyNoteBean saveNewThread(DiscrepancyNoteBean noteBean) {
        DiscrepancyNote parent = convertToEntity(noteBean);
        parent.setParentDiscrepancyNote(null);
        parent = getDiscrepancyNoteDao().saveOrUpdate(parent);
        getDiscrepancyNoteDao().createMapping(parent, noteBean.getEntityId(), noteBean.getColumn(), noteBean.getEntityType());

        DiscrepancyNote child = convertToEntity(noteBean);
        child.setParentDiscrepancyNote(parent);
        child = getDiscrepancyNoteDao().saveOrUpdate(child);
        getDiscrepancyNoteDao().createMapping(child, noteBean.getEntityId(), noteBean.getColumn(), noteBean.getEntityType());

        return convertToBean(child);
    }

    @Transactional
    public DiscrepancyNoteBean updateThread(DiscrepancyNoteBean noteBean) {
        DiscrepancyNote parent = getDiscrepancyNoteDao().findById(noteBean.getParentDnId());
        if (parent != null) {
            if (noteBean.getResolutionStatusId() > 0) {
                parent.setResolutionStatus(getResolutionStatusDao().findById(noteBean.getResolutionStatusId()));
            }
            if (noteBean.getDiscrepancyNoteTypeId() > 0) {
                parent.setDiscrepancyNoteType(getDiscrepancyNoteTypeDao().findById(noteBean.getDiscrepancyNoteTypeId()));
            }
            if (noteBean.getAssignedUserId() > 0) {
                parent.setUserAccount(getUserAccountDao().findById(noteBean.getAssignedUserId()));
            } else {
                parent.setUserAccount(null);
            }
            parent = getDiscrepancyNoteDao().saveOrUpdate(parent);
        }

        DiscrepancyNote child = convertToEntity(noteBean);
        child.setParentDiscrepancyNote(parent);
        child = getDiscrepancyNoteDao().saveOrUpdate(child);
        getDiscrepancyNoteDao().createMapping(child, noteBean.getEntityId(), noteBean.getColumn(), noteBean.getEntityType());

        return convertToBean(child);
    }

    public DiscrepancyNoteBean convertToBean(DiscrepancyNote dn) {
        if (dn == null) {
            return null;
        }
        DiscrepancyNoteBean bean = new DiscrepancyNoteBean();
        bean.setId(dn.getDiscrepancyNoteId());
        bean.setDescription(dn.getDescription());
        if (dn.getDiscrepancyNoteType() != null) {
            bean.setDiscrepancyNoteTypeId(dn.getDiscrepancyNoteType().getDiscrepancyNoteTypeId());
            bean.setDisType(org.akaza.openclinica.bean.core.DiscrepancyNoteType.get(dn.getDiscrepancyNoteType().getDiscrepancyNoteTypeId()));
        }
        if (dn.getResolutionStatus() != null) {
            bean.setResolutionStatusId(dn.getResolutionStatus().getResolutionStatusId());
            bean.setResStatus(org.akaza.openclinica.bean.core.ResolutionStatus.get(dn.getResolutionStatus().getResolutionStatusId()));
        }
        bean.setDetailedNotes(dn.getDetailedNotes());
        if (dn.getParentDiscrepancyNote() != null) {
            bean.setParentDnId(dn.getParentDiscrepancyNote().getDiscrepancyNoteId());
        } else {
            bean.setParentDnId(0);
        }
        bean.setEntityType(dn.getEntityType());
        bean.setCreatedDate(dn.getDateCreated());
        if (dn.getStudy() != null) {
            bean.setStudyId(dn.getStudy().getStudyId());
        }
        if (dn.getUserAccountByOwnerId() != null) {
            UserAccountBean owner = convertUserToBean(dn.getUserAccountByOwnerId());
            bean.setOwner(owner);
            bean.setOwnerId(owner.getId());
        }
        if (dn.getUserAccount() != null) {
            bean.setAssignedUserId(dn.getUserAccount().getUserId());
            bean.setAssignedUser(convertUserToBean(dn.getUserAccount()));
        }

        setMappingFields(dn, bean);

        return bean;
    }

    private void setMappingFields(DiscrepancyNote dn, DiscrepancyNoteBean bean) {
        String entityType = dn.getEntityType();
        bean.setEntityType(entityType);
        if ("subject".equalsIgnoreCase(entityType)) {
            if (dn.getDnSubjectMaps() != null && !dn.getDnSubjectMaps().isEmpty()) {
                DnSubjectMap map = dn.getDnSubjectMaps().get(0);
                bean.setEntityId(map.getDnSubjectMapId().getSubjectId());
                bean.setColumn(map.getDnSubjectMapId().getColumnName());
            }
        } else if ("studySub".equalsIgnoreCase(entityType)) {
            if (dn.getDnStudySubjectMaps() != null && !dn.getDnStudySubjectMaps().isEmpty()) {
                DnStudySubjectMap map = dn.getDnStudySubjectMaps().get(0);
                bean.setEntityId(map.getDnStudySubjectMapId().getStudySubjectId());
                bean.setColumn(map.getDnStudySubjectMapId().getColumnName());
            }
        } else if ("eventCrf".equalsIgnoreCase(entityType)) {
            if (dn.getDnEventCrfMaps() != null && !dn.getDnEventCrfMaps().isEmpty()) {
                DnEventCrfMap map = dn.getDnEventCrfMaps().get(0);
                bean.setEntityId(map.getDnEventCrfMapId().getEventCrfId());
                bean.setColumn(map.getDnEventCrfMapId().getColumnName());
            }
        } else if ("studyEvent".equalsIgnoreCase(entityType)) {
            if (dn.getDnStudyEventMaps() != null && !dn.getDnStudyEventMaps().isEmpty()) {
                DnStudyEventMap map = dn.getDnStudyEventMaps().get(0);
                bean.setEntityId(map.getDnStudyEventMapId().getStudyEventId());
                bean.setColumn(map.getDnStudyEventMapId().getColumnName());
            }
        } else if ("itemData".equalsIgnoreCase(entityType)) {
            if (dn.getDnItemDataMaps() != null && !dn.getDnItemDataMaps().isEmpty()) {
                DnItemDataMap map = dn.getDnItemDataMaps().get(0);
                bean.setEntityId(map.getDnItemDataMapId().getItemDataId());
                bean.setColumn(map.getDnItemDataMapId().getColumnName());
            }
        }
    }

    private UserAccountBean convertUserToBean(UserAccount user) {
        if (user == null) return null;
        UserAccountBean bean = new UserAccountBean();
        bean.setId(user.getUserId());
        bean.setName(user.getUserName());
        bean.setFirstName(user.getFirstName());
        bean.setLastName(user.getLastName());
        bean.setEmail(user.getEmail());
        return bean;
    }

    public DiscrepancyNote convertToEntity(DiscrepancyNoteBean bean) {
        if (bean == null) {
            return null;
        }
        DiscrepancyNote dn = new DiscrepancyNote();
        if (bean.getId() > 0) {
            dn.setDiscrepancyNoteId(bean.getId());
        }
        dn.setDescription(bean.getDescription());
        dn.setDetailedNotes(bean.getDetailedNotes());
        dn.setEntityType(bean.getEntityType());
        dn.setDateCreated(bean.getCreatedDate() != null ? bean.getCreatedDate() : new Date());

        if (bean.getStudyId() > 0) {
            dn.setStudy(getStudyDao().findById(bean.getStudyId()));
        }
        if (bean.getOwnerId() > 0) {
            dn.setUserAccountByOwnerId(getUserAccountDao().findById(bean.getOwnerId()));
        } else if (bean.getOwner() != null && bean.getOwner().getId() > 0) {
            dn.setUserAccountByOwnerId(getUserAccountDao().findById(bean.getOwner().getId()));
        }
        if (bean.getDiscrepancyNoteTypeId() > 0) {
            dn.setDiscrepancyNoteType(getDiscrepancyNoteTypeDao().findById(bean.getDiscrepancyNoteTypeId()));
        } else if (bean.getDisType() != null) {
            dn.setDiscrepancyNoteType(getDiscrepancyNoteTypeDao().findById(bean.getDisType().getId()));
        }
        if (bean.getResolutionStatusId() > 0) {
            dn.setResolutionStatus(getResolutionStatusDao().findById(bean.getResolutionStatusId()));
        } else if (bean.getResStatus() != null) {
            dn.setResolutionStatus(getResolutionStatusDao().findById(bean.getResStatus().getId()));
        }
        if (bean.getAssignedUserId() > 0) {
            dn.setUserAccount(getUserAccountDao().findById(bean.getAssignedUserId()));
        } else if (bean.getAssignedUser() != null && bean.getAssignedUser().getId() > 0) {
            dn.setUserAccount(getUserAccountDao().findById(bean.getAssignedUser().getId()));
        }
        if (bean.getParentDnId() > 0) {
            dn.setParentDiscrepancyNote(getDiscrepancyNoteDao().findById(bean.getParentDnId()));
        }
        return dn;
    }

    private DiscrepancyNoteDao getDiscrepancyNoteDao() {
        return discrepancyNoteDao;
    }

    private StudyDao getStudyDao() {
        return studyDao;
    }

    private UserAccountDao getUserAccountDao() {
        return userAccountDao;
    }

    private ResolutionStatusDao getResolutionStatusDao() {
        return resolutionStatusDao;
    }

    private DiscrepancyNoteTypeDao getDiscrepancyNoteTypeDao() {
        return discrepancyNoteTypeDao;
    }

}

