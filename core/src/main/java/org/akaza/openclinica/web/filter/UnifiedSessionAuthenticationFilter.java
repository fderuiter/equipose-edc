package org.akaza.openclinica.web.filter;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import javax.sql.DataSource;

import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.dao.login.UserAccountDAO;
import org.akaza.openclinica.domain.datamap.AuditLogEvent;
import org.akaza.openclinica.domain.datamap.AuditLogEventType;
import org.akaza.openclinica.dao.hibernate.AuditLogEventDao;
import org.akaza.openclinica.log.LoggingConstants;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.akaza.openclinica.sdk.dto.ApiError;
import org.akaza.openclinica.sdk.dto.ApiResponse;

public class UnifiedSessionAuthenticationFilter extends OncePerRequestFilter {

    private String realm = "Protected";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AuditLogEventDao auditLogEventDao;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        String apiKeyHeader = request.getHeader("api_key");
        String remoteUser = request.getHeader("REMOTE_USER");
        String apiKey = null;

        if (authHeader != null) {
            StringTokenizer st = new StringTokenizer(authHeader);
            if (st.hasMoreTokens()) {
                String tokenType = st.nextToken();
                if (tokenType.equalsIgnoreCase("Bearer") && st.hasMoreTokens()) {
                    apiKey = st.nextToken();
                } else if (tokenType.equalsIgnoreCase("Basic")) {
                    auditApiLogin("unknown", null, false, "Deprecated Basic authentication is not supported");
                    unauthorized(response, "Deprecated Basic authentication is not supported");
                    return;
                }
            }
        }

        if (apiKey == null && apiKeyHeader != null) {
            apiKey = apiKeyHeader;
        }

