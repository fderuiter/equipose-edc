package org.akaza.openclinica.modern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntegrationTests extends AbstractIntegrationTest {

    @Test
    public void testOdmExport() throws Exception {
        mockMvc.perform(get("/api/odm/export").param("studyOid", "S1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(result -> {
                    String content = result.getResponse().getContentAsString();
                    assertTrue(content.contains("<ODM"));
                    assertTrue(content.contains("<Study>"));
                    assertTrue(content.contains("<ClinicalData>"));
                });
    }

    @Test
    public void testOdmImportWritesToDatabase() throws Exception {
        String xmlPayload = "<?xml version=\"1.0\"?><ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\" FileType=\"Snapshot\" FileOID=\"1\" CreationDateTime=\"2023-01-01T00:00:00\"><ClinicalData StudyOID=\"S1\" MetaDataVersionOID=\"v1\"><SubjectData SubjectKey=\"SS1\"/></ClinicalData></ODM>";
        
        long countBefore = getCount("clinical_records");
        
        mockMvc.perform(post("/api/odm/import")
                .contentType(MediaType.APPLICATION_XML)
                .content(xmlPayload))
                .andExpect(status().isOk());
                
        long countAfter = getCount("clinical_records");
        assertEquals(countBefore + 1, countAfter, "Clinical records count should increase by 1");
    }

    @Test
    public void testDdeValidationWorkflow() throws Exception {
        String subjectOid = "SUBJ_1";
        String itemOid = "ITEM_1";
        
        // 1. Initial entry
        mockMvc.perform(post("/api/dde/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"study_id\":1, \"subjectOid\":\"" + subjectOid + "\", \"itemOid\":\"" + itemOid + "\", \"value\":\"A\"}"))
                .andExpect(status().isOk());
                
        // 2. Mismatched double entry without override
        mockMvc.perform(post("/api/dde/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"study_id\":1, \"subjectOid\":\"" + subjectOid + "\", \"itemOid\":\"" + itemOid + "\", \"value\":\"B\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().string("Mismatch detected. Provide override to force save."));
                
        // 3. Mismatched double entry with override
        mockMvc.perform(post("/api/dde/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"study_id\":1, \"subjectOid\":\"" + subjectOid + "\", \"itemOid\":\"" + itemOid + "\", \"value\":\"B\", \"override\":\"true\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("Verification complete"));
                
        // 4. Matched double entry
        String subjectOid2 = "SUBJ_2";
        mockMvc.perform(post("/api/dde/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"study_id\":1, \"subjectOid\":\"" + subjectOid2 + "\", \"itemOid\":\"" + itemOid + "\", \"value\":\"C\"}"))
                .andExpect(status().isOk());
                
        mockMvc.perform(post("/api/dde/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"study_id\":1, \"subjectOid\":\"" + subjectOid2 + "\", \"itemOid\":\"" + itemOid + "\", \"value\":\"C\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("Verification complete"));
    }

    @Test
    public void testDdeValidationMissingStudyIdReturns400() throws Exception {
        String subjectOid = "SUBJ_NO_STUDY";
        String itemOid = "ITEM_NO_STUDY";

        mockMvc.perform(post("/api/dde/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectOid\":\"" + subjectOid + "\", \"itemOid\":\"" + itemOid + "\", \"value\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testDdeValidationUnauthorizedStudyIdReturns403() throws Exception {
        try {
            org.akaza.openclinica.modern.security.TenantContext.setCurrentStudy(1);

            mockMvc.perform(post("/api/dde/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"study_id\":999, \"subjectOid\":\"SUBJ_UNAUTH\", \"itemOid\":\"ITEM_UNAUTH\", \"value\":\"X\"}"))
                    .andExpect(status().isForbidden());
        } finally {
            org.akaza.openclinica.modern.security.TenantContext.clear();
        }
    }

    @Test
    public void testDdeValidationStudyAndTenantIsolation() throws Exception {
        String subjectOid = "SUBJ_ISO";
        String itemOid = "ITEM_ISO";

        // Initial entry for Study 1
        mockMvc.perform(post("/api/dde/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"study_id\":1, \"subjectOid\":\"" + subjectOid + "\", \"itemOid\":\"" + itemOid + "\", \"value\":\"VAL_STUDY_1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("First entry saved"));

        // Initial entry for Study 2 with same subjectOid and itemOid should also be "First entry saved" (isolated)
        mockMvc.perform(post("/api/dde/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"study_id\":2, \"subjectOid\":\"" + subjectOid + "\", \"itemOid\":\"" + itemOid + "\", \"value\":\"VAL_STUDY_2\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("First entry saved"));
    }

    private long getCount(String tableName) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count != null ? count : 0;
    }
}
