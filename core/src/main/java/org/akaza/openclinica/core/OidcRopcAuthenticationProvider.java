package org.akaza.openclinica.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import org.akaza.openclinica.dao.hibernate.AuditUserLoginDao;
import org.akaza.openclinica.dao.hibernate.AuditLogEventDao;
import org.akaza.openclinica.domain.technicaladmin.AuditUserLoginBean;
import org.akaza.openclinica.domain.technicaladmin.LoginStatus;
import org.akaza.openclinica.domain.datamap.AuditLogEvent;
import org.akaza.openclinica.domain.datamap.AuditLogEventType;
import org.akaza.openclinica.core.interceptor.AuditHashService;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;

/**
 * AuthenticationProvider for OIDC Resource Owner Password Credentials (ROPC)
 * electronic signature verification.
 */
public class OidcRopcAuthenticationProvider implements AuthenticationProvider {

    private static final Logger logger = LoggerFactory.getLogger(OidcRopcAuthenticationProvider.class);

    private UserDetailsService userDetailsService;

    @Autowired(required = false)
    private AuditUserLoginDao auditUserLoginDao;

    @Autowired(required = false)
    private AuditLogEventDao auditLogEventDao;

    @Autowired(required = false)
    private AuditHashService auditHashService;

    @Autowired(required = false)
    private SessionFactory sessionFactory;

    @Autowired(required = false)
    private DataSource dataSource;

    public UserDetailsService getUserDetailsService() {
        return userDetailsService;
    }

    public void setUserDetailsService(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    public AuditUserLoginDao getAuditUserLoginDao() {
        return auditUserLoginDao;
    }

    public void setAuditUserLoginDao(AuditUserLoginDao auditUserLoginDao) {
        this.auditUserLoginDao = auditUserLoginDao;
    }

    public AuditLogEventDao getAuditLogEventDao() {
        return auditLogEventDao;
    }

    public void setAuditLogEventDao(AuditLogEventDao auditLogEventDao) {
        this.auditLogEventDao = auditLogEventDao;
    }

    public AuditHashService getAuditHashService() {
        return auditHashService;
    }

    public void setAuditHashService(AuditHashService auditHashService) {
        this.auditHashService = auditHashService;
    }

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!supports(authentication.getClass())) {
            return null;
        }

        String username = authentication.getName();
        String rawPassword = authentication.getCredentials() != null ? authentication.getCredentials().toString() : "";

        if (username == null || username.trim().isEmpty()) {
            return null;
        }

        UserDetails userDetails = null;
        if (authentication.getPrincipal() instanceof UserDetails) {
            userDetails = (UserDetails) authentication.getPrincipal();
        } else if (userDetailsService != null) {
            try {
                userDetails = userDetailsService.loadUserByUsername(username);
            } catch (Exception e) {
                logger.debug("UserDetailsService could not load user: {}", username);
            }
        }

        if (!isSsoUser(userDetails)) {
            // Fall back to DB / LDAP providers for non-SSO users
            return null;
        }

        String tokenEndpoint = getTokenEndpoint();
        if (tokenEndpoint == null || tokenEndpoint.trim().isEmpty()) {
            logger.debug("OIDC Token Endpoint is not configured. Skipping OIDC ROPC authentication.");
            return null;
        }

        logger.info("Attempting OIDC ROPC electronic signature verification for user: {}", username);

        boolean success = false;
        try {
            success = verifyRopc(tokenEndpoint, username, rawPassword);
        } catch (Exception e) {
            logger.warn("Exception during OIDC ROPC authentication attempt: {}", e.getMessage());
            success = false;
        }

