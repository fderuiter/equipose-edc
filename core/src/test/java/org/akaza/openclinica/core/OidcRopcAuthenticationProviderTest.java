package org.akaza.openclinica.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

public class OidcRopcAuthenticationProviderTest {

    private OidcRopcAuthenticationProvider provider;
    private UserDetailsService userDetailsService;
    private HttpServer mockHttpServer;
    private int mockServerPort;

    @Before
    public void setUp() throws Exception {
        provider = new OidcRopcAuthenticationProvider();
        userDetailsService = Mockito.mock(UserDetailsService.class);
        provider.setUserDetailsService(userDetailsService);

        System.clearProperty("OIDC_TOKEN_ENDPOINT");
        System.clearProperty("OIDC_ISSUER_URI");
        System.clearProperty("OIDC_PROVIDER");
        System.clearProperty("OIDC_CLIENT_ID");
        System.clearProperty("OIDC_CLIENT_SECRET");
    }

    @After
    public void tearDown() {
        if (mockHttpServer != null) {
            mockHttpServer.stop(0);
        }
        System.clearProperty("OIDC_TOKEN_ENDPOINT");
        System.clearProperty("OIDC_ISSUER_URI");
        System.clearProperty("OIDC_PROVIDER");
        System.clearProperty("OIDC_CLIENT_ID");
        System.clearProperty("OIDC_CLIENT_SECRET");
    }

    @Test
    public void testIsSsoUser() {
        UserDetails ssoUserEmptyPass = new User("sso_user", "", Collections.emptyList());
        UserDetails ssoUserNullPass = new User("sso_user2", "", Collections.emptyList());
        UserDetails ssoUserBlankPass = new User("sso_user3", "   ", Collections.emptyList());

        UserDetails localUser = new User("local_user", "$2a$10$e8.s15uO/p.3c1...", Collections.emptyList());
        UserDetails ldapUser = new User("ldap_user", "*", Collections.emptyList());

        assertTrue(provider.isSsoUser(ssoUserEmptyPass));
        assertTrue(provider.isSsoUser(ssoUserNullPass));
        assertTrue(provider.isSsoUser(ssoUserBlankPass));

        assertFalse(provider.isSsoUser(localUser));
        assertFalse(provider.isSsoUser(ldapUser));
        assertFalse(provider.isSsoUser(null));
    }

    @Test
    public void testNonSsoUserFallback() {
        UserDetails localUser = new User("local_user", "$2a$10$hashedpass", Collections.emptyList());
        Authentication authRequest = new UsernamePasswordAuthenticationToken(localUser, "submitted_pass");

        Authentication result = provider.authenticate(authRequest);
        assertNull("Non-SSO users should return null to fall back to legacy DB/LDAP provider", result);
    }

    @Test
    public void testMissingOidcConfigFallback() {
        UserDetails ssoUser = new User("sso_user", "", Collections.emptyList());
        Authentication authRequest = new UsernamePasswordAuthenticationToken(ssoUser, "submitted_pass");

        Authentication result = provider.authenticate(authRequest);
        assertNull("Missing OIDC endpoint configuration should return null to fall back gracefully", result);
    }

    @Test
    public void testTokenEndpointResolution() {
        System.setProperty("OIDC_TOKEN_ENDPOINT", "http://localhost:8080/auth/token");
        assertEquals("http://localhost:8080/auth/token", provider.getTokenEndpoint());

        System.clearProperty("OIDC_TOKEN_ENDPOINT");
        System.setProperty("OIDC_ISSUER_URI", "http://keycloak:8080/realms/openclinica/");
        assertEquals("http://keycloak:8080/realms/openclinica/protocol/openid-connect/token", provider.getTokenEndpoint());

        System.clearProperty("OIDC_ISSUER_URI");
        System.setProperty("OIDC_PROVIDER", "http://idp:8080/auth");
        assertEquals("http://idp:8080/auth/protocol/openid-connect/token", provider.getTokenEndpoint());
    }

