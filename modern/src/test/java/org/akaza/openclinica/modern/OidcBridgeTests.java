package org.akaza.openclinica.modern;

import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.modern.filter.LegacyModernContextBridgeFilter;
import org.akaza.openclinica.modern.security.TenantContext;
import org.akaza.openclinica.repository.UnifiedRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class OidcBridgeTests {

    private DataSource dataSource;
    private UnifiedRepository unifiedRepository;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;
    private StringWriter responseOutput;

    @BeforeEach
    public void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        unifiedRepository = mock(UnifiedRepository.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        responseOutput = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseOutput));

        java.sql.Connection conn = mock(java.sql.Connection.class);
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        SecurityContextHolder.clearContext();
        TenantContext.setCurrentTenant(null);
        TenantContext.setBypass(false);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.setCurrentTenant(null);
        TenantContext.setBypass(false);
    }

    private LegacyModernContextBridgeFilter createFilter() {
        return new LegacyModernContextBridgeFilter(dataSource, unifiedRepository) {
            @Override
            protected void provisionOrUpdateUser(String username, Map<String, Object> claims) throws Exception {
                // No-op in unit tests to avoid complex database dependency
            }
        };
    }

    @Test
    public void testFilterWithMissingUserIdentifierClaim() throws Exception {
        LegacyModernContextBridgeFilter filter = createFilter();

        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", "tenant-a");
        // "user_id" (the default claim) is omitted entirely

        Jwt jwt = new Jwt("token123", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "none"), claims);
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, null, "service_account");

        SecurityContextHolder.getContext().setAuthentication(token);

        when(request.getRequestURI()).thenReturn("/api/test");

        filter.doFilter(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(responseOutput.toString().contains("Missing user identifier claim"));
    }

    @Test
    public void testFilterWithValidUserIdentifierButNoDatabaseUser() throws Exception {
        LegacyModernContextBridgeFilter filter = createFilter();

        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", "tenant-a");
        claims.put("user_id", "john_sso");
        claims.put("active_study_id", 42);

        Jwt jwt = new Jwt("token123", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "none"), claims);
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, null, "john_sso");

        SecurityContextHolder.getContext().setAuthentication(token);

        when(request.getRequestURI()).thenReturn("/api/test");
        when(unifiedRepository.getUserAccountBeanByUserName("john_sso")).thenReturn(null);

        doAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.getCurrentTenant());
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
    }

    @Test
    public void testFilterWithValidUserIdentifierAndDatabaseUser() throws Exception {
        LegacyModernContextBridgeFilter filter = createFilter();

        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", "tenant-a");
        claims.put("user_id", "existing_user");
        claims.put("active_study_id", 99);

        Jwt jwt = new Jwt("token123", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "none"), claims);
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, null, "existing_user");

        SecurityContextHolder.getContext().setAuthentication(token);

        UserAccountBean userBean = new UserAccountBean();
        userBean.setName("existing_user");
        userBean.setId(100);

        when(request.getRequestURI()).thenReturn("/api/test");
        when(unifiedRepository.getUserAccountBeanByUserName("existing_user")).thenReturn(userBean);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        assertEquals(99, userBean.getActiveStudyId());
    }

    @Test
    public void testFilterWithCustomClaimMapping() throws Exception {
        System.setProperty("OIDC_USER_IDENTIFIER_CLAIM", "custom_username,sub");
        try {
            LegacyModernContextBridgeFilter filter = createFilter();

            Map<String, Object> claims = new HashMap<>();
            claims.put("tenant_id", "tenant-a");
            claims.put("custom_username", "custom_mapped_user");

            Jwt jwt = new Jwt("token123", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "none"), claims);
            JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, null, "custom_mapped_user");

            SecurityContextHolder.getContext().setAuthentication(token);

            UserAccountBean userBean = new UserAccountBean();
            userBean.setName("custom_mapped_user");
            userBean.setId(101);

            when(request.getRequestURI()).thenReturn("/api/test");
            when(unifiedRepository.getUserAccountBeanByUserName("custom_mapped_user")).thenReturn(userBean);

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(any(), any());
            assertEquals("custom_mapped_user", userBean.getName());
        } finally {
            System.clearProperty("OIDC_USER_IDENTIFIER_CLAIM");
        }
    }

    @Test
    public void testFilterWithSubClaimFallback() throws Exception {
        LegacyModernContextBridgeFilter filter = createFilter();

        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", "tenant-a");
        claims.put("sub", "keycloak_sub_user");

        Jwt jwt = new Jwt("token123", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "none"), claims);
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, null, "keycloak_sub_user");

        SecurityContextHolder.getContext().setAuthentication(token);

        UserAccountBean userBean = new UserAccountBean();
        userBean.setName("keycloak_sub_user");
        userBean.setId(102);

        when(request.getRequestURI()).thenReturn("/api/test");
        when(unifiedRepository.getUserAccountBeanByUserName("keycloak_sub_user")).thenReturn(userBean);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        assertEquals("keycloak_sub_user", userBean.getName());
    }

    @Test
    public void testFilterWithClientIdProtection() throws Exception {
        LegacyModernContextBridgeFilter filter = createFilter();

        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", "tenant-a");
        claims.put("client_id", "client_app_abc");
        // User claims are missing/blank or match client_id
        claims.put("user_id", "client_app_abc");

        Jwt jwt = new Jwt("token123", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "none"), claims);
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, null, "client_app_abc");

        SecurityContextHolder.getContext().setAuthentication(token);

        when(request.getRequestURI()).thenReturn("/api/test");

        filter.doFilter(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(responseOutput.toString().contains("Invalid user identifier") || responseOutput.toString().contains("Missing user identifier claim"));
    }

    @Test
    public void testTraditionalLocalLoginMockSession() throws Exception {
        LegacyModernContextBridgeFilter filter = createFilter();

        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("local_user", "password", java.util.Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(token);

        UserAccountBean userBean = new UserAccountBean();
        userBean.setName("local_user");
        userBean.setId(45);
        userBean.setActiveStudyId(12);

        StudyBean studyBean = new StudyBean();
        studyBean.setId(12);

        when(request.getRequestURI()).thenReturn("/api/test");
        when(unifiedRepository.getUserAccountBeanByUserName("local_user")).thenReturn(userBean);
        when(unifiedRepository.getStudyBean(12)).thenReturn(studyBean);

        HttpSession mockSession = mock(HttpSession.class);
        when(request.getSession(anyBoolean())).thenReturn(mockSession);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        verify(mockSession).setAttribute(eq("userBean"), eq(userBean));
        verify(mockSession).setAttribute(eq("studyBean"), eq(studyBean));
    }

    @Test
    public void testAuthControllerConfigEndpoint() {
        AuthController authController = new AuthController(null, null, null);
        Map<String, Object> config = authController.config();

        assertNotNull(config);
        assertTrue(config.containsKey("oidcProvider"));
        assertTrue(config.containsKey("clientId"));
        assertTrue(config.containsKey("authorizationEndpoint"));
        assertTrue(config.containsKey("scopes"));
    }
}
