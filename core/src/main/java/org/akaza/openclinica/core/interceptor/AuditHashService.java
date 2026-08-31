package org.akaza.openclinica.core.interceptor;

import org.akaza.openclinica.domain.datamap.AuditLogEvent;
import org.hibernate.query.Query;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

@Service
public class AuditHashService {

    @Autowired
    private SessionFactory sessionFactory;

    @org.springframework.beans.factory.annotation.Value("${audit.seal.time.buffer.seconds:60}")
    private int auditSealTimeBufferSeconds;

    @Scheduled(fixedDelay = 60000)
    public void sealAudits() {
        StatelessSession session = sessionFactory.openStatelessSession();
        try {
            session.getTransaction().begin();
            try {
                // 1. Cluster coordination using a pessimistic database lock on an existing configuration record
                Query<org.akaza.openclinica.domain.technicaladmin.ConfigurationBean> lockQ = session.createQuery(
                    "FROM ConfigurationBean c WHERE c.key = 'user.lock.switch'", 
                    org.akaza.openclinica.domain.technicaladmin.ConfigurationBean.class);
                lockQ.setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                org.akaza.openclinica.domain.technicaladmin.ConfigurationBean lockRecord = lockQ.uniqueResult();
                
                if (lockRecord == null) {
                    Query<org.akaza.openclinica.domain.technicaladmin.ConfigurationBean> fallbackLockQ = session.createQuery(
                        "FROM ConfigurationBean c", org.akaza.openclinica.domain.technicaladmin.ConfigurationBean.class);
                    fallbackLockQ.setMaxResults(1);
                    fallbackLockQ.setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                    lockRecord = fallbackLockQ.uniqueResult();
                }

                // 2. Find last sealed hash that is not a legacy record
                Query<AuditLogEvent> lastSealedQ = session.createQuery(
                    "FROM AuditLogEvent WHERE chainHash IS NOT NULL AND chainHash != 'LEGACY_UNCHAINED' ORDER BY auditId DESC", AuditLogEvent.class);
                lastSealedQ.setMaxResults(1);
                AuditLogEvent lastSealed = lastSealedQ.uniqueResult();
                String prevHash = lastSealed != null ? lastSealed.getChainHash() : null;
                Integer lastSealedId = lastSealed != null ? lastSealed.getAuditId() : 0;

                // 3. Time-buffered safety window
                java.util.Date safeLimit = new java.util.Date(System.currentTimeMillis() - (auditSealTimeBufferSeconds * 1000L));

                // 4. Find unsealed audits (excluding LEGACY_UNCHAINED) created before the safeLimit
                Query<AuditLogEvent> unsealedQ = session.createQuery(
                    "FROM AuditLogEvent WHERE auditId > :lastSealedId AND (chainHash IS NULL OR chainHash != 'LEGACY_UNCHAINED') AND auditDate < :safeLimit ORDER BY auditId ASC", AuditLogEvent.class);
                unsealedQ.setParameter("lastSealedId", lastSealedId);
                unsealedQ.setParameter("safeLimit", safeLimit);
                unsealedQ.setFetchSize(100);
                
                ScrollableResults<AuditLogEvent> results = unsealedQ.scroll(ScrollMode.FORWARD_ONLY);
                while (results.next()) {
                    AuditLogEvent event = results.get();
                    if ("LEGACY_UNCHAINED".equals(event.getChainHash())) {
                        continue;
                    }
                    String newHash = computeHash(event, prevHash);
                    event.setChainHash(newHash);
                    session.update(event);
                    prevHash = newHash;
                }
                session.getTransaction().commit();
            } catch (Exception e) {
                if (session.getTransaction().isActive()) {
                    session.getTransaction().rollback();
                }
                throw e;
            }
        } finally {
            session.close();
        }
    }

    public static String computeHashValues(String previousHash, String auditTable, Object entityId, String entityName, String reasonForChange, String oldValue, String newValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(previousHash == null ? "ROOT" : previousHash);
            sb.append("|").append(auditTable != null ? auditTable : "");
            sb.append("|").append(entityId != null ? entityId : "");
            sb.append("|").append(entityName != null ? entityName : "");
            sb.append("|").append(reasonForChange != null ? reasonForChange : "");
            sb.append("|").append(oldValue != null ? oldValue : "");
            sb.append("|").append(newValue != null ? newValue : "");
            
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    public String computeHash(AuditLogEvent event, String previousHash) {
        return computeHashValues(
            previousHash,
            event.getAuditTable(),
            event.getEntityId(),
            event.getEntityName(),
            event.getReasonForChange(),
            event.getOldValue(),
            event.getNewValue()
        );
    }

    public synchronized String saveAndChain(AuditLogEvent event) {
        if (sessionFactory == null) {
            return null;
        }
        StatelessSession session = sessionFactory.openStatelessSession();
        try {
            session.getTransaction().begin();
            Query<AuditLogEvent> lastSealedQ = session.createQuery(
                "FROM AuditLogEvent WHERE chainHash IS NOT NULL AND chainHash != 'LEGACY_UNCHAINED' ORDER BY auditId DESC", AuditLogEvent.class);
            lastSealedQ.setMaxResults(1);
            AuditLogEvent lastSealed = lastSealedQ.uniqueResult();
            String prevHash = lastSealed != null ? lastSealed.getChainHash() : null;
            
            String newHash = computeHash(event, prevHash);
            event.setChainHash(newHash);
            
            if (event.getAuditDate() == null) {
                event.setAuditDate(new java.util.Date());
            }
            
            session.insert(event);
            session.getTransaction().commit();
            return newHash;
        } catch (Exception e) {
            if (session.getTransaction().isActive()) {
                session.getTransaction().rollback();
            }
            throw e;
        } finally {
            session.close();
        }
    }

    public void validateChain() {
        StatelessSession session = sessionFactory.openStatelessSession();
        try {
            Query<AuditLogEvent> query = session.createQuery("FROM AuditLogEvent ORDER BY auditId ASC", AuditLogEvent.class);
            query.setFetchSize(1000);
            ScrollableResults<AuditLogEvent> results = query.scroll(ScrollMode.FORWARD_ONLY);
            String currentHash = null;

            while (results.next()) {
                AuditLogEvent event = results.get();
                if ("LEGACY_UNCHAINED".equals(event.getChainHash())) {
                    continue;
                }
                String expectedHash = computeHash(event, currentHash);
                
                if (event.getChainHash() == null || !event.getChainHash().equals(expectedHash)) {
                    throw new DataIntegrityException("Audit log chain mismatch detected at auditId: " + event.getAuditId());
                }
                currentHash = expectedHash;
            }
        } finally {
            session.close();
        }
    }
}
