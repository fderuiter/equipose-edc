package org.akaza.openclinica.core.interceptor;

import org.akaza.openclinica.domain.datamap.AuditLogEvent;
import org.akaza.openclinica.domain.datamap.ItemData;
import org.akaza.openclinica.domain.datamap.EventCrf;
import org.akaza.openclinica.exception.AuditSequenceException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.StatelessSession;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.query.NativeQuery;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class AuditEventListenerTest {

    private AuditEventListener listener;

    @Mock
    private PostInsertEvent insertEvent;

    @Mock
    private PostUpdateEvent updateEvent;

    @Mock
    private PostDeleteEvent deleteEvent;

    @Mock
    private EntityPersister persister;

    @Mock
    private SessionFactoryImplementor sessionFactory;

    @Mock
    private StatelessSession session;

    @Mock
    private NativeQuery query;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        listener = new AuditEventListener();

        when(insertEvent.getPersister()).thenReturn(persister);
        when(updateEvent.getPersister()).thenReturn(persister);
        when(deleteEvent.getPersister()).thenReturn(persister);
        when(persister.getFactory()).thenReturn(sessionFactory);
        when(sessionFactory.openStatelessSession()).thenReturn(session);
        when(session.createNativeQuery(anyString())).thenReturn(query);
    }

    @Test
    public void testSequenceRetrievalSuccess() {
        ItemData itemData = new ItemData();
        itemData.setItemDataId(42);
        when(insertEvent.getEntity()).thenReturn(itemData);
        when(query.getSingleResult()).thenReturn(1001);

        listener.onPostInsert(insertEvent);

        ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(session).insert(captor.capture());
        AuditLogEvent auditEvent = captor.getValue();
        assertNotNull(auditEvent);
        assertEquals(Integer.valueOf(1001), auditEvent.getAuditId());
        assertEquals("item_data", auditEvent.getAuditTable());
        assertEquals(Integer.valueOf(42), auditEvent.getEntityId());
        verify(session).close();
    }

    @Test
    public void testSequenceRetrievalFailureThrowsAuditSequenceExceptionOnInsert() {
        ItemData itemData = new ItemData();
        itemData.setItemDataId(42);
        when(insertEvent.getEntity()).thenReturn(itemData);
        when(query.getSingleResult()).thenThrow(new RuntimeException("Database sequence missing"));

        try {
            listener.onPostInsert(insertEvent);
            fail("Expected AuditSequenceException");
        } catch (AuditSequenceException e) {
            assertTrue(e.getMessage().contains("Failed to retrieve audit sequence"));
            assertNotNull(e.getCause());
        }

        verify(session, never()).insert(any());
        verify(session).close();
    }

    @Test
    public void testSequenceRetrievalReturnsNullThrowsAuditSequenceException() {
        ItemData itemData = new ItemData();
        itemData.setItemDataId(42);
        when(insertEvent.getEntity()).thenReturn(itemData);
        when(query.getSingleResult()).thenReturn(null);

        try {
            listener.onPostInsert(insertEvent);
            fail("Expected AuditSequenceException");
        } catch (AuditSequenceException e) {
            assertTrue(e.getMessage().contains("returned null"));
        }

        verify(session, never()).insert(any());
        verify(session).close();
    }

    @Test
    public void testSequenceRetrievalFailureOnPostUpdate() {
        EventCrf eventCrf = new EventCrf();
        eventCrf.setEventCrfId(10);
        when(updateEvent.getEntity()).thenReturn(eventCrf);
        when(query.getSingleResult()).thenThrow(new RuntimeException("Connection error"));

        try {
            listener.onPostUpdate(updateEvent);
            fail("Expected AuditSequenceException");
        } catch (AuditSequenceException e) {
            assertTrue(e.getMessage().contains("Failed to retrieve audit sequence"));
        }

        verify(session, never()).insert(any());
        verify(session).close();
    }

    @Test
    public void testSequenceRetrievalFailureOnPostDelete() {
        EventCrf eventCrf = new EventCrf();
        eventCrf.setEventCrfId(10);
        when(deleteEvent.getEntity()).thenReturn(eventCrf);
        when(query.getSingleResult()).thenThrow(new RuntimeException("Connection error"));

        try {
            listener.onPostDelete(deleteEvent);
            fail("Expected AuditSequenceException");
        } catch (AuditSequenceException e) {
            assertTrue(e.getMessage().contains("Failed to retrieve audit sequence"));
        }

        verify(session, never()).insert(any());
        verify(session).close();
    }

    @Test
    public void testNonAuditedEntityDoesNotThrowAndClosesSession() {
        Object nonAuditedObject = new Object();
        when(insertEvent.getEntity()).thenReturn(nonAuditedObject);

        listener.onPostInsert(insertEvent);

        verify(query, never()).getSingleResult();
        verify(session, never()).insert(any());
        verify(session).close();
    }

    @Test
    public void testAuditLogEventEntityDoesNotThrowAndClosesSession() {
        AuditLogEvent auditLogEvent = new AuditLogEvent();
        when(insertEvent.getEntity()).thenReturn(auditLogEvent);

        listener.onPostInsert(insertEvent);

        verify(query, never()).getSingleResult();
        verify(session, never()).insert(any());
        verify(session).close();
    }
}
