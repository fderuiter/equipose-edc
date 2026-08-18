package org.akaza.openclinica.modern;

import org.akaza.openclinica.modern.security.TenantContext;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.modern.security.StudySecurityValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = true)
public class TenantQueryIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StudySecurityValidator studySecurityValidator;

    @BeforeEach
    public void setUp() {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS study CASCADE");
        } catch (Exception e) {}
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS user_account CASCADE");
        } catch (Exception e) {}
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS study_user_role CASCADE");
        } catch (Exception e) {}
        
        try {
            jdbcTemplate.execute("CREATE TABLE study (study_id INTEGER PRIMARY KEY, name VARCHAR(255), unique_identifier VARCHAR(255), oc_oid VARCHAR(255), tenant_id VARCHAR(255))");
        } catch (Exception e) {}
        
        try {
            jdbcTemplate.execute("CREATE TABLE user_account (" +
                    "user_id INTEGER PRIMARY KEY, " +
                    "user_name VARCHAR(255), " +
                    "passwd VARCHAR(255), " +
                    "first_name VARCHAR(255), " +
                    "last_name VARCHAR(255), " +
                    "email VARCHAR(255), " +
                    "status_id INTEGER, " +
                    "institutional_affiliation VARCHAR(255), " +
                    "active_study INTEGER, " +
                    "owner_id INTEGER, " +
                    "date_created DATE, " +
                    "date_updated DATE, " +
                    "date_lastvisit TIMESTAMP, " +
                    "passwd_timestamp DATE, " +
                    "passwd_challenge_question VARCHAR(255), " +
                    "passwd_challenge_answer VARCHAR(255), " +
                    "phone VARCHAR(255), " +
                    "user_type_id INTEGER, " +
                    "update_id INTEGER, " +
                    "enabled BOOLEAN, " +
                    "account_non_locked BOOLEAN, " +
                    "lock_counter INTEGER, " +
                    "run_webservices BOOLEAN, " +
                    "access_code VARCHAR(255), " +
                    "time_zone VARCHAR(255), " +
                    "enable_api_key BOOLEAN, " +
                    "api_key VARCHAR(255)" +
                    ")");
        } catch (Exception e) {}

        try {
            jdbcTemplate.execute("CREATE TABLE study_user_role (role_name VARCHAR(255), study_id INT, status_id INT, owner_id INT, date_created DATE, date_updated DATE, update_id INT, user_name VARCHAR(255))");
        } catch (Exception e) {}

        try {
            jdbcTemplate.execute("DELETE FROM study");
        } catch (Exception e) {}
        try {
            jdbcTemplate.execute("DELETE FROM user_account");
        } catch (Exception e) {}
        try {
            jdbcTemplate.execute("DELETE FROM study_user_role");
        } catch (Exception e) {}

        // Insert mock user account for AuthController to resolve
        try {
            jdbcTemplate.execute("INSERT INTO user_account (" +
                    "user_id, user_name, passwd, first_name, last_name, email, status_id, institutional_affiliation, active_study, owner_id, date_created, date_updated, date_lastvisit, passwd_timestamp, passwd_challenge_question, passwd_challenge_answer, phone, user_type_id, update_id, enabled, account_non_locked, lock_counter, run_webservices, access_code, time_zone, enable_api_key, api_key" +
                    ") VALUES (1, 'service_account', 'password', 'Service', 'Account', 'service@openclinica.org', 1, 'OpenClinica', 0, 1, CURRENT_DATE, CURRENT_DATE, CURRENT_TIMESTAMP, CURRENT_DATE, 'Q', 'A', '123', 1, 1, TRUE, TRUE, 0, FALSE, 'doe', 'UTC', FALSE, 'key')");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @org.junit.jupiter.api.AfterEach
    public void tearDown() {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void testTenantWhitelistAndAuthentication() throws Exception {
        // 1. Get token with valid whitelisted tenant ID
        String validToken = mockMvc.perform(post("/api/auth/token")
                .param("tenant_id", "tenant-a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 2. Get token with invalid/non-whitelisted tenant ID
        String invalidToken = mockMvc.perform(post("/api/auth/token")
                .param("tenant_id", "unauthorized-tenant"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 3. Get token with missing tenant ID
        String missingToken = mockMvc.perform(post("/api/auth/token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Accessing clinical odm export (authenticated endpoint) with valid token should succeed (200 OK)
        mockMvc.perform(get("/api/odm/export")
                .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk());

        // Accessing with invalid whitelisted token should be rejected (403 Forbidden)
        mockMvc.perform(get("/api/odm/export")
                .header("Authorization", "Bearer " + invalidToken))
                .andExpect(status().isForbidden());

        // Accessing with missing tenant ID token should be rejected (403 Forbidden)
        mockMvc.perform(get("/api/odm/export")
                .header("Authorization", "Bearer " + missingToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testLogicalTenantQueryIsolationAndTagging() {
        // Set tenant context to tenant-a
        TenantContext.setCurrentTenant("tenant-a");

        // Insert study - should be dynamically tagged with 'tenant-a'
        jdbcTemplate.update("INSERT INTO study (study_id, name, unique_identifier, oc_oid) VALUES (101, 'Study A', 'UID-A', 'OID-A')");

        // Verify it was correctly tagged in the database (querying globally with bypass)
        TenantContext.setBypass(true);
        Map<String, Object> record = jdbcTemplate.queryForMap("SELECT * FROM study WHERE study_id = 101");
        assertEquals("tenant-a", record.get("tenant_id"));
        TenantContext.setBypass(false);

        // Under tenant-a context, the study should be visible
        TenantContext.setCurrentTenant("tenant-a");
        int countA = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM study", Integer.class);
        assertEquals(1, countA);

        // Under tenant-b context, the study should be completely invisible (Logical isolation)
        TenantContext.setCurrentTenant("tenant-b");
        int countB = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM study", Integer.class);
        assertEquals(0, countB);

        // Administrative tool / bypass should see across all tenants
        TenantContext.setBypass(true);
        int countBypass = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM study", Integer.class);
        assertEquals(1, countBypass);
        TenantContext.setBypass(false);
    }

    @Test
    public void testUnauthorizedResourceObscurity() throws Exception {
        // Insert study owned by tenant-a
        TenantContext.setCurrentTenant("tenant-a");
        jdbcTemplate.update("INSERT INTO study (study_id, name, unique_identifier, oc_oid) VALUES (102, 'Study A', 'UID-A', 'OID-A')");
        TenantContext.clear();

        // Attempt access to study of tenant-a under tenant-b context
        TenantContext.setCurrentTenant("tenant-b");

        // Prepare Mock Session and User
        MockHttpSession session = new MockHttpSession();
        UserAccountBean userBean = new UserAccountBean();
        userBean.setId(999);
        session.setAttribute("userBean", userBean);

        // Bind mock request attributes to current thread
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        // Ensure studySecurityValidator throws IllegalArgumentException (which triggers 404 in controller)
        assertThrows(IllegalArgumentException.class, () -> {
            studySecurityValidator.hasAdminOrCoordinatorRole("UID-A");
        });

        // Verifying that if study doesn't exist globally at all, it also throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            studySecurityValidator.hasAdminOrCoordinatorRole("NON-EXISTENT-UID");
        });
    }

    @Test
    public void testForeignStudyContextRejectionAndNoRoleProvisioning() throws Exception {
        // 1. Create study 101 under tenant-a
        jdbcTemplate.update("INSERT INTO study (study_id, name, unique_identifier, oc_oid, tenant_id) VALUES (101, 'Study Tenant A', 'UID-101', 'OID-101', 'tenant-a')");

        // 2. Create study 202 under tenant-b
        jdbcTemplate.update("INSERT INTO study (study_id, name, unique_identifier, oc_oid, tenant_id) VALUES (202, 'Study Tenant B', 'UID-202', 'OID-202', 'tenant-b')");

        // 3. Update service_account's active study to 202 (belonging to tenant-b)
        jdbcTemplate.update("UPDATE user_account SET active_study = 202 WHERE user_name = 'service_account'");

        // 4. Request token under tenant-a context (claims tenant_id=tenant-a, active_study_id=202)
        String foreignStudyToken = mockMvc.perform(post("/api/auth/token")
                .param("tenant_id", "tenant-a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 5. Attempt accessing protected resource with token claiming tenant-a but referencing study 202 (foreign tenant-b study)
        mockMvc.perform(get("/api/odm/export")
                .header("Authorization", "Bearer " + foreignStudyToken))
                .andExpect(status().isForbidden());

        // 6. Verify zero study_user_role records were created or updated for study 202 or service_account
        TenantContext.setBypass(true);
        Integer roleCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'service_account'", Integer.class);
        assertEquals(0, roleCount);
        TenantContext.setBypass(false);
    }
}