        UserAccountBean userBean = null;

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            apiKey = apiKey.trim();
            UserAccountDAO userAccountDAO = new UserAccountDAO(dataSource);
            userBean = (UserAccountBean) userAccountDAO.findByApiKey(apiKey);
            if (userBean == null || userBean.getId() == 0) {
                auditApiLogin("unknown", null, false, "Bad credentials");
                unauthorized(response, "Bad credentials");
                return;
            }
            auditApiLogin(userBean.getName(), userBean, true, "Successful API Login");
        } else if (remoteUser != null && !remoteUser.trim().isEmpty()) {
            UserAccountDAO userAccountDAO = new UserAccountDAO(dataSource);
            userBean = (UserAccountBean) userAccountDAO.findByUserName(remoteUser.trim());
        }

        if (userBean != null && userBean.getId() > 0) {
            // Isolate session write operations
            HttpServletRequest requestToChain = new StatelessSessionRequestWrapper(request);
            
            OpenClinicaJdbcService jdbcService = new OpenClinicaJdbcService(dataSource);
            OpenClinicaJdbcService.establishAuthenticatedContext(requestToChain, userBean, jdbcService);
            
            MDC.put(LoggingConstants.USERNAME, userBean.getName());
            try {
                filterChain.doFilter(requestToChain, response);
            } finally {
                MDC.remove(LoggingConstants.USERNAME);
            }
            return;
        }

        // If no token/header, just proceed
        boolean mdcSet = false;
        HttpSession session = request.getSession(false);
        if (session != null) {
            UserAccountBean sessionUb = (UserAccountBean) session.getAttribute("userBean");
            if (sessionUb != null) {
                MDC.put(LoggingConstants.USERNAME, sessionUb.getName());
                mdcSet = true;
            }
        }
        
        if (!mdcSet) {
            java.security.Principal principal = request.getUserPrincipal();
            if (principal != null) {
                MDC.put(LoggingConstants.USERNAME, principal.getName());
                mdcSet = true;
            }
        }

        // Check if API endpoint without token
        String uri = request.getRequestURI();
        if (uri.startsWith(request.getContextPath() + "/pages/auth/api/")) {
            unauthorized(response);
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (mdcSet) {
                MDC.remove(LoggingConstants.USERNAME);
            }
        }
    }

    private void auditApiLogin(String username, UserAccountBean ub, boolean success, String reason) {
        AuditLogEvent auditEvent = new AuditLogEvent();
        auditEvent.setAuditDate(new Date());
        auditEvent.setAuditTable("user_account");
        auditEvent.setEntityId(ub != null ? ub.getId() : null);
        if (ub != null) {
            org.akaza.openclinica.domain.user.UserAccount ua = new org.akaza.openclinica.domain.user.UserAccount();
            ua.setUserId(ub.getId());
            auditEvent.setUserAccount(ua);
        }
        auditEvent.setEntityName(username);
        AuditLogEventType eventType = new AuditLogEventType();
        if (success) {
            eventType.setAuditLogEventTypeId(44);
        } else {
            eventType.setAuditLogEventTypeId(45);
            auditEvent.setReasonForChange(reason);
        }
        auditEvent.setAuditLogEventType(eventType);
        auditLogEventDao.saveOrUpdate(auditEvent);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer realm=\"" + realm + "\"");
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        
        ObjectMapper mapper = new ObjectMapper();
        ApiError error = new ApiError("401", message);
        ApiResponse<Object> apiResponse = new ApiResponse<>(Collections.singletonList(error));
        mapper.writeValue(response.getWriter(), apiResponse);
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        unauthorized(response, "Unauthorized");
    }
    
    private static class StatelessSessionRequestWrapper extends HttpServletRequestWrapper {
        private final HttpServletRequest realRequest;
        private HttpSession proxySession;

        public StatelessSessionRequestWrapper(HttpServletRequest request) {
            super(request);
            this.realRequest = request;
        }

        @Override
        public HttpSession getSession(boolean create) {
            if (proxySession != null) {
                return proxySession;
            }
            if (!create && realRequest.getSession(false) == null) {
                return null;
            }
            proxySession = createStatelessSessionProxy();
            return proxySession;
        }

        @Override
        public HttpSession getSession() {
            return getSession(true);
        }

        private HttpSession createStatelessSessionProxy() {
            Map<String, Object> localAttributes = new HashMap<>();
            long creationTime = System.currentTimeMillis();

            return (HttpSession) Proxy.newProxyInstance(
                    HttpSession.class.getClassLoader(),
                    new Class<?>[]{HttpSession.class},
                    (proxy, method, args) -> {
                        String methodName = method.getName();

                        if ("getAttribute".equals(methodName)) {
                            String name = (String) args[0];
                            if (localAttributes.containsKey(name)) {
                                return localAttributes.get(name);
                            }
                            HttpSession phys = realRequest.getSession(false);
                            if (phys != null) {
                                return phys.getAttribute(name);
                            }
                            return null;

                        } else if ("setAttribute".equals(methodName)) {
                            String name = (String) args[0];
                            Object value = args[1];
                            if (value == null) {
                                localAttributes.remove(name);
                                HttpSession phys = realRequest.getSession(false);
                                if (phys != null) {
                                    phys.removeAttribute(name);
                                }
                            } else {
                                localAttributes.put(name, value);
                                // Lazily instantiate container session on write
                                HttpSession phys = realRequest.getSession(true);
                                if (phys != null) {
                                    phys.setAttribute(name, value);
                                }
                            }
                            return null;

                        } else if ("removeAttribute".equals(methodName)) {
                            String name = (String) args[0];
                            localAttributes.remove(name);
                            HttpSession phys = realRequest.getSession(false);
                            if (phys != null) {
                                phys.removeAttribute(name);
                            }
                            return null;

                        } else if ("getAttributeNames".equals(methodName)) {
                            java.util.Set<String> names = new java.util.HashSet<>(localAttributes.keySet());
                            HttpSession phys = realRequest.getSession(false);
                            if (phys != null) {
                                java.util.Enumeration<String> physNames = phys.getAttributeNames();
                                while (physNames.hasMoreElements()) {
                                    names.add(physNames.nextElement());
                                }
                            }
                            return Collections.enumeration(names);

                        } else if ("getId".equals(methodName)) {
                            HttpSession phys = realRequest.getSession(false);
                            if (phys != null) {
                                return phys.getId();
                            }
                            return "stateless-session";

                        } else if ("getCreationTime".equals(methodName)) {
                            HttpSession phys = realRequest.getSession(false);
                            if (phys != null) {
                                return phys.getCreationTime();
                            }
                            return creationTime;

                        } else if ("getLastAccessedTime".equals(methodName)) {
                            HttpSession phys = realRequest.getSession(false);
                            if (phys != null) {
                                return phys.getLastAccessedTime();
                            }
                            return System.currentTimeMillis();

                        } else if ("getServletContext".equals(methodName)) {
                            return realRequest.getServletContext();

                        } else if ("invalidate".equals(methodName)) {
                            localAttributes.clear();
                            HttpSession phys = realRequest.getSession(false);
                            if (phys != null) {
                                phys.invalidate();
                            }
                            return null;
                        }

                        HttpSession phys = realRequest.getSession(false);
                        if (phys != null) {
                            return method.invoke(phys, args);
                        }

                        if (method.getReturnType().equals(Void.TYPE)) {
                            return null;
                        }
                        if (method.getReturnType().equals(Boolean.TYPE)) {
                            return false;
                        }
                        if (method.getReturnType().equals(Integer.TYPE)) {
                            return 0;
                        }
                        return null;
                    }
            );
        }
    }
}
