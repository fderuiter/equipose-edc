package org.akaza.openclinica.modern.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.repository.UnifiedRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class LegacyModernContextBridgeFilterTest {

    private DataSource dataSource;
    private UnifiedRepository unifiedRepository;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private HttpSession session;
    private Connection connection;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;

    @BeforeEach
    public void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        unifiedRepository = mock(UnifiedRepository.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        session = mock(HttpSession.class);
        connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(request.getSession(anyBoolean())).thenReturn(session);
        when(request.getSession()).thenReturn(session);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void testBypassForServiceAccount() throws Exception {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("service_account");
        when(auth.getPrincipal()).thenReturn("service_account");

        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);

        when(unifiedRepository.getUserAccountBeanByUserName("service_account")).thenReturn(null);

        LegacyModernContextBridgeFilter filter = new LegacyModernContextBridgeFilter(dataSource, unifiedRepository);
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), eq(response));
    }

    @Test
    public void testProvisionNewUserOAuth2() throws Exception {
        OAuth2User oauth2User = mock(OAuth2User.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tenant_id", "tenant-a");
        attributes.put("given_name", "John");
        attributes.put("family_name", "Doe");
        attributes.put("email", "john.doe@example.com");
        attributes.put("affiliation", "Harvard");
        attributes.put("system_role", "coordinator");
        attributes.put("active_study_id", 101);
        when(oauth2User.getAttributes()).thenReturn(attributes);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("jdoe");
        when(auth.getPrincipal()).thenReturn(oauth2User);

        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);

        // Mock database sequence / queries for provisioning
        // 1. isStudyIdValid check (SELECT 1 FROM study WHERE study_id = ?)
        // 2. getUserIdByUsername (SELECT user_id FROM user_account WHERE user_name = ?) -> return null (new user)
        // 3. getNextUserId (SELECT nextval('user_account_user_id_seq'))
        // 4. studyUserRoleExists (SELECT 1 FROM study_user_role WHERE user_name = ? AND study_id = ?) -> return false
        
        ResultSet rsStudyValid = mock(ResultSet.class);
        when(rsStudyValid.next()).thenReturn(true); // study valid

        ResultSet rsUserExist = mock(ResultSet.class);
        when(rsUserExist.next()).thenReturn(false); // new user

        ResultSet rsSeq = mock(ResultSet.class);
        when(rsSeq.next()).thenReturn(true);
        when(rsSeq.getInt(1)).thenReturn(42); // newUserId = 42

        ResultSet rsRoleExist = mock(ResultSet.class);
        when(rsRoleExist.next()).thenReturn(false);

        PreparedStatement psStudyValid = mock(PreparedStatement.class);
        when(psStudyValid.executeQuery()).thenReturn(rsStudyValid);

        PreparedStatement psUserExist = mock(PreparedStatement.class);
        when(psUserExist.executeQuery()).thenReturn(rsUserExist);

        PreparedStatement psSeq = mock(PreparedStatement.class);
        when(psSeq.executeQuery()).thenReturn(rsSeq);

        PreparedStatement psInsertUser = mock(PreparedStatement.class);
        PreparedStatement psRoleExistStmt = mock(PreparedStatement.class);
        when(psRoleExistStmt.executeQuery()).thenReturn(rsRoleExist);
        PreparedStatement psInsertRole = mock(PreparedStatement.class);

        when(connection.prepareStatement(contains("SELECT 1 FROM study WHERE study_id"))).thenReturn(psStudyValid);
        when(connection.prepareStatement(contains("SELECT user_id FROM user_account"))).thenReturn(psUserExist);
        when(connection.prepareStatement(contains("SELECT nextval('user_account_user_id_seq')"))).thenReturn(psSeq);
        when(connection.prepareStatement(contains("INSERT INTO user_account"))).thenReturn(psInsertUser);
        when(connection.prepareStatement(contains("SELECT 1 FROM study_user_role"))).thenReturn(psRoleExistStmt);
        when(connection.prepareStatement(contains("INSERT INTO study_user_role"))).thenReturn(psInsertRole);

        // Mock unifiedRepository loading user to populate session bean
        UserAccountBean uBean = new UserAccountBean();
        uBean.setName("jdoe");
        uBean.setId(42);
        uBean.setActiveStudyId(101);
        when(unifiedRepository.getUserAccountBeanByUserName("jdoe")).thenReturn(uBean);

        LegacyModernContextBridgeFilter filter = new LegacyModernContextBridgeFilter(dataSource, unifiedRepository);
        filter.doFilter(request, response, chain);

        // Verify JDBC statements
        verify(psInsertUser).setInt(1, 42);
        verify(psInsertUser).setString(2, "jdoe");
        verify(psInsertUser).setString(3, "John");
        verify(psInsertUser).setString(4, "Doe");
        verify(psInsertUser).setString(5, "john.doe@example.com");
        verify(psInsertUser).setInt(6, 101);
        verify(psInsertUser).setString(7, "Harvard");
        verify(psInsertUser).executeUpdate();

        verify(psInsertRole).setString(1, "coordinator"); // mapped from coordinator
        verify(psInsertRole).setInt(2, 101);
        verify(psInsertRole).setString(3, "jdoe");
        verify(psInsertRole).executeUpdate();

        // Verify session binding
        ArgumentCaptor<HttpServletRequest> reqCaptor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(reqCaptor.capture(), eq(response));
        
        HttpServletRequest wrappedReq = reqCaptor.getValue();
        assertNotNull(wrappedReq);
        HttpSession statelessSession = wrappedReq.getSession();
        assertNotNull(statelessSession);
        
        UserAccountBean userBean = (UserAccountBean) statelessSession.getAttribute("userBean");
        assertNotNull(userBean);
        assertEquals("jdoe", userBean.getName());
        assertEquals(42, userBean.getId());
    }

    @Test
    public void testProvisionReturningUserSaml2() throws Exception {
        Saml2AuthenticatedPrincipal samlPrincipal = mock(Saml2AuthenticatedPrincipal.class);
        Map<String, List<Object>> attrs = new HashMap<>();
        attrs.put("tenant_id", Collections.singletonList("tenant-a"));
        attrs.put("firstName", Collections.singletonList("Jane"));
        attrs.put("lastName", Collections.singletonList("Smith"));
        attrs.put("mail", Collections.singletonList("jane.smith@example.com"));
        attrs.put("organization", Collections.singletonList("Yale"));
        attrs.put("systemRole", Collections.singletonList("director"));
        attrs.put("study_id", Collections.singletonList(202));
        when(samlPrincipal.getAttributes()).thenReturn(attrs);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("jsmith");
        when(auth.getPrincipal()).thenReturn(samlPrincipal);

        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);

        // Mock database sequences for existing user update
        ResultSet rsStudyValid = mock(ResultSet.class);
        when(rsStudyValid.next()).thenReturn(true);

        ResultSet rsUserExist = mock(ResultSet.class);
        when(rsUserExist.next()).thenReturn(true);
        when(rsUserExist.getInt(1)).thenReturn(1001); // existing user ID

        ResultSet rsRoleExist = mock(ResultSet.class);
        when(rsRoleExist.next()).thenReturn(true); // study user role exists, so perform UPDATE

        PreparedStatement psStudyValid = mock(PreparedStatement.class);
        when(psStudyValid.executeQuery()).thenReturn(rsStudyValid);

        PreparedStatement psUserExist = mock(PreparedStatement.class);
        when(psUserExist.executeQuery()).thenReturn(rsUserExist);

        PreparedStatement psUpdateUser = mock(PreparedStatement.class);
        
        PreparedStatement psRoleExistStmt = mock(PreparedStatement.class);
        when(psRoleExistStmt.executeQuery()).thenReturn(rsRoleExist);
        PreparedStatement psUpdateRole = mock(PreparedStatement.class);

        when(connection.prepareStatement(contains("SELECT 1 FROM study WHERE study_id"))).thenReturn(psStudyValid);
        when(connection.prepareStatement(contains("SELECT user_id FROM user_account"))).thenReturn(psUserExist);
        when(connection.prepareStatement(contains("UPDATE user_account SET"))).thenReturn(psUpdateUser);
        when(connection.prepareStatement(contains("SELECT 1 FROM study_user_role"))).thenReturn(psRoleExistStmt);
        when(connection.prepareStatement(contains("UPDATE study_user_role SET"))).thenReturn(psUpdateRole);

        UserAccountBean uBean = new UserAccountBean();
        uBean.setName("jsmith");
        uBean.setId(1001);
        uBean.setActiveStudyId(202);
        when(unifiedRepository.getUserAccountBeanByUserName("jsmith")).thenReturn(uBean);

        LegacyModernContextBridgeFilter filter = new LegacyModernContextBridgeFilter(dataSource, unifiedRepository);
        filter.doFilter(request, response, chain);

        // Verify JDBC updates
        verify(psUpdateUser).setString(1, "Jane");
        verify(psUpdateUser).setString(2, "Smith");
        verify(psUpdateUser).setString(3, "jane.smith@example.com");
        verify(psUpdateUser).setString(4, "Yale");
        verify(psUpdateUser).setInt(5, 202);
        verify(psUpdateUser).setInt(6, 2); // default non-admin user type
        verify(psUpdateUser).setInt(7, 1001);
        verify(psUpdateUser).executeUpdate();

        verify(psUpdateRole).setString(1, "director"); // mapped from director
        verify(psUpdateRole).setString(2, "jsmith");
        verify(psUpdateRole).setInt(3, 202);
        verify(psUpdateRole).executeUpdate();

        verify(chain).doFilter(any(), eq(response));
        assertNull(org.akaza.openclinica.modern.security.TenantContext.getCurrentTenant());
    }

    @Test
    public void testProvisionExceptionFriendlyErrorRedirection() throws Exception {
        OAuth2User oauth2User = mock(OAuth2User.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tenant_id", "tenant-a");
        attributes.put("given_name", "Fail");
        attributes.put("family_name", "User");
        when(oauth2User.getAttributes()).thenReturn(attributes);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("failed_user");
        when(auth.getPrincipal()).thenReturn(oauth2User);

        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);

        // Force SQLException on database check
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("Simulated DB Connection Outage"));

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(response.getWriter()).thenReturn(pw);

        LegacyModernContextBridgeFilter filter = new LegacyModernContextBridgeFilter(dataSource, unifiedRepository);
        filter.doFilter(request, response, chain);

        // Verify SC_INTERNAL_SERVER_ERROR and custom error HTML response
        verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        verify(response).setContentType("text/html;charset=UTF-8");
        
        String htmlOutput = sw.toString();
        assertTrue(htmlOutput.contains("Authentication Error"));
        assertTrue(htmlOutput.contains("A transient database or network error occurred during your single sign-on authentication."));
        assertTrue(htmlOutput.contains("Return to Login"));

        // Verify filter chain execution was aborted
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    public void testSaml2MissingTenantIdRejected() throws Exception {
        Saml2AuthenticatedPrincipal samlPrincipal = mock(Saml2AuthenticatedPrincipal.class);
        Map<String, List<Object>> attrs = new HashMap<>();
        attrs.put("firstName", Collections.singletonList("NoTenant"));
        when(samlPrincipal.getAttributes()).thenReturn(attrs);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("notenant");
        when(auth.getPrincipal()).thenReturn(samlPrincipal);

        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(response.getWriter()).thenReturn(pw);

        LegacyModernContextBridgeFilter filter = new LegacyModernContextBridgeFilter(dataSource, unifiedRepository);
        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(sw.toString().contains("Missing tenant identifier"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    public void testLazySessionInstantiationOnWriteOnly() throws Exception {
        OAuth2User oauth2User = mock(OAuth2User.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tenant_id", "tenant-a");
        when(oauth2User.getAttributes()).thenReturn(attributes);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("lazy_user");
        when(auth.getPrincipal()).thenReturn(oauth2User);

        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);

        UserAccountBean uBean = new UserAccountBean();
        uBean.setName("lazy_user");
        uBean.setId(555);
        when(unifiedRepository.getUserAccountBeanByUserName("lazy_user")).thenReturn(uBean);

        HttpServletRequest rawRequest = mock(HttpServletRequest.class);
        HttpSession containerSession = mock(HttpSession.class);
        when(rawRequest.getSession(false)).thenReturn(null);
        when(rawRequest.getSession(true)).thenReturn(containerSession);

        LegacyModernContextBridgeFilter filter = new LegacyModernContextBridgeFilter(dataSource, unifiedRepository) {
            @Override
            protected void provisionOrUpdateUser(String username, Map<String, Object> claims) throws Exception {}
        };
        
        ArgumentCaptor<HttpServletRequest> reqCaptor = ArgumentCaptor.forClass(HttpServletRequest.class);
        filter.doFilter(rawRequest, response, chain);

        verify(chain).doFilter(reqCaptor.capture(), eq(response));
        HttpServletRequest wrappedReq = reqCaptor.getValue();
        
        // At this point, setAttribute("userBean", uBean) was called inside the filter
        // Verify containerSession was lazily instantiated via getSession(true)
        verify(rawRequest).getSession(true);
        verify(containerSession).setAttribute(eq("userBean"), eq(uBean));
    }
}
