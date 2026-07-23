package org.akaza.openclinica.modern;

import org.akaza.openclinica.modern.service.ConfigurationDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class InteropServiceIT extends AbstractIntegrationTest {

    @Autowired
    private InteropService interopService;

    @Autowired
    private ConfigurationDraftService draftService;

    @Test
    public void testUpsertSyntaxExecution() {
        // Manually create tables required by UnifiedWorkflowEnforcementService
        jdbcTemplate.execute("DROP TABLE IF EXISTS study CASCADE; CREATE TABLE study (study_id INT PRIMARY KEY, status_id INT, unique_identifier VARCHAR(255), secondary_identifier VARCHAR(255), name VARCHAR(255), summary VARCHAR(255), date_planned_start TIMESTAMP, date_planned_end TIMESTAMP, date_created TIMESTAMP, date_updated TIMESTAMP, update_id INT, principal_investigator VARCHAR(255), facility_name VARCHAR(255), facility_city VARCHAR(255), facility_state VARCHAR(255), facility_zip VARCHAR(255), facility_country VARCHAR(255), facility_recruitment_status VARCHAR(255), facility_contact_name VARCHAR(255), facility_contact_degree VARCHAR(255), facility_contact_phone VARCHAR(255), facility_contact_email VARCHAR(255), protocol_type VARCHAR(255), protocol_description VARCHAR(255), protocol_date_verification TIMESTAMP, phase VARCHAR(255), expected_total_enrollment INT, sponsor VARCHAR(255), collaborators VARCHAR(255), medline_identifier VARCHAR(255), url VARCHAR(255), url_description VARCHAR(255), conditions VARCHAR(255), keywords VARCHAR(255), eligibility VARCHAR(255), gender VARCHAR(255), age_max VARCHAR(255), age_min VARCHAR(255), healthy_volunteer_accepted BOOLEAN, purpose VARCHAR(255), allocation VARCHAR(255), masking VARCHAR(255), control VARCHAR(255), assignment VARCHAR(255), endpoint VARCHAR(255), interventions VARCHAR(255), duration VARCHAR(255), selection VARCHAR(255), timing VARCHAR(255), official_title VARCHAR(255), results_reference BOOLEAN, oc_oid VARCHAR(255), old_status_id INT, parent_study_id INT, owner_id INT)");
        jdbcTemplate.execute("INSERT INTO study (study_id, oc_oid) VALUES (1, 'study-1')");

        jdbcTemplate.execute("DROP TABLE IF EXISTS public.user_account CASCADE; CREATE TABLE public.user_account (user_id INT PRIMARY KEY, access_code VARCHAR(255), account_non_locked BOOLEAN, active_study INT, api_key VARCHAR(255), date_created TIMESTAMP, date_lastvisit TIMESTAMP, date_updated TIMESTAMP, email VARCHAR(255), enable_api_key BOOLEAN, enabled BOOLEAN, first_name VARCHAR(255), institutional_affiliation VARCHAR(255), last_name VARCHAR(255), lock_counter INT, passwd VARCHAR(255), passwd_challenge_answer VARCHAR(255), passwd_challenge_question VARCHAR(255), passwd_timestamp TIMESTAMP, phone VARCHAR(255), run_webservices BOOLEAN, status_id INT, time_zone VARCHAR(255), update_id INT, owner_id INT, user_name VARCHAR(255), user_type_id INT)");
        jdbcTemplate.execute("INSERT INTO public.user_account (user_id, enabled, account_non_locked, enable_api_key, run_webservices, lock_counter) VALUES (1, true, true, false, false, 0)");

        jdbcTemplate.execute("DROP TABLE IF EXISTS crf_version CASCADE; CREATE TABLE crf_version (crf_version_id INT PRIMARY KEY, status_id INT, name VARCHAR(255), description VARCHAR(255), revision_notes VARCHAR(255), date_created TIMESTAMP, date_updated TIMESTAMP, update_id INT, oc_oid VARCHAR(255), xform VARCHAR(255), xform_name VARCHAR(255), owner_id INT, crf_id INT)");
        jdbcTemplate.execute("INSERT INTO crf_version (crf_version_id, oc_oid, crf_id) VALUES (1, 'cv-1', 1)");

        jdbcTemplate.execute("DROP TABLE IF EXISTS item CASCADE; CREATE TABLE item (item_id INT PRIMARY KEY, status_id INT, name VARCHAR(255), description VARCHAR(255), units VARCHAR(255), phi_status BOOLEAN, date_created TIMESTAMP, date_updated TIMESTAMP, update_id INT, oc_oid VARCHAR(255), owner_id INT, item_reference_type_id INT, item_data_type_id INT)");
        jdbcTemplate.execute("INSERT INTO item (item_id, oc_oid) VALUES (1, 'item-1')");

        jdbcTemplate.execute("DROP TABLE IF EXISTS completion_status CASCADE; CREATE TABLE completion_status (completion_status_id INT PRIMARY KEY, status_id INT, name VARCHAR(255), description VARCHAR(255))");
        jdbcTemplate.execute("INSERT INTO completion_status (completion_status_id) VALUES (1)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS study_subject CASCADE; CREATE TABLE study_subject (study_subject_id INT PRIMARY KEY, subject_id VARCHAR(255), study_id INT, label VARCHAR(255), secondary_label VARCHAR(255), status_id INT, date_created TIMESTAMP, date_updated TIMESTAMP, enrollment_date TIMESTAMP, oc_oid VARCHAR(255), owner_id INT, update_id INT)");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS study_subject_study_subject_id_seq; CREATE SEQUENCE study_subject_study_subject_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS subject_subject_id_seq; CREATE SEQUENCE subject_subject_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS study_event_study_event_id_seq; CREATE SEQUENCE study_event_study_event_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS event_crf_event_crf_id_seq; CREATE SEQUENCE event_crf_event_crf_id_seq");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS item_data_item_data_id_seq; CREATE SEQUENCE item_data_item_data_id_seq");
        jdbcTemplate.execute("DROP TABLE IF EXISTS study_event_definition CASCADE; CREATE TABLE study_event_definition (study_event_definition_id INT PRIMARY KEY, status_id INT, name VARCHAR(255), description VARCHAR(255), repeating BOOLEAN, type VARCHAR(255), category VARCHAR(255), date_created TIMESTAMP, date_updated TIMESTAMP, update_id INT, ordinal INT, oc_oid VARCHAR(255), owner_id INT, study_id INT)");
        jdbcTemplate.execute("INSERT INTO study_event_definition (study_event_definition_id, oc_oid) VALUES (1, 'ev1')");
        jdbcTemplate.execute("DROP TABLE IF EXISTS study_event CASCADE; CREATE TABLE study_event (study_event_id INT PRIMARY KEY, study_event_definition_id INT, study_subject_id INT, status_id INT, owner_id INT, date_created TIMESTAMP, date_updated TIMESTAMP, location VARCHAR(255), sample_ordinal INT, date_start TIMESTAMP, date_end TIMESTAMP, update_id INT, subject_event_status_id INT, start_time_flag BOOLEAN, end_time_flag BOOLEAN)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS event_crf CASCADE; CREATE TABLE event_crf (event_crf_id INT PRIMARY KEY, study_event_id INT, study_subject_id INT, crf_version_id INT, completion_status_id INT, status_id INT, owner_id INT, date_created TIMESTAMP, date_updated TIMESTAMP, sdv_status BOOLEAN, date_interviewed DATE, annotations VARCHAR(255), date_completed TIMESTAMP, validator_id INT, date_validate TIMESTAMP, date_validate_completed TIMESTAMP, validator_annotations VARCHAR(255), validate_string VARCHAR(255), electronic_signature_id INT, update_id INT, electronic_signature_status BOOLEAN, interviewer_name VARCHAR(255), old_status_id INT, sdv_update_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS item_data CASCADE; CREATE TABLE item_data (item_data_id INT PRIMARY KEY, event_crf_id INT, item_id INT, status_id INT, value VARCHAR(255), owner_id INT, date_created TIMESTAMP, date_updated TIMESTAMP, ordinal INT, update_id INT, deleted BOOLEAN, old_status_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS subject CASCADE; CREATE TABLE subject (subject_id INT PRIMARY KEY, date_created TIMESTAMP, date_of_birth DATE, date_updated TIMESTAMP, dob_collected BOOLEAN, gender VARCHAR(255), status_id INT, father_id INT, mother_id INT, unique_identifier VARCHAR(255), update_id INT, owner_id INT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS audit_event CASCADE; CREATE TABLE audit_event (AUDIT_ID INT PRIMARY KEY, AUDIT_DATE TIMESTAMP, AUDIT_TABLE VARCHAR(255), USER_ID INT, ENTITY_ID INT, REASON_FOR_CHANGE VARCHAR(255), ACTION_MESSAGE VARCHAR(255))");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS audit_event_audit_id_seq; CREATE SEQUENCE audit_event_audit_id_seq");
        jdbcTemplate.execute("DROP TABLE IF EXISTS audit_log_event CASCADE; CREATE TABLE audit_log_event (audit_id INT PRIMARY KEY, audit_date TIMESTAMP, audit_log_event_type_id INT, audit_table VARCHAR(255), chain_hash VARCHAR(255), entity_id INT, entity_name VARCHAR(255), event_crf_id INT, event_crf_version_id INT, new_value VARCHAR(255), old_value VARCHAR(255), reason_for_change VARCHAR(255), study_event_id INT, user_id INT)");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS audit_log_event_audit_id_seq; CREATE SEQUENCE audit_log_event_audit_id_seq");

        // We will insert a dummy record into the staging map and then call commit
        // This validates the PostgreSQL-specific ON CONFLICT DO NOTHING upsert syntax
        
        String recordId = "test-record-1";
        String payload = "{\"subject_id\":\"sub1\",\"event_id\":\"ev1\",\"item_value\":\"val1\"}";
        
        interopService.validate(recordId, payload);
        
        // Before committing, we must have active field mappings.
        draftService.saveDraftWithId("field-mappings-singleton", "system", "MAPPINGS", "{\"subject_id\":\"/subject_id\",\"event_id\":\"/event_id\",\"item_value\":\"/item_value\"}");
        
        assertDoesNotThrow(() -> {
            interopService.commit(recordId);
        });
    }
}