    @Test
    public void testClientIdAndSecretResolution() {
        assertEquals("openclinica-web", provider.getClientId());
        assertNull(provider.getClientSecret());

        System.setProperty("OIDC_CLIENT_ID", "custom-client");
        System.setProperty("OIDC_CLIENT_SECRET", "custom-secret");

        assertEquals("custom-client", provider.getClientId());
        assertEquals("custom-secret", provider.getClientSecret());
    }

    @Test
    public void testSuccessfulRopcAuthentication() throws Exception {
        startMockHttpServer(200, "{\"access_token\":\"eyJhbGciOi...\",\"token_type\":\"Bearer\"}");
        System.setProperty("OIDC_TOKEN_ENDPOINT", "http://localhost:" + mockServerPort + "/token");

        UserDetails ssoUser = new User("sso_user", "", Collections.emptyList());
        Authentication authRequest = new UsernamePasswordAuthenticationToken(ssoUser, "valid_sso_password");

        Authentication authResult = provider.authenticate(authRequest);
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());
        assertEquals("sso_user", authResult.getName());
    }

    @Test(expected = BadCredentialsException.class)
    public void testFailedRopcAuthenticationInvalidCredentials() throws Exception {
        startMockHttpServer(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}");
        System.setProperty("OIDC_TOKEN_ENDPOINT", "http://localhost:" + mockServerPort + "/token");

        UserDetails ssoUser = new User("sso_user", "", Collections.emptyList());
        Authentication authRequest = new UsernamePasswordAuthenticationToken(ssoUser, "wrong_sso_password");

        provider.authenticate(authRequest);
    }

    @Test(expected = BadCredentialsException.class)
    public void testRopcAuthenticationNetworkTimeoutOrFailure() throws Exception {
        // Point to an unreachable port
        System.setProperty("OIDC_TOKEN_ENDPOINT", "http://127.0.0.1:59999/unreachable_token");

        UserDetails ssoUser = new User("sso_user", "", Collections.emptyList());
        Authentication authRequest = new UsernamePasswordAuthenticationToken(ssoUser, "sso_password");

        provider.authenticate(authRequest);
    }

    @Test
    public void testSecurityNoCredentialInExceptionMessage() throws Exception {
        startMockHttpServer(401, "{\"error\":\"unauthorized\"}");
        System.setProperty("OIDC_TOKEN_ENDPOINT", "http://localhost:" + mockServerPort + "/token");

        String secretPassword = "SuperSecretPlaintextPassword123!";
        UserDetails ssoUser = new User("sso_user", "", Collections.emptyList());
        Authentication authRequest = new UsernamePasswordAuthenticationToken(ssoUser, secretPassword);

        try {
            provider.authenticate(authRequest);
            fail("Expected BadCredentialsException");
        } catch (BadCredentialsException ex) {
            assertFalse("Exception message must NOT contain cleartext password", ex.getMessage().contains(secretPassword));
        }
    }

    @Test
    public void testSecurityManagerIntegrationWithOidc() throws Exception {
        startMockHttpServer(200, "{\"access_token\":\"mock_access_token\"}");
        System.setProperty("OIDC_TOKEN_ENDPOINT", "http://localhost:" + mockServerPort + "/token");

        SecurityManager securityManager = new SecurityManager();
        securityManager.setProviders(new AuthenticationProvider[]{provider});

        UserDetails ssoUser = new User("investigator_sso", "", Collections.emptyList());

        boolean verified = securityManager.verifyPassword("valid_sso_password", ssoUser);
        assertTrue("SecurityManager verifyPassword must succeed for valid SSO credentials via OIDC", verified);
    }

    private void startMockHttpServer(final int responseCode, final String responseBody) throws IOException {
        mockHttpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        mockServerPort = mockHttpServer.getAddress().getPort();
        mockHttpServer.createContext("/token", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = responseBody.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseCode, bytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            }
        });
        mockHttpServer.start();
    }
}
