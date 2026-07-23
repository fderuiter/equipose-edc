package org.akaza.openclinica.core.interceptor;

import org.akaza.openclinica.domain.datamap.AuditLogEvent;
import org.akaza.openclinica.domain.datamap.AuditLogEventType;
import org.akaza.openclinica.domain.datamap.StudySubject;
import org.akaza.openclinica.domain.datamap.EventCrf;
import org.akaza.openclinica.domain.datamap.ItemData;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.StatelessSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.hibernate.query.Query;

import java.util.Date;
import java.util.Arrays;

import org.hibernate.persister.entity.EntityPersister;

public class AuditEventListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener, PreInsertEventListener {
    
    private AuditHashService hashService;
    
    public void setHashService(AuditHashService hashService) {
        this.hashService = hashService;
    }
    
    private final Object lock = new Object();

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        return false; // Return false to not veto the insert
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        handleAudit(event.getEntity(), "INSERT", event.getPersister().getFactory().openStatelessSession());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        handleAudit(event.getEntity(), "UPDATE", event.getPersister().getFactory().openStatelessSession());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        handleAudit(event.getEntity(), "DELETE", event.getPersister().getFactory().openStatelessSession());
    }

    private void handleAudit(Object entity, String action, StatelessSession session) {
        if (entity instanceof AuditLogEvent) {
            session.close();
            return;
        }
        
        String auditTable = null;
        Integer entityId = null;
        if (entity instanceof ItemData) {
            auditTable = "item_data";
            entityId = ((ItemData) entity).getItemDataId();
        } else if (entity instanceof EventCrf) {
            auditTable = "event_crf";
            entityId = ((EventCrf) entity).getEventCrfId();
        } else if (entity instanceof StudySubject) {
            auditTable = "study_subject";
            entityId = ((StudySubject) entity).getStudySubjectId();
        } else if (entity instanceof org.akaza.openclinica.domain.datamap.Subject) {
            auditTable = "subject";
            entityId = ((org.akaza.openclinica.domain.datamap.Subject) entity).getSubjectId();
        }
        
        if (auditTable == null) {
            session.close();
            return;
        }

        try {
            AuditLogEvent auditEvent = new AuditLogEvent();
            try {
                Object result = session.createNativeQuery("SELECT nextval('audit_log_event_audit_id_seq')").getSingleResult();
                if (result != null) {
                    auditEvent.setAuditId(((Number) result).intValue());
                }
            } catch (Exception e) {
                System.out.println("MANUAL SEQ FETCH FAILED: " + e.getMessage());
                e.printStackTrace();
            }
            auditEvent.setAuditDate(new Date());
            auditEvent.setAuditTable(auditTable);
            auditEvent.setEntityId(entityId);
            auditEvent.setEntityName(auditTable);
            auditEvent.setReasonForChange(action);
            
            AuditLogEventType eventType = new AuditLogEventType();
            eventType.setAuditLogEventTypeId(1);
            auditEvent.setAuditLogEventType(eventType);
            
            session.insert(auditEvent);
        } finally {
            session.close();
        }
    }
}
