package org.akaza.openclinica.logic.importdata;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StreamingSubjectDataListTest {

    @Test
    public void testValidXmlSizeAndMetadata() {
        String validXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<ODM>\n" +
                "  <ClinicalData StudyOID=\"S_TEST01\">\n" +
                "    <SubjectData SubjectKey=\"SS_001\">\n" +
                "    </SubjectData>\n" +
                "    <SubjectData SubjectKey=\"SS_002\">\n" +
                "    </SubjectData>\n" +
                "  </ClinicalData>\n" +
                "</ODM>";

        StreamingSubjectDataList list = new StreamingSubjectDataList(validXml);
        assertEquals("S_TEST01", list.getStudyOid());
        assertEquals(2, list.size());
    }

    @Test
    public void testCorruptedXmlStreamThrowsRuntimeExceptionOnSize() {
        String corruptedXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<ODM>\n" +
                "  <ClinicalData StudyOID=\"S_TEST01\">\n" +
                "    <SubjectData SubjectKey=\"SS_001\">\n" +
                "    </SubjectData>\n" +
                "    <SubjectData SubjectKey=\"SS_002\">\n" +
                "    <CORRUPTED_TAG_NO_CLOSE";

        StreamingSubjectDataList list = new StreamingSubjectDataList(corruptedXml);
        assertEquals("S_TEST01", list.getStudyOid());

        try {
            list.size();
            fail("Expected RuntimeException when calculating size of corrupted XML stream");
        } catch (RuntimeException e) {
            assertNotNull(e.getMessage());
            assertNotNull("Root cause exception should be present", e.getCause());
            assertTrue("Root cause message should be propagated in runtime exception message",
                    e.getMessage().contains(e.getCause().getMessage()) || e.getMessage().contains("Error calculating dataset size"));
        }
    }

    @Test
    public void testMalformedXmlThrowsRuntimeException() {
        String malformedXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<ODM><ClinicalData StudyOID=\"S_TEST01\" <INVALID_SYNTAX>";

        try {
            new StreamingSubjectDataList(malformedXml);
            fail("Expected RuntimeException during initialization of malformed XML");
        } catch (RuntimeException e) {
            assertNotNull(e.getMessage());
            assertNotNull("Root cause should be passed through", e.getCause());
        }
    }
}
