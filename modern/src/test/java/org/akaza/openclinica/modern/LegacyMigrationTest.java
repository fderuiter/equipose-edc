package org.akaza.openclinica.modern;

import org.akaza.openclinica.web.filter.LegacyMigrationAuthenticationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
public class LegacyMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("ocUserDetailsService")
    private UserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Connection mockConnection;
    private PreparedStatement mockStatement;
    private ResultSet mockResultSet;

    @BeforeEach
    public void setUp() throws Exception {
        // Initialize required tables and sequences in H2 in-memory DB if they do not exist
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS study (" +
                "    study_id INT PRIMARY KEY," +
                "    name VARCHAR(255)" +
                ")");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS user_account (" +
                "    user_id INT PRIMARY KEY," +
                "    user_name VARCHAR(255) UNIQUE," +
                "    passwd VARCHAR(255)," +
                "    first_name VARCHAR(255)," +
                "    last_name VARCHAR(255)," +
                "    email VARCHAR(255)," +
                "    active_study INT," +
                "    institutional_affiliation VARCHAR(255)," +
                "    status_id INT," +
                "    owner_id INT," +
                "    date_created TIMESTAMP," +
                "    passwd_challenge_question VARCHAR(255)," +
                "    passwd_challenge_answer VARCHAR(255)," +
                "    phone VARCHAR(255)," +
                "    user_type_id INT," +
                "    enabled BOOLEAN," +
                "    account_non_locked BOOLEAN," +
                "    lock_counter INT," +
                "    run_webservices BOOLEAN," +
                "    access_code VARCHAR(255)," +
                "    enable_api_key BOOLEAN," +
                "    api_key VARCHAR(255)," +
                "    tenant_id VARCHAR(255)" +
                ")");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS authorities (" +
                "    username VARCHAR(255)," +
                "    authority VARCHAR(255)" +
                ")");

        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS user_account_user_id_seq START WITH 10000");
        try {
            jdbcTemplate.execute("ALTER SEQUENCE user_account_user_id_seq RESTART WITH 10000");
        } catch (Exception e) {
            // Ignore if the database/mode does not support sequence restart
        }

        // Clean up authorities and user accounts for our specific test users to avoid key constraint violations
        jdbcTemplate.execute("DELETE FROM authorities WHERE username IN ('legacy_bcrypt_user', 'legacy_md5_user', 'sso_legacy_user')");
        jdbcTemplate.execute("DELETE FROM user_account WHERE user_name IN ('legacy_bcrypt_user', 'legacy_md5_user', 'sso_legacy_user')");

        // Seed default study safely (if not already present)
        try {
            jdbcTemplate.execute("INSERT INTO study (study_id, name) VALUES (1, 'Default Study')");
        } catch (Exception e) {
            // Ignore if study with ID 1 already exists
        }

        // Set up Mock legacy DB connections
        mockConnection = mock(Connection.class);
        mockStatement = mock(PreparedStatement.class);
        mockResultSet = mock(ResultSet.class);

        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStatement);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);

        // Ensure we don't have lingering test users in the local db
        jdbcTemplate.execute("DELETE FROM authorities WHERE username IN ('legacy_bcrypt_user', 'legacy_md5_user', 'sso_legacy_user')");
        jdbcTemplate.execute("DELETE FROM user_account WHERE user_name IN ('legacy_bcrypt_user', 'legacy_md5_user', 'sso_legacy_user')");
    }

    @AfterEach
    public void tearDown() {
        jdbcTemplate.execute("DELETE FROM authorities WHERE username IN ('legacy_bcrypt_user', 'legacy_md5_user', 'sso_legacy_user')");
        jdbcTemplate.execute("DELETE FROM user_account WHERE user_name IN ('legacy_bcrypt_user', 'legacy_md5_user', 'sso_legacy_user')");
    }

    @Test
    public void testLegacyBcryptUserMigrationSuccess() throws Exception {
        // Prepare mock ResultSet for BCrypt user in legacy database
        when(mockResultSet.next()).thenReturn(true).thenReturn(false);
        String correctPassword = "BcryptSecretPassword123";
        String bcryptHash = passwordEncoder.encode(correctPassword);

        when(mockResultSet.getString("passwd")).thenReturn(bcryptHash);
        when(mockResultSet.getString("first_name")).thenReturn("Bcrypt");
        when(mockResultSet.getString("last_name")).thenReturn("User");
        when(mockResultSet.getString("email")).thenReturn("bcrypt@example.com");
        when(mockResultSet.getInt("active_study")).thenReturn(1);
        when(mockResultSet.getString("institutional_affiliation")).thenReturn("Bcrypt Affiliation");
        when(mockResultSet.getInt("user_type_id")).thenReturn(2);
        when(mockResultSet.getBoolean("enabled")).thenReturn(true);
        when(mockResultSet.getBoolean("account_non_locked")).thenReturn(true);

        LegacyMigrationAuthenticationProvider provider = new LegacyMigrationAuthenticationProvider() {
            @Override
            protected Connection getLegacyConnection() throws SQLException {
                return mockConnection;
            }
        };

        provider.setDataSource(dataSource);
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setLegacyDbUrl("jdbc:postgresql://dummy:5432/legacy");

        // Verify user does not exist locally
        Integer localCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE user_name = 'legacy_bcrypt_user'", Integer.class);
        assertEquals(0, localCountBefore);

        // Authenticate BCrypt user
        UsernamePasswordAuthenticationToken authRequest = new UsernamePasswordAuthenticationToken("legacy_bcrypt_user", correctPassword);
        Authentication result = provider.authenticate(authRequest);

        assertNotNull(result);
        assertTrue(result.isAuthenticated());
        assertEquals("legacy_bcrypt_user", result.getName());

        // Verify that user is successfully migrated to the central directory
        Integer localCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE user_name = 'legacy_bcrypt_user'", Integer.class);
        assertEquals(1, localCountAfter);

        // Verify that the migrated password is a valid BCrypt hash matching correctPassword
        String migratedHash = jdbcTemplate.queryForObject(
                "SELECT passwd FROM user_account WHERE user_name = 'legacy_bcrypt_user'", String.class);
        assertTrue(migratedHash.startsWith("$2a$") || migratedHash.startsWith("$2b$") || migratedHash.startsWith("$2y$"));
        assertTrue(passwordEncoder.matches(correctPassword, migratedHash));

        // Subsequent logins can bypass the legacy database lookup (provider returns null, letting standard local DaoAuthenticationProvider handle it)
        Authentication subsequentResult = provider.authenticate(authRequest);
        assertNull(subsequentResult);
    }

    @Test
    public void testLegacyMd5UserMigrationSuccess() throws Exception {
        // Prepare mock ResultSet for MD5 user in legacy database
        when(mockResultSet.next()).thenReturn(true).thenReturn(false);
        String correctPassword = "Md5SecretPassword123";
        String md5Hash = org.apache.commons.codec.digest.DigestUtils.md5Hex(correctPassword);

        when(mockResultSet.getString("passwd")).thenReturn(md5Hash); // MD5 hex hash
        when(mockResultSet.getString("first_name")).thenReturn("Md5");
        when(mockResultSet.getString("last_name")).thenReturn("User");
        when(mockResultSet.getString("email")).thenReturn("md5@example.com");
        when(mockResultSet.getInt("active_study")).thenReturn(1);
        when(mockResultSet.getString("institutional_affiliation")).thenReturn("Md5 Affiliation");
        when(mockResultSet.getInt("user_type_id")).thenReturn(2);
        when(mockResultSet.getBoolean("enabled")).thenReturn(true);
        when(mockResultSet.getBoolean("account_non_locked")).thenReturn(true);

        LegacyMigrationAuthenticationProvider provider = new LegacyMigrationAuthenticationProvider() {
            @Override
            protected Connection getLegacyConnection() throws SQLException {
                return mockConnection;
            }
        };

        provider.setDataSource(dataSource);
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setLegacyDbUrl("jdbc:postgresql://dummy:5432/legacy");

        // Verify user does not exist locally
        Integer localCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE user_name = 'legacy_md5_user'", Integer.class);
        assertEquals(0, localCountBefore);

        // Authenticate MD5 user with correct password
        UsernamePasswordAuthenticationToken authRequest = new UsernamePasswordAuthenticationToken("legacy_md5_user", correctPassword);
        Authentication result = provider.authenticate(authRequest);

        assertNotNull(result);
        assertTrue(result.isAuthenticated());
        assertEquals("legacy_md5_user", result.getName());

        // Verify that user is successfully migrated to the central directory
        Integer localCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE user_name = 'legacy_md5_user'", Integer.class);
        assertEquals(1, localCountAfter);

        // Verify that the migrated password has been upgraded and re-encrypted using modern BCrypt
        String migratedHash = jdbcTemplate.queryForObject(
                "SELECT passwd FROM user_account WHERE user_name = 'legacy_md5_user'", String.class);
        assertTrue(migratedHash.startsWith("$2a$") || migratedHash.startsWith("$2b$") || migratedHash.startsWith("$2y$"));
        assertTrue(passwordEncoder.matches(correctPassword, migratedHash));

        // Subsequent logins succeed even if legacy database is entirely disconnected
        LegacyMigrationAuthenticationProvider disconnectedProvider = new LegacyMigrationAuthenticationProvider() {
            @Override
            protected Connection getLegacyConnection() throws SQLException {
                throw new SQLException("Database is disconnected!");
            }
        };
        disconnectedProvider.setDataSource(dataSource);
        disconnectedProvider.setUserDetailsService(userDetailsService);
        disconnectedProvider.setPasswordEncoder(passwordEncoder);
        disconnectedProvider.setLegacyDbUrl("jdbc:postgresql://dummy:5432/legacy");

        // Should return null (bypass), letting local DaoAuthenticationProvider handle local authentication
        Authentication disconnectedResult = disconnectedProvider.authenticate(authRequest);
        assertNull(disconnectedResult);
    }

    @Test
    public void testLegacyUserIncorrectPasswordFails() throws Exception {
        // Prepare mock ResultSet for BCrypt user in legacy database
        when(mockResultSet.next()).thenReturn(true).thenReturn(false);
        String correctPassword = "BcryptSecretPassword123";
        String bcryptHash = passwordEncoder.encode(correctPassword);

        when(mockResultSet.getString("passwd")).thenReturn(bcryptHash);
        when(mockResultSet.getString("first_name")).thenReturn("Bcrypt");
        when(mockResultSet.getString("last_name")).thenReturn("User");
        when(mockResultSet.getString("email")).thenReturn("bcrypt@example.com");
        when(mockResultSet.getInt("active_study")).thenReturn(1);
        when(mockResultSet.getString("institutional_affiliation")).thenReturn("Bcrypt Affiliation");
        when(mockResultSet.getInt("user_type_id")).thenReturn(2);
        when(mockResultSet.getBoolean("enabled")).thenReturn(true);
        when(mockResultSet.getBoolean("account_non_locked")).thenReturn(true);

        LegacyMigrationAuthenticationProvider provider = new LegacyMigrationAuthenticationProvider() {
            @Override
            protected Connection getLegacyConnection() throws SQLException {
                return mockConnection;
            }
        };

        provider.setDataSource(dataSource);
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setLegacyDbUrl("jdbc:postgresql://dummy:5432/legacy");

        // Authenticate with wrong password
        UsernamePasswordAuthenticationToken authRequest = new UsernamePasswordAuthenticationToken("legacy_bcrypt_user", "IncorrectPassword123");
        
        assertThrows(BadCredentialsException.class, () -> {
            provider.authenticate(authRequest);
        });

        // Verify that no migration has occurred (user is still not present locally)
        Integer localCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE user_name = 'legacy_bcrypt_user'", Integer.class);
        assertEquals(0, localCountAfter);
    }

    @Test
    public void testSsoUserBypassesLegacyLookup() {
        LegacyMigrationAuthenticationProvider provider = new LegacyMigrationAuthenticationProvider();
        
        // Single-sign-on authentications use custom token classes, so supports(Class) is false for them
        class CustomSsoToken implements Authentication {
            @Override
            public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() { return null; }
            @Override
            public Object getCredentials() { return null; }
            @Override
            public Object getDetails() { return null; }
            @Override
            public Object getPrincipal() { return "sso_user"; }
            @Override
            public boolean isAuthenticated() { return true; }
            @Override
            public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {}
            @Override
            public String getName() { return "sso_user"; }
        }

        assertFalse(provider.supports(CustomSsoToken.class));
        assertNull(provider.authenticate(new CustomSsoToken()));
    }
}