        if (success) {
            logger.info("OIDC ROPC signature verification succeeded for user: {}", username);
            recordAudit(username, true, "OIDC ROPC signature verification succeeded");
            return new UsernamePasswordAuthenticationToken(
                    userDetails != null ? userDetails : username,
                    rawPassword,
                    userDetails != null ? userDetails.getAuthorities() : Collections.emptyList()
            );
        } else {
            logger.warn("OIDC ROPC signature verification failed for user: {}", username);
            recordAudit(username, false, "OIDC ROPC signature verification failed");
            throw new BadCredentialsException("Invalid OIDC credentials");
        }
    }

    public void recordAudit(String username, boolean success, String details) {
        try {
            if (auditUserLoginDao != null) {
                AuditUserLoginBean loginBean = new AuditUserLoginBean();
                loginBean.setUserName(username);
                loginBean.setLoginAttemptDate(new java.util.Date());
                loginBean.setLoginStatus(success ? LoginStatus.SUCCESSFUL_LOGIN : LoginStatus.FAILED_LOGIN);
                loginBean.setDetails(details);
                auditUserLoginDao.saveOrUpdate(loginBean);
            }
        } catch (Exception e) {
            logger.error("Failed to save AuditUserLoginBean: {}", e.getMessage());
        }

        try {
            AuditLogEvent event = new AuditLogEvent();
            event.setAuditDate(new java.util.Date());
            event.setAuditTable("user_account");
            event.setEntityName(username);
            event.setReasonForChange(details);
            
            AuditLogEventType eventType = new AuditLogEventType();
            eventType.setAuditLogEventTypeId(success ? 44 : 45);
            event.setAuditLogEventType(eventType);

            if (auditHashService != null && sessionFactory != null) {
                auditHashService.saveAndChain(event);
            } else if (auditLogEventDao != null) {
                auditLogEventDao.saveOrUpdate(event);
            } else if (dataSource != null) {
                recordAuditDirect(username, success, details);
            }
        } catch (Exception e) {
            logger.error("Failed to save AuditLogEvent for OIDC authentication: {}", e.getMessage());
        }
    }

    private void recordAuditDirect(String username, boolean success, String details) {
        if (dataSource == null) {
            return;
        }
        try (java.sql.Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO audit_user_login (user_name, login_attempt_date, login_status_code, details) VALUES (?, NOW(), ?, ?)")) {
                ps.setString(1, username);
                ps.setInt(2, success ? 1 : 2);
                ps.setString(3, details);
                ps.executeUpdate();
            }

            String prevHash = null;
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT chain_hash FROM audit_log_event WHERE chain_hash IS NOT NULL AND chain_hash != 'LEGACY_UNCHAINED' ORDER BY audit_id DESC LIMIT 1");
                 java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    prevHash = rs.getString(1);
                }
            }

            String auditTable = "user_account";
            int typeId = success ? 44 : 45;
            String newHash = AuditHashService.computeHashValues(prevHash, auditTable, null, username, details, null, null);

            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO audit_log_event (audit_date, audit_table, entity_name, reason_for_change, audit_log_event_type_id, chain_hash) VALUES (NOW(), ?, ?, ?, ?, ?)")) {
                ps.setString(1, auditTable);
                ps.setString(2, username);
                ps.setString(3, details);
                ps.setInt(4, typeId);
                ps.setString(5, newHash);
                ps.executeUpdate();
            }

            conn.commit();
        } catch (Exception e) {
            logger.error("JDBC audit recording failed: {}", e.getMessage());
        }
    }

    /**
     * Determines whether a user profile represents an SSO-provisioned user.
     * SSO users are provisioned with an empty/null password field.
     */
    public boolean isSsoUser(UserDetails userDetails) {
        if (userDetails == null) {
            return false;
        }
        String storedPassword = userDetails.getPassword();
        return storedPassword == null || storedPassword.trim().isEmpty();
    }

    public String getTokenEndpoint() {
        String tokenEndpoint = System.getProperty("OIDC_TOKEN_ENDPOINT");
        if (tokenEndpoint == null || tokenEndpoint.trim().isEmpty()) {
            tokenEndpoint = System.getenv("OIDC_TOKEN_ENDPOINT");
        }
        if (tokenEndpoint != null && !tokenEndpoint.trim().isEmpty()) {
            return tokenEndpoint.trim();
        }

        String issuerUri = System.getProperty("OIDC_ISSUER_URI");
        if (issuerUri == null || issuerUri.trim().isEmpty()) {
            issuerUri = System.getenv("OIDC_ISSUER_URI");
        }
        if (issuerUri == null || issuerUri.trim().isEmpty()) {
            issuerUri = System.getProperty("OIDC_PROVIDER");
        }
        if (issuerUri == null || issuerUri.trim().isEmpty()) {
            issuerUri = System.getenv("OIDC_PROVIDER");
        }

        if (issuerUri != null && !issuerUri.trim().isEmpty()) {
            String base = issuerUri.trim();
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (base.endsWith("/protocol/openid-connect/token")) {
                return base;
            }
            return base + "/protocol/openid-connect/token";
        }

        return null;
    }

    public String getClientId() {
        String clientId = System.getProperty("OIDC_CLIENT_ID");
        if (clientId == null || clientId.trim().isEmpty()) {
            clientId = System.getenv("OIDC_CLIENT_ID");
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            return clientId.trim();
        }
        return "openclinica-web";
    }

    public String getClientSecret() {
        String clientSecret = System.getProperty("OIDC_CLIENT_SECRET");
        if (clientSecret == null || clientSecret.trim().isEmpty()) {
            clientSecret = System.getenv("OIDC_CLIENT_SECRET");
        }
        if (clientSecret != null && !clientSecret.trim().isEmpty()) {
            return clientSecret.trim();
        }
        return null;
    }

    public boolean verifyRopc(String tokenEndpoint, String username, String password) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(tokenEndpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");

            StringBuilder postData = new StringBuilder();
            postData.append("grant_type=").append(URLEncoder.encode("password", "UTF-8"));
            postData.append("&client_id=").append(URLEncoder.encode(getClientId(), "UTF-8"));

            String clientSecret = getClientSecret();
            if (clientSecret != null && !clientSecret.isEmpty()) {
                postData.append("&client_secret=").append(URLEncoder.encode(clientSecret, "UTF-8"));
            }

            postData.append("&username=").append(URLEncoder.encode(username, "UTF-8"));
            postData.append("&password=").append(URLEncoder.encode(password, "UTF-8"));

            byte[] postDataBytes = postData.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postDataBytes);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                String jsonResponse = response.toString();
                return jsonResponse.contains("\"access_token\"");
            } else {
                logger.warn("OIDC Token Endpoint returned HTTP status {}", responseCode);
                return false;
            }
        } catch (Exception e) {
            logger.warn("Exception during OIDC ROPC authentication attempt: {}", e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
