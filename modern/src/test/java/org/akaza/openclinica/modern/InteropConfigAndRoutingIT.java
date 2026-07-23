package org.akaza.openclinica.modern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.akaza.openclinica.modern.dto.MappingDataRequest;
import org.akaza.openclinica.modern.service.ConfigurationDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class InteropConfigAndRoutingIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InteropService interopService;

    @Autowired
    private ConfigurationDraftService draftService;

    @BeforeEach
    public void setUp() {
        // Drop and recreate tables needed
        jdbcTemplate.execute("DROP TABLE IF EXISTS study CASCADE; CREATE TABLE study (study_id INT PRIMARY KEY, status_id INT, unique_identifier VARCHAR(255), secondary_identifier VARCHAR(255), name VARCHAR(255), summary VARCHAR(255), date_planned_start TIMESTAMP, date_planned_end TIMESTAMP, date_created TIMESTAMP, date_updated TIMESTAMP, update_id INT, principal_investigator VARCHAR(255), facility_name VARCHAR(255), facility_city VARCHAR(255), facility_state VARCHAR(255), facility_zip VARCHAR(255), facility_country VARCHAR(255), facility_recruitment_status VARCHAR(255), facility_contact_name VARCHAR(255), facility_contact_degree VARCHAR(255), facility_contact_phone VARCHAR(255), facility_contact_email VARCHAR(255), protocol_type VARCHAR(255), protocol_description VARCHAR(255), protocol_date_verification TIMESTAMP, phase VARCHAR(255), expected_total_enrollment INT, sponsor VARCHAR(255), collaborators VARCHAR(255), medline_identifier VARCHAR(255), url VARCHAR(255), url_description VARCHAR(255), conditions VARCHAR(255), keywords VARCHAR(255), eligibility VARCHAR(255), gender VARCHAR(255), age_max VARCHAR(255), age_min VARCHAR(255), healthy_volunteer_accepted BOOLEAN, purpose VARCHAR(255), allocation VARCHAR(255), masking VARCHAR(255), control VARCHAR(255), assignment VARCHAR(255), endpoint VARCHAR(255), interventions VARCHAR(255), duration VARCHAR(255), selection VARCHAR(255), timing VARCHAR(255), official_title VARCHAR(255), results_reference BOOLEAN, oc_oid VARCHAR(255), old_status_id INT, parent_study_id INT, owner_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS crf_version CASCADE; CREATE TABLE crf_version (crf_version_id INT PRIMARY KEY, status_id INT, name VARCHAR(255), description VARCHAR(255), revision_notes VARCHAR(255), date_created TIMESTAMP, date_updated TIMESTAMP, update_id INT, oc_oid VARCHAR(255), xform VARCHAR(255), xform_name VARCHAR(255), owner_id INT, crf_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS item CASCADE; CREATE TABLE item (item_id INT PRIMARY KEY, status_id INT, name VARCHAR(255), description VARCHAR(255), units VARCHAR(255), phi_status BOOLEAN, date_created TIMESTAMP, date_updated TIMESTAMP, update_id INT, oc_oid VARCHAR(255), owner_id INT, item_reference_type_id INT, item_data_type_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS public.user_account CASCADE; CREATE TABLE public.user_account (user_id INT PRIMARY KEY, user_name VARCHAR(255), enabled BOOLEAN, account_non_locked BOOLEAN, enable_api_key BOOLEAN, run_webservices BOOLEAN, lock_counter INT, status_id INT, access_code VARCHAR(255), active_study INT, api_key VARCHAR(255), date_created TIMESTAMP, date_lastvisit TIMESTAMP, date_updated TIMESTAMP, email VARCHAR(255), first_name VARCHAR(255), institutional_affiliation VARCHAR(255), last_name VARCHAR(255), passwd VARCHAR(255), passwd_challenge_answer VARCHAR(255), passwd_challenge_question VARCHAR(255), passwd_timestamp TIMESTAMP, phone VARCHAR(255), time_zone VARCHAR(255), update_id INT, owner_id INT, user_type_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS completion_status CASCADE; CREATE TABLE completion_status (completion_status_id INT PRIMARY KEY, status_id INT, name VARCHAR(255), description VARCHAR(255))");
        jdbcTemplate.execute("DROP TABLE IF EXISTS study_subject CASCADE; CREATE TABLE study_subject (study_subject_id INT PRIMARY KEY, subject_id VARCHAR(255), study_id INT, label VARCHAR(255), secondary_label VARCHAR(255), status_id INT, date_created TIMESTAMP, date_updated TIMESTAMP, enrollment_date TIMESTAMP, oc_oid VARCHAR(255), owner_id INT, update_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS study_event_definition CASCADE; CREATE TABLE study_event_definition (study_event_definition_id INT PRIMARY KEY, status_id INT, name VARCHAR(255), oc_oid VARCHAR(255), description VARCHAR(255), repeating BOOLEAN, type VARCHAR(255), category VARCHAR(255), date_created TIMESTAMP, date_updated TIMESTAMP, update_id INT, ordinal INT, owner_id INT, study_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS study_event CASCADE; CREATE TABLE study_event (study_event_id INT PRIMARY KEY, study_event_definition_id INT, study_subject_id INT, status_id INT, owner_id INT, date_created TIMESTAMP, date_updated TIMESTAMP, location VARCHAR(255), sample_ordinal INT, date_start TIMESTAMP, date_end TIMESTAMP, update_id INT, subject_event_status_id INT, start_time_flag BOOLEAN, end_time_flag BOOLEAN)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS event_crf CASCADE; CREATE TABLE event_crf (event_crf_id INT PRIMARY KEY, study_event_id INT, study_subject_id INT, crf_version_id INT, completion_status_id INT, status_id INT, owner_id INT, date_created TIMESTAMP, date_updated TIMESTAMP, sdv_status BOOLEAN, date_interviewed DATE, annotations VARCHAR(255), date_completed TIMESTAMP, validator_id INT, date_validate TIMESTAMP, date_validate_completed TIMESTAMP, validator_annotations VARCHAR(255), validate_string VARCHAR(255), electronic_signature_id INT, update_id INT, electronic_signature_status BOOLEAN, interviewer_name VARCHAR(255), old_status_id INT, sdv_update_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS item_data CASCADE; CREATE TABLE item_data (item_data_id INT PRIMARY KEY, event_crf_id INT, item_id INT, status_id INT, value VARCHAR(255), owner_id INT, date_created TIMESTAMP, date_updated TIMESTAMP, ordinal INT, update_id INT, deleted BOOLEAN, old_status_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS subject CASCADE; CREATE TABLE subject (subject_id INT PRIMARY KEY, date_created TIMESTAMP, date_of_birth DATE, date_updated TIMESTAMP, dob_collected BOOLEAN, gender VARCHAR(255), status_id INT, father_id INT, mother_id INT, unique_identifier VARCHAR(255), update_id INT, owner_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS audit_event CASCADE; CREATE TABLE audit_event (AUDIT_ID INT PRIMARY KEY, AUDIT_DATE TIMESTAMP, AUDIT_TABLE VARCHAR(255), USER_ID INT, ENTITY_ID INT, REASON_FOR_CHANGE VARCHAR(255), ACTION_MESSAGE VARCHAR(255))");
        jdbcTemplate.execute("DROP TABLE IF EXISTS audit_log_event CASCADE; CREATE TABLE audit_log_event (audit_id INT PRIMARY KEY, audit_date TIMESTAMP, audit_log_event_type_id INT, audit_table VARCHAR(255), chain_hash VARCHAR(255), entity_id INT, entity_name VARCHAR(255), event_crf_id INT, event_crf_version_id INT, new_value VARCHAR(255), old_value VARCHAR(255), reason_for_change VARCHAR(255), study_event_id INT, user_id INT)");

        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS study_subject_study_subject_id_seq; CREATE SEQUENCE study_subject_study_subject_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS subject_subject_id_seq; CREATE SEQUENCE subject_subject_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS study_event_study_event_id_seq; CREATE SEQUENCE study_event_study_event_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS event_crf_event_crf_id_seq; CREATE SEQUENCE event_crf_event_crf_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS item_data_item_data_id_seq; CREATE SEQUENCE item_data_item_data_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS audit_event_audit_id_seq; CREATE SEQUENCE audit_event_audit_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS audit_log_event_audit_id_seq; CREATE SEQUENCE audit_log_event_audit_id_seq");

        // Insert defaults/specifics
        jdbcTemplate.execute("INSERT INTO study (study_id, name, oc_oid) VALUES (1, 'Default Study', 'S_DEFAULT')");
        jdbcTemplate.execute("INSERT INTO study (study_id, name, oc_oid) VALUES (100, 'Oncology Study', 'S_ONCO')");

        jdbcTemplate.execute("INSERT INTO crf_version (crf_version_id, name, oc_oid, crf_id) VALUES (1, 'Default CRF', 'V_DEFAULT', 1)");
        jdbcTemplate.execute("INSERT INTO crf_version (crf_version_id, name, oc_oid, crf_id) VALUES (200, 'CRF Version 2', 'V_CRF2', 1)");

        jdbcTemplate.execute("INSERT INTO item (item_id, name, oc_oid) VALUES (1, 'Default Item', 'I_DEFAULT')");
        jdbcTemplate.execute("INSERT INTO item (item_id, name, oc_oid) VALUES (300, 'Blood Pressure', 'I_BP')");

        jdbcTemplate.execute("INSERT INTO public.user_account (user_id, enabled, account_non_locked, enable_api_key, run_webservices, lock_counter) VALUES (1, true, true, false, false, 0)");
        jdbcTemplate.execute("INSERT INTO completion_status (completion_status_id) VALUES (1)");
        jdbcTemplate.execute("INSERT INTO study_event_definition (study_event_definition_id, oc_oid) VALUES (1, 'ev1')");
    }

    @Test
    public void testSaveMappingsValidation() throws Exception {
        // 1. Invalid Study OID should return 400 Bad Request
        MappingDataRequest invalidStudyReq = new MappingDataRequest();
        invalidStudyReq.setSubjectId("/sub");
        invalidStudyReq.setEventId("/ev");
        invalidStudyReq.setItemValue("/val");
        invalidStudyReq.setTargetStudy("NON_EXISTENT_STUDY");

        mockMvc.perform(post("/interop/mapping/data")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidStudyReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data", containsString("Target Study identifier 'NON_EXISTENT_STUDY' does not exist")));

        // 2. Valid configuration should succeed and save mapping
        MappingDataRequest validReq = new MappingDataRequest();
        validReq.setSubjectId("/sub");
        validReq.setEventId("/ev");
        validReq.setItemValue("/val");
        validReq.setTargetStudy("S_ONCO");
        validReq.setTargetFormVersion("V_CRF2");
        validReq.setTargetField("I_BP");

        mockMvc.perform(post("/interop/mapping/data")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is("Mapping saved")));
    }

    @Test
    public void testReviewQueueAndIngestionRouting() throws Exception {
        // Save mapping configuration with Target parameters
        MappingDataRequest validReq = new MappingDataRequest();
        validReq.setSubjectId("/subject_id");
        validReq.setEventId("/event_id");
        validReq.setItemValue("/item_value");
        validReq.setTargetStudy("S_ONCO");
        validReq.setTargetFormVersion("V_CRF2");
        validReq.setTargetField("I_BP");

        mockMvc.perform(post("/interop/mapping/data")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isOk());

        // Stage a clinical record
        String recordId = "staged-rec-123";
        String payload = "{\"subject_id\":\"subject-A\",\"event_id\":\"ev1\",\"item_value\":\"120/80\"}";
        interopService.validate(recordId, payload);

        // Verify the review queue shows correct target study name and CRF version name
        mockMvc.perform(get("/interop/pipeline/review")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].record_id", is(recordId)))
                .andExpect(jsonPath("$.data[0].target_study", is("Oncology Study")))
                .andExpect(jsonPath("$.data[0].target_form_version", is("CRF Version 2")));

        // Commit the staged record
        mockMvc.perform(post("/interop/pipeline/commit")
                .param("recordId", recordId))
                .andExpect(status().isOk());

        // Verify the data was stored under Study Oncology Study (100), CRF Version 2 (200), and Item Blood Pressure (300)
        // Check StudySubject was created with study_id = 100
        Integer studyId = jdbcTemplate.queryForObject("SELECT study_id FROM study_subject WHERE label = 'subject-A'", Integer.class);
        assertEquals(100, studyId);

        // Check EventCrf was created with crf_version_id = 200
        Integer crfVerId = jdbcTemplate.queryForObject("SELECT crf_version_id FROM event_crf WHERE study_subject_id = (SELECT study_subject_id FROM study_subject WHERE label = 'subject-A')", Integer.class);
        assertEquals(200, crfVerId);

        // Check ItemData was created with item_id = 300
        Integer itemId = jdbcTemplate.queryForObject("SELECT item_id FROM item_data WHERE value = '120/80'", Integer.class);
        assertEquals(300, itemId);
    }
}
