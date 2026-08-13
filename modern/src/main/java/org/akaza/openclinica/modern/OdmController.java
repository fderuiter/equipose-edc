package org.akaza.openclinica.modern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.UUID;

@RestController
@RequestMapping("/api/odm")
public class OdmController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> exportOdm(@RequestParam(required = false) String studyOid) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\">\n" +
                "  <Study>\n" +
                "    <MetaDataVersion>\n" +
                "       <Protocol/>\n" +
                "    </MetaDataVersion>\n" +
                "  </Study>\n" +
                "  <ClinicalData>\n" +
                "    <SubjectData/>\n" +
                "  </ClinicalData>\n" +
                "</ODM>";
        return ResponseEntity.ok(xml);
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_XML_VALUE)
    @Transactional
    public ResponseEntity<String> importOdm(jakarta.servlet.http.HttpServletRequest request) {
        Path tempFile = null;
        try {
            InputStream xmlStream = request.getInputStream();
            // 1. Save to temp file for streaming
            tempFile = Files.createTempFile("odm_import", ".xml");
            try (FileOutputStream out = new FileOutputStream(tempFile.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = xmlStream.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            // 2. Validate against XSD
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            ClassPathResource schemaResource = new ClassPathResource("properties/ODM1-3-0.xsd");
            Schema schema = factory.newSchema(schemaResource.getURL());
            Validator validator = schema.newValidator();
            try (FileInputStream fis = new FileInputStream(tempFile.toFile())) {
                validator.validate(new StreamSource(fis));
            } catch (Exception e) {
                e.printStackTrace();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid ODM schema");
            }

            // 3. Parse and extract identifiers
            String studyOid = null;
            String subjectOid = null;
            String studyEventOid = null;

            XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
            try (FileInputStream fis = new FileInputStream(tempFile.toFile())) {
                XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(fis);
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamReader.START_ELEMENT) {
                        String localName = reader.getLocalName();
                        if ("ClinicalData".equals(localName)) {
                            studyOid = reader.getAttributeValue(null, "StudyOID");
                        } else if ("SubjectData".equals(localName)) {
                            subjectOid = reader.getAttributeValue(null, "SubjectKey");
                        } else if ("StudyEventData".equals(localName)) {
                            studyEventOid = reader.getAttributeValue(null, "StudyEventOID");
                        }
                    }
                }
            }

            if (studyOid == null || subjectOid == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing StudyOID or SubjectKey");
            }

            // 4. Verify safety status (LOCKED=7, SIGNED=8, STOPPED=5)
            if (studyEventOid != null) {
                String sqlStatus = "SELECT se.subject_event_status_id FROM study_event se " +
                        "JOIN study_subject ss ON se.study_subject_id = ss.study_subject_id " +
                        "JOIN study s ON ss.study_id = s.study_id " +
                        "JOIN study_event_definition sed ON se.study_event_definition_id = sed.study_event_definition_id " +
                        "WHERE s.oc_oid = ? AND ss.oc_oid = ? AND sed.oc_oid = ? ORDER BY se.study_event_id LIMIT 1";

                Integer statusId = null;
                try {
                    statusId = jdbcTemplate.queryForObject(sqlStatus, Integer.class, studyOid, subjectOid, studyEventOid);
                } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                    // event not found, proceed
                }

                if (statusId != null && (statusId == 5 || statusId == 7 || statusId == 8)) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body("Study event is locked, signed, or stopped");
                }
            }

            // 5. Insert into clinical_records
            String id = UUID.randomUUID().toString();
            String xmlPayload = Files.readString(tempFile);
            String sql = "INSERT INTO clinical_records (id, study_oid, subject_oid, data) VALUES (?, ?, ?, ?)";
            jdbcTemplate.update(sql, id, studyOid, subjectOid, xmlPayload);

            // 6. Generate unsealed audit_log_event for hash chain
            Integer auditLogEventTypeId = 1;
            try {
                auditLogEventTypeId = jdbcTemplate.queryForObject("SELECT MIN(audit_log_event_type_id) FROM audit_log_event_type", Integer.class);
                if (auditLogEventTypeId == null) {
                    auditLogEventTypeId = 1;
                }
            } catch (Exception e) {
                // Ignore and use 1
            }

            String auditSql = "INSERT INTO audit_log_event " +
                "(audit_id, audit_date, audit_table, entity_id, entity_name, reason_for_change, old_value, new_value, audit_log_event_type_id) " +
                "VALUES (nextval('audit_log_event_audit_id_seq'), ?, ?, ?, ?, ?, ?, ?, ?)";
            
            jdbcTemplate.update(auditSql, 
                new Date(),
                "clinical_records",
                1, // entityId
                id, // entityName
                "Automated integration data update",
                null,
                "Imported clinical payload for subject: " + subjectOid,
                auditLogEventTypeId
            );

            return ResponseEntity.ok("Import successful");

        } catch (Exception e) {
            e.printStackTrace();
            // Throw exception to trigger @Transactional rollback
            throw new RuntimeException("Error processing import", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }
}
