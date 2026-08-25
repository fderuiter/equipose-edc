package org.akaza.openclinica.modern.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TenantContextAstRewriteTest {

    @BeforeEach
    public void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    public void tearDown() {
        TenantContext.clear();
    }

    @Test
    public void testSimpleSelectRewriting() {
        TenantContext.setCurrentTenant("tenant-a");
        String sql = "SELECT * FROM study";
        String rewritten = TenantContext.rewriteSql(sql);
        assertTrue(rewritten.toUpperCase().contains("STUDY.TENANT_ID = 'TENANT-A'") || rewritten.toUpperCase().contains("TENANT_ID = 'TENANT-A'"));
        assertFalse(rewritten.contains("(SELECT * FROM study"));
    }

    @Test
    public void testTableAliasPreservation() {
        TenantContext.setCurrentTenant("tenant-a");
        String sql = "SELECT s.study_id, s.name FROM study s WHERE s.study_id = 101";
        String rewritten = TenantContext.rewriteSql(sql);
        assertTrue(rewritten.contains("s.study_id = 101"));
        assertTrue(rewritten.contains("S.TENANT_ID = 'tenant-a'") || rewritten.contains("s.tenant_id = 'tenant-a'"));
    }

    @Test
    public void testQualifiedColumnNamesPreservation() {
        TenantContext.setCurrentTenant("tenant-beta");
        String sql = "SELECT study.name, study.oc_oid FROM study WHERE study.study_id = 42";
        String rewritten = TenantContext.rewriteSql(sql);
        assertTrue(rewritten.contains("study.name"));
        assertTrue(rewritten.contains("study.oc_oid"));
        assertTrue(rewritten.contains("study.study_id = 42"));
        assertTrue(rewritten.toUpperCase().contains("STUDY.TENANT_ID = 'TENANT-BETA'"));
    }

    @Test
    public void testInnerJoinQuery() {
        TenantContext.setCurrentTenant("tenant-1");
        String sql = "SELECT s.name, r.user_name FROM study s INNER JOIN study_user_role r ON s.study_id = r.study_id WHERE r.status_id = 1";
        String rewritten = TenantContext.rewriteSql(sql);
        assertTrue(rewritten.contains("s.name"));
        assertTrue(rewritten.contains("r.user_name"));
        assertTrue(rewritten.contains("r.status_id = 1"));
        assertTrue(rewritten.contains("s.tenant_id = 'tenant-1'") || rewritten.contains("S.TENANT_ID = 'tenant-1'"));
    }

    @Test
    public void testLeftJoinQuery() {
        TenantContext.setCurrentTenant("tenant-c");
        String sql = "SELECT u.user_name, s.name FROM user_account u LEFT JOIN study s ON u.active_study = s.study_id";
        String rewritten = TenantContext.rewriteSql(sql);
        assertTrue(rewritten.contains("u.user_name"));
        assertTrue(rewritten.contains("s.name"));
        // Tenant filter should be in LEFT JOIN ON clause
        assertTrue(rewritten.contains("s.tenant_id = 'tenant-c'") || rewritten.contains("S.TENANT_ID = 'tenant-c'"));
    }

    @Test
    public void testRightJoinQuery() {
        TenantContext.setCurrentTenant("tenant-alpha");
        String sql = "SELECT s.name, u.user_name FROM study s RIGHT JOIN user_account u ON s.study_id = u.active_study";
        String rewritten = TenantContext.rewriteSql(sql);
        assertTrue(rewritten.contains("s.tenant_id = 'tenant-alpha'") || rewritten.contains("S.TENANT_ID = 'tenant-alpha'"));
    }

    @Test
    public void testMissingTenantContextThrowsException() {
        TenantContext.clear();
        String sql = "SELECT * FROM study WHERE study_id = 1";
        assertThrows(IllegalStateException.class, () -> {
            TenantContext.rewriteSql(sql);
        });
    }

    @Test
    public void testBypassModeSkipsRewriting() {
        TenantContext.clear();
        TenantContext.setBypass(true);
        String sql = "SELECT * FROM study WHERE study_id = 1";
        String rewritten = TenantContext.rewriteSql(sql);
        assertEquals(sql, rewritten);
    }

    @Test
    public void testNonTenantTableQueryUnmodified() {
        TenantContext.setCurrentTenant("tenant-a");
        String sql = "SELECT user_id, user_name FROM user_account WHERE enabled = true";
        String rewritten = TenantContext.rewriteSql(sql);
        assertEquals(sql, rewritten);
    }

    @Test
    public void testTenantLiteralEscaping() {
        TenantContext.setCurrentTenant("tenant's-id");
        String sql = "SELECT * FROM study s WHERE s.study_id = 5";
        String rewritten = TenantContext.rewriteSql(sql);
        assertTrue(rewritten.contains("tenant''s-id"));
    }

    @Test
    public void testSubqueryInFromClause() {
        TenantContext.setCurrentTenant("tenant-a");
        String sql = "SELECT sub.name FROM (SELECT study_id, name FROM study) sub WHERE sub.study_id = 10";
        String rewritten = TenantContext.rewriteSql(sql);
        assertTrue(rewritten.contains("tenant-a"));
    }

    @Test
    public void testUnionQuery() {
        TenantContext.setCurrentTenant("tenant-a");
        String sql = "SELECT study_id, name FROM study WHERE study_id = 1 UNION SELECT study_id, name FROM study WHERE study_id = 2";
        String rewritten = TenantContext.rewriteSql(sql);
        assertTrue(rewritten.contains("tenant-a"));
    }
}
