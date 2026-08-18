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

/**
 * AuthenticationProvider for OIDC Resource Owner Password Credentials (ROPC)
 * electronic signature verification.
 */
public class OidcRopcAuthenticationProvider implements AuthenticationProvider {

    private static final Logger logger = LoggerFactory.getLogger(OidcRopcAuthenticationProvider.class);

    private UserDetailsService userDetailsService;

    public UserDetailsService getUserDetailsService() {
        return userDetailsService;
    }

    public void setUserDetailsService(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
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

        boolean success = verifyRopc(tokenEndpoint, username, rawPassword);
        if (success) {
            logger.info("OIDC ROPC signature verification succeeded for user: {}", username);
            return new UsernamePasswordAuthenticationToken(
                    userDetails != null ? userDetails : username,
                    rawPassword,
                    userDetails != null ? userDetails.getAuthorities() : Collections.emptyList()
            );
        } else {
            logger.warn("OIDC ROPC signature verification failed for user: {}", username);
            throw new BadCredentialsException("Invalid OIDC credentials");
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
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
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
