package org.akaza.openclinica.modern;

import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.modern.filter.LegacyModernContextBridgeFilter;
import org.akaza.openclinica.repository.UnifiedRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class LegacyModernContextBridgeFilterTest {

    private DataSource dataSource;
    private UnifiedRepository unifiedRepository;
    private LegacyModernContextBridgeFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    public void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        unifiedRepository = mock(UnifiedRepository.class);
        filter = new LegacyModernContextBridgeFilter(dataSource, unifiedRepository);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        java.io.PrintWriter writer = mock(java.io.PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void testDoFilter_JwtAuthentication_Success() throws Exception {
        String username = "testuser";
        JwtAuthenticationToken jwtAuth = mock(JwtAuthenticationToken.class);
        when(jwtAuth.getName()).thenReturn(username);
        when(jwtAuth.isAuthenticated()).thenReturn(true);
        when(jwtAuth.getPrincipal()).thenReturn(username);

        Map<String, Object> claims = new HashMap<>();
        claims.put("active_study_id", 45L);
        claims.put("tenant_id", "tenant-a");
        claims.put("user_id", username);
        when(jwtAuth.getTokenAttributes()).thenReturn(claims);

        UserAccountBean userBean = new UserAccountBean();
        userBean.setId(10);
        userBean.setName(username);
        userBean.setActiveStudyId(1);

        when(unifiedRepository.getUserAccountBeanByUserName(username)).thenReturn(userBean);

        StudyBean studyBean = new StudyBean();
        studyBean.setId(45);
        when(unifiedRepository.getStudyBean(45)).thenReturn(studyBean);

        SecurityContextHolder.getContext().setAuthentication(jwtAuth);

        filter.doFilter(request, response, chain);

        assertEquals(45, userBean.getActiveStudyId());
        verify(chain).doFilter(any(HttpServletRequest.class), eq(response));
        verify(response, never()).sendError(anyInt(), anyString());
    }

    @Test
    public void testDoFilter_JwtAuthentication_Success_IntegerClaim() throws Exception {
        String username = "testuser_int";
        JwtAuthenticationToken jwtAuth = mock(JwtAuthenticationToken.class);
        when(jwtAuth.getName()).thenReturn(username);
        when(jwtAuth.isAuthenticated()).thenReturn(true);
        when(jwtAuth.getPrincipal()).thenReturn(username);

        Map<String, Object> claims = new HashMap<>();
        claims.put("active_study_id", 45); // Integer claim, not Long
        when(jwtAuth.getTokenAttributes()).thenReturn(claims);

        UserAccountBean userBean = new UserAccountBean();
        userBean.setId(10);
        userBean.setName(username);
        userBean.setActiveStudyId(1);

        when(unifiedRepository.getUserAccountBeanByUserName(username)).thenReturn(userBean);

        StudyBean studyBean = new StudyBean();
        studyBean.setId(45);
        when(unifiedRepository.getStudyBean(45)).thenReturn(studyBean);

        SecurityContextHolder.getContext().setAuthentication(jwtAuth);

        filter.doFilter(request, response, chain);

        assertEquals(45, userBean.getActiveStudyId());
        verify(chain).doFilter(any(HttpServletRequest.class), eq(response));
        verify(response, never()).sendError(anyInt(), anyString());
    }

    @Test
    public void testDoFilter_JwtAuthentication_UserNotFound() throws Exception {
        String username = "missinguser";
        JwtAuthenticationToken jwtAuth = mock(JwtAuthenticationToken.class);
        when(jwtAuth.getName()).thenReturn(username);
        when(jwtAuth.isAuthenticated()).thenReturn(true);
        when(jwtAuth.getPrincipal()).thenReturn(username);

        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", "tenant-a");
        claims.put("user_id", username);
        when(jwtAuth.getTokenAttributes()).thenReturn(claims);
        when(unifiedRepository.getUserAccountBeanByUserName(username)).thenReturn(null);

        SecurityContextHolder.getContext().setAuthentication(jwtAuth);

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized - User account not found");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    public void testDoFilter_JwtAuthentication_UserWithZeroId() throws Exception {
        String username = "zero_id_user";
        JwtAuthenticationToken jwtAuth = mock(JwtAuthenticationToken.class);
        when(jwtAuth.getName()).thenReturn(username);
        when(jwtAuth.isAuthenticated()).thenReturn(true);
        when(jwtAuth.getPrincipal()).thenReturn(username);

        UserAccountBean userBean = new UserAccountBean();
        userBean.setId(0); // Invalid user ID

        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", "tenant-a");
        claims.put("user_id", username);
        when(jwtAuth.getTokenAttributes()).thenReturn(claims);
        when(unifiedRepository.getUserAccountBeanByUserName(username)).thenReturn(userBean);

        SecurityContextHolder.getContext().setAuthentication(jwtAuth);

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized - User account not found");
        verify(chain, never()).doFilter(any(), any());
    }
}
