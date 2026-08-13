package org.akaza.openclinica.modern.filter;

import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.log.LoggingConstants;
import org.akaza.openclinica.repository.UnifiedRepository;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class LegacyModernContextBridgeFilter extends OncePerRequestFilter {

    private final DataSource dataSource;
    private final UnifiedRepository unifiedRepository;

    public LegacyModernContextBridgeFilter(DataSource dataSource, UnifiedRepository unifiedRepository) {
        this.dataSource = dataSource;
        this.unifiedRepository = unifiedRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        logger.warn("DEBUG FILTER STARTED: request URI = " + request.getRequestURI() + ", auth = " + SecurityContextHolder.getContext().getAuthentication());
        
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        boolean mdcSet = false;
        HttpServletRequest requestToChain = request;

        try {
            if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
                String username = authentication.getName();
                logger.warn("DEBUG AUTH CLASS: " + authentication.getClass().getName() + ", username: " + username);
                
                if ("service_account".equals(username)) {
                    org.akaza.openclinica.modern.security.TenantContext.setBypass(true);
                }

                UserAccountBean userBean = null;
                if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) {
                    org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth = (org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) authentication;
                    Map<String, Object> claims = jwtAuth.getTokenAttributes();
                    
                    // Validate tenant ID claim
                    Object tenantIdObj = claims.get("tenant_id");
                    logger.warn("DEBUG JWT: tenantIdObj = " + tenantIdObj + ", claims = " + claims);
                    if (tenantIdObj == null) {
                        logger.warn("DEBUG JWT: tenantIdObj is null, rejecting with 403");
                        logger.error("SECURITY ALERT: User identity token lacks a valid tenant identifier.");
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.getWriter().write("Access Denied: Missing tenant identifier");
                        return;
                    }
                    String tenantId = String.valueOf(tenantIdObj);
                    boolean whitelisted = org.akaza.openclinica.modern.security.TenantContext.isWhitelisted(tenantId);
                    logger.warn("DEBUG JWT: tenantId = " + tenantId + ", whitelisted = " + whitelisted);
                    if (!whitelisted) {
                        logger.warn("DEBUG JWT: tenantId is not whitelisted, rejecting with 403");
                        logger.error("SECURITY ALERT: Tenant identifier " + tenantId + " is not whitelisted.");
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.getWriter().write("Access Denied: Tenant not whitelisted");
                        return;
                    }
                    org.akaza.openclinica.modern.security.TenantContext.setCurrentTenant(tenantId);

                    userBean = new UserAccountBean();
                    userBean.setName(username);
                    if (claims.containsKey("user_id")) {
                        userBean.setId(((Long) claims.get("user_id")).intValue());
                    }
                    if (claims.containsKey("active_study_id")) {
                        userBean.setActiveStudyId(((Long) claims.get("active_study_id")).intValue());
                    }
                } else {
                    userBean = unifiedRepository.getUserAccountBeanByUserName(username);
                }
                
                if (userBean != null) {
                    if (userBean.isSysAdmin()) {
                        org.akaza.openclinica.modern.security.TenantContext.setBypass(true);
                    }
                    if (userBean.getId() > 0) {
                        requestToChain = new StatelessSessionRequestWrapper(request);
                        HttpSession session = requestToChain.getSession(true);
                        session.setAttribute("userBean", userBean);
                        
                        if (userBean.getActiveStudyId() > 0) {
                            StudyBean studyBean = unifiedRepository.getStudyBean(userBean.getActiveStudyId());
                            if (studyBean != null && studyBean.getId() > 0) {
                                session.setAttribute("studyBean", studyBean);
                                session.setAttribute("study", studyBean);
                            }
                        }
                        
                        MDC.put(LoggingConstants.USERNAME, username);
                        mdcSet = true;
                    }
                }
            }

            filterChain.doFilter(requestToChain, response);
        } finally {
            if (mdcSet) {
                MDC.remove(LoggingConstants.USERNAME);
            }
            org.akaza.openclinica.modern.security.TenantContext.clear();
        }
    }

    private static class StatelessSessionRequestWrapper extends HttpServletRequestWrapper {
        private HttpSession statelessSession;

        public StatelessSessionRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public HttpSession getSession(boolean create) {
            if (statelessSession == null && create) {
                statelessSession = createStatelessSession(super.getSession(false));
            } else if (statelessSession == null && !create) {
                return super.getSession(false);
            }
            return statelessSession;
        }

        @Override
        public HttpSession getSession() {
            return getSession(true);
        }

        private HttpSession createStatelessSession(HttpSession originalSession) {
            Map<String, Object> attributes = new HashMap<>();
            return (HttpSession) Proxy.newProxyInstance(
                    HttpSession.class.getClassLoader(),
                    new Class<?>[]{HttpSession.class},
                    (proxy, method, args) -> {
                        String methodName = method.getName();
                        if ("getAttribute".equals(methodName)) {
                            Object val = attributes.get(args[0]);
                            if (val == null && originalSession != null) {
                                return originalSession.getAttribute((String) args[0]);
                            }
                            return val;
                        } else if ("setAttribute".equals(methodName)) {
                            attributes.put((String) args[0], args[1]);
                            return null;
                        } else if ("removeAttribute".equals(methodName)) {
                            attributes.remove(args[0]);
                            return null;
                        } else if ("getAttributeNames".equals(methodName)) {
                            return Collections.enumeration(attributes.keySet());
                        } else if (originalSession != null) {
                            return method.invoke(originalSession, args);
                        }
                        
                        if ("getId".equals(methodName)) return "stateless-session";
                        if ("getCreationTime".equals(methodName)) return System.currentTimeMillis();
                        if ("getLastAccessedTime".equals(methodName)) return System.currentTimeMillis();
                        if ("getServletContext".equals(methodName)) return super.getServletContext();
                        
                        if (method.getReturnType().equals(Void.TYPE)) {
                            return null;
                        }
                        return null;
                    }
            );
        }
    }
}
