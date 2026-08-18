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
                
                Map<String, Object> claims = extractClaims(authentication);

                Object principal = authentication.getPrincipal();
                boolean isFederated = (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken)
                        || (principal instanceof org.springframework.security.oauth2.jwt.Jwt)
                        || (principal instanceof org.springframework.security.oauth2.core.user.OAuth2User)
                        || (principal instanceof org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal)
                        || (authentication.getClass().getName().contains("Saml2"))
                        || (authentication.getClass().getName().contains("OAuth2"))
                        || (claims != null && !claims.isEmpty());

                if (isFederated) {
                    Object tenantIdObj = claims != null ? claims.get("tenant_id") : null;
                    if (tenantIdObj == null && claims != null) {
                        tenantIdObj = claims.get("tenantId");
                    }
                    if (tenantIdObj == null && claims != null) {
                        tenantIdObj = claims.get("tenant");
                    }

                    logger.warn("DEBUG FEDERATED: tenantIdObj = " + tenantIdObj + ", claims = " + claims);
                    if (tenantIdObj == null || String.valueOf(tenantIdObj).trim().isEmpty()) {
                        logger.warn("DEBUG FEDERATED: tenantIdObj is null, rejecting with 403");
                        logger.error("SECURITY ALERT: User identity token lacks a valid tenant identifier.");
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.getWriter().write("Access Denied: Missing tenant identifier");
                        return;
                    }

                    String tenantId = String.valueOf(tenantIdObj).trim();
                    boolean whitelisted = org.akaza.openclinica.modern.security.TenantContext.isWhitelisted(tenantId);
                    logger.warn("DEBUG FEDERATED: tenantId = " + tenantId + ", whitelisted = " + whitelisted);
                    if (!whitelisted) {
                        logger.warn("DEBUG FEDERATED: tenantId is not whitelisted, rejecting with 403");
                        logger.error("SECURITY ALERT: Tenant identifier " + tenantId + " is not whitelisted.");
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.getWriter().write("Access Denied: Tenant not whitelisted");
                        return;
                    }

                    org.akaza.openclinica.modern.security.TenantContext.setCurrentTenant(tenantId);
                }

                if ("service_account".equals(username)) {
                    org.akaza.openclinica.modern.security.TenantContext.setBypass(true);
                }

                if (claims != null && !claims.isEmpty() && !"service_account".equals(username)) {
                    try {
                        provisionOrUpdateUser(username, claims);
                    } catch (Exception ex) {
                        logger.error("Failed to provision or update user " + username, ex);
                        response.setContentType("text/html;charset=UTF-8");
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        response.getWriter().write(
                            "<html>" +
                            "<head><title>Authentication Error</title></head>" +
                            "<body style='font-family: Arial, sans-serif; text-align: center; margin-top: 100px;'>" +
                            "  <div style='display: inline-block; padding: 30px; border: 1px solid #ccc; border-radius: 5px; box-shadow: 0 0 10px rgba(0,0,0,0.1);'>" +
                            "    <h2 style='color: #d9534f;'>Authentication Error</h2>" +
                            "    <p>A transient database or network error occurred during your single sign-on authentication.</p>" +
                            "    <p>Please try again later or contact your system administrator.</p>" +
                            "    <a href=\"/app/login\" style='display: inline-block; margin-top: 15px; padding: 10px 20px; background-color: #337ab7; color: white; text-decoration: none; border-radius: 3px;'>Return to Login</a>" +
                            "  </div>" +
                            "</body>" +
                            "</html>"
                        );
                        return;
                    }
                }

                UserAccountBean userBean = null;
                if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) {
                    String claimKeyConfig = System.getProperty("OIDC_USER_IDENTIFIER_CLAIM");
                    if (claimKeyConfig == null || claimKeyConfig.trim().isEmpty()) {
                        claimKeyConfig = System.getenv("OIDC_USER_IDENTIFIER_CLAIM");
                    }
                    String[] candidateKeys;
                    if (claimKeyConfig != null && !claimKeyConfig.trim().isEmpty()) {
                        candidateKeys = claimKeyConfig.split("\\s*,\\s*");
                    } else {
                        candidateKeys = new String[]{"user_id", "sub", "preferred_username"};
                    }

                    String matchedClaimKey = null;
                    Object userIdentifierObj = null;
                    for (String candidate : candidateKeys) {
                        if ("client_id".equalsIgnoreCase(candidate) 
                            || "appid".equalsIgnoreCase(candidate) 
                            || "azp".equalsIgnoreCase(candidate)
                            || "client_id_claim".equalsIgnoreCase(candidate)) {
                            continue;
                        }
                        Object value = claims != null ? claims.get(candidate) : null;
                        if (value != null && !String.valueOf(value).trim().isEmpty()) {
                            matchedClaimKey = candidate;
                            userIdentifierObj = value;
                            break;
                        }
                    }

                    if (userIdentifierObj == null) {
                        logger.error("SECURITY ALERT: Configured user identifier claims " + java.util.Arrays.toString(candidateKeys) + " are missing or blank in the token.");
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.getWriter().write("Access Denied: Missing user identifier claim");
                        return;
                    }
                    String userIdentifier = String.valueOf(userIdentifierObj);

                    Object clientIdVal = claims != null ? claims.get("client_id") : null;
                    Object appidVal = claims != null ? claims.get("appid") : null;
                    Object azpVal = claims != null ? claims.get("azp") : null;
                    if ((clientIdVal != null && userIdentifier.equals(String.valueOf(clientIdVal)))
                        || (appidVal != null && userIdentifier.equals(String.valueOf(appidVal)))
                        || (azpVal != null && userIdentifier.equals(String.valueOf(azpVal)))) {
                        logger.error("SECURITY ALERT: Resolved user identifier matches a client ID claim: " + userIdentifier + ". Rejecting to prevent account overwrites.");
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.getWriter().write("Access Denied: Invalid user identifier");
                        return;
                    }

                    userBean = unifiedRepository.getUserAccountBeanByUserName(userIdentifier);
                    if (userBean == null) {
                        userBean = new UserAccountBean();
                        userBean.setName(userIdentifier);
                        if (claims != null && claims.containsKey("user_id")) {
                            try {
                                userBean.setId(Integer.parseInt(String.valueOf(claims.get("user_id"))));
                            } catch (Exception e) {
                                userBean.setId(1);
                            }
                        } else {
                            userBean.setId(1);
                        }
                        if (claims != null && claims.containsKey("active_study_id")) {
                            try {
                                userBean.setActiveStudyId(Integer.parseInt(String.valueOf(claims.get("active_study_id"))));
                            } catch (Exception e) {}
                        }
                    } else {
                        if (claims != null && claims.containsKey("active_study_id")) {
                            try {
                                userBean.setActiveStudyId(Integer.parseInt(String.valueOf(claims.get("active_study_id"))));
                            } catch (Exception e) {}
                        }
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

    private Map<String, Object> extractClaims(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal == null) {
            return null;
        }

        Map<String, Object> claims = new HashMap<>();

        if (principal instanceof org.springframework.security.oauth2.core.user.OAuth2User) {
            org.springframework.security.oauth2.core.user.OAuth2User oauth2User = 
                (org.springframework.security.oauth2.core.user.OAuth2User) principal;
            claims.putAll(oauth2User.getAttributes());
        }
        else if (principal instanceof org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal) {
            org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal saml2Principal = 
                (org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal) principal;
            
            Map<String, java.util.List<Object>> attrs = saml2Principal.getAttributes();
            for (Map.Entry<String, java.util.List<Object>> entry : attrs.entrySet()) {
                java.util.List<Object> vals = entry.getValue();
                if (vals != null && !vals.isEmpty()) {
                    claims.put(entry.getKey(), vals.get(0));
                }
            }
        }
        else if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) {
            org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth = 
                (org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) authentication;
            claims.putAll(jwtAuth.getTokenAttributes());
        }

        return claims;
    }

    private String getClaimAsString(Map<String, Object> claims, String... keys) {
        if (claims == null) return null;
        for (String key : keys) {
            Object val = claims.get(key);
            if (val != null) {
                return String.valueOf(val).trim();
            }
        }
        return null;
    }

    private int getClaimAsInt(Map<String, Object> claims, int defaultVal, String... keys) {
        if (claims == null) return defaultVal;
        for (String key : keys) {
            Object val = claims.get(key);
            if (val != null) {
                try {
                    if (val instanceof Number) {
                        return ((Number) val).intValue();
                    }
                    return Integer.parseInt(String.valueOf(val).trim());
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        return defaultVal;
    }

    private int getDefaultStudyId() {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement("SELECT study_id FROM study ORDER BY study_id ASC LIMIT 1");
             java.sql.ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve default study id", e);
        }
        return 1;
    }

    private boolean isStudyIdValid(int studyId) {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM study WHERE study_id = ?")) {
            ps.setInt(1, studyId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            logger.error("Failed to validate study id: " + studyId, e);
        }
        return false;
    }

    private Integer getUserIdByUsername(String username) {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM user_account WHERE user_name = ?")) {
            ps.setString(1, username);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to query user_id by username", e);
        }
        return null;
    }

    private int getNextUserId() throws java.sql.SQLException {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement("SELECT nextval('user_account_user_id_seq')");
             java.sql.ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        throw new java.sql.SQLException("Could not retrieve next user_id value from sequence.");
    }

    private boolean studyUserRoleExists(String username, int studyId) {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM study_user_role WHERE user_name = ? AND study_id = ?")) {
            ps.setString(1, username);
            ps.setInt(2, studyId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            logger.error("Failed to check study user role mapping", e);
        }
        return false;
    }

    private void updateStudyUserRole(String username, int studyId, String roleName) {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE study_user_role SET role_name = ?, status_id = 1, date_updated = NOW() WHERE user_name = ? AND study_id = ?")) {
            ps.setString(1, roleName);
            ps.setString(2, username);
            ps.setInt(3, studyId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to update study user role mapping", e);
        }
    }

    private void insertStudyUserRole(String username, int studyId, String roleName) {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO study_user_role (role_name, study_id, status_id, user_name, owner_id, date_created) VALUES (?, ?, 1, ?, 1, NOW())")) {
            ps.setString(1, roleName);
            ps.setInt(2, studyId);
            ps.setString(3, username);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to insert study user role mapping", e);
        }
    }

    protected void provisionOrUpdateUser(String username, Map<String, Object> claims) throws Exception {
        String firstName = getClaimAsString(claims, "given_name", "firstName", "first_name", "givenName");
        if (firstName == null || firstName.isEmpty()) firstName = "First";

        String lastName = getClaimAsString(claims, "family_name", "lastName", "last_name", "familyName");
        if (lastName == null || lastName.isEmpty()) lastName = "Last";

        String email = getClaimAsString(claims, "email", "mail");
        if (email == null || email.isEmpty()) email = username + "@example.com";

        String affiliation = getClaimAsString(claims, "institutional_affiliation", "affiliation", "organization", "org");
        if (affiliation == null || affiliation.isEmpty()) affiliation = "SSO";

        int studyId = getClaimAsInt(claims, -1, "active_study_id", "study_id", "study", "activeStudyId");
        if (studyId <= 0 || !isStudyIdValid(studyId)) {
            studyId = getDefaultStudyId();
        }

        String roleName = getClaimAsString(claims, "role", "roles", "system_role", "systemRole");
        org.akaza.openclinica.bean.core.Role finalRole = org.akaza.openclinica.bean.core.Role.RESEARCHASSISTANT;
        if (roleName != null) {
            String cleanRole = roleName.toLowerCase().replaceAll("[_\\s\\-]+", "");
            if (cleanRole.contains("admin") || cleanRole.contains("sysadmin")) {
                finalRole = org.akaza.openclinica.bean.core.Role.ADMIN;
            } else if (cleanRole.contains("coordinator")) {
                finalRole = org.akaza.openclinica.bean.core.Role.COORDINATOR;
            } else if (cleanRole.contains("director")) {
                finalRole = org.akaza.openclinica.bean.core.Role.STUDYDIRECTOR;
            } else if (cleanRole.contains("investigator")) {
                finalRole = org.akaza.openclinica.bean.core.Role.INVESTIGATOR;
            } else if (cleanRole.contains("monitor")) {
                finalRole = org.akaza.openclinica.bean.core.Role.MONITOR;
            } else if (cleanRole.contains("ra2") || cleanRole.contains("entry2")) {
                finalRole = org.akaza.openclinica.bean.core.Role.RESEARCHASSISTANT2;
            } else if (cleanRole.contains("ra") || cleanRole.contains("entry") || cleanRole.contains("assistant")) {
                finalRole = org.akaza.openclinica.bean.core.Role.RESEARCHASSISTANT;
            }
        }

        int userTypeId = (finalRole == org.akaza.openclinica.bean.core.Role.ADMIN) ? 1 : 2;

        Integer existingUserId = getUserIdByUsername(username);

        if (existingUserId == null) {
            int newUserId = getNextUserId();
            logger.warn("SSO: Provisioning new user " + username + " with ID " + newUserId + ", study ID " + studyId + ", role " + finalRole.getName());
            
            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO user_account (" +
                         "    user_id, user_name, passwd, first_name, last_name, email, " +
                         "    active_study, institutional_affiliation, status_id, owner_id, " +
                         "    date_created, passwd_challenge_question, passwd_challenge_answer, phone, " +
                         "    user_type_id, enabled, account_non_locked, lock_counter, run_webservices, " +
                         "    access_code, enable_api_key, api_key" +
                         ") VALUES (" +
                         "    ?, ?, '', ?, ?, ?, " +
                         "    ?, ?, 1, 1, " +
                         "    NOW(), '', '', '', " +
                         "    ?, TRUE, TRUE, 0, FALSE, " +
                         "    '', FALSE, ''" +
                         ")")) {
                ps.setInt(1, newUserId);
                ps.setString(2, username);
                ps.setString(3, firstName);
                ps.setString(4, lastName);
                ps.setString(5, email);
                ps.setInt(6, studyId);
                ps.setString(7, affiliation);
                ps.setInt(8, userTypeId);
                ps.executeUpdate();
            }
        } else {
            logger.warn("SSO: Updating returning user " + username + " with ID " + existingUserId + ", study ID " + studyId + ", role " + finalRole.getName());
            
            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(
                         "UPDATE user_account SET " +
                         "    first_name = ?, " +
                         "    last_name = ?, " +
                         "    email = ?, " +
                         "    institutional_affiliation = ?, " +
                         "    active_study = ?, " +
                         "    user_type_id = ?, " +
                         "    enabled = TRUE, " +
                         "    account_non_locked = TRUE, " +
                         "    status_id = 1, " +
                         "    date_updated = NOW() " +
                         "WHERE user_id = ?")) {
                ps.setString(1, firstName);
                ps.setString(2, lastName);
                ps.setString(3, email);
                ps.setString(4, affiliation);
                ps.setInt(5, studyId);
                ps.setInt(6, userTypeId);
                ps.setInt(7, existingUserId);
                ps.executeUpdate();
            }
        }

        if (!studyUserRoleExists(username, studyId)) {
            insertStudyUserRole(username, studyId, finalRole.getName());
        } else {
            updateStudyUserRole(username, studyId, finalRole.getName());
        }
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
