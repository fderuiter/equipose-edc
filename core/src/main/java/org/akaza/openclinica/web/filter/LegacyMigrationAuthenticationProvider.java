package org.akaza.openclinica.web.filter;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class LegacyMigrationAuthenticationProvider implements AuthenticationProvider {

    private static final Logger logger = LoggerFactory.getLogger(LegacyMigrationAuthenticationProvider.class);

    private DataSource dataSource;
    private UserDetailsService userDetailsService;
    private PasswordEncoder passwordEncoder;

    // Legacy DB connection parameters
    private String legacyDbUrl;
    private String legacyDbUser;
    private String legacyDbPassword;

    public LegacyMigrationAuthenticationProvider() {
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setUserDetailsService(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    public void setPasswordEncoder(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public void setLegacyDbUrl(String legacyDbUrl) {
        this.legacyDbUrl = legacyDbUrl;
    }

    public void setLegacyDbUser(String legacyDbUser) {
        this.legacyDbUser = legacyDbUser;
    }

    public void setLegacyDbPassword(String legacyDbPassword) {
        this.legacyDbPassword = legacyDbPassword;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!supports(authentication.getClass())) {
            return null;
        }

        String username = authentication.getName();
        String clearTextPassword = (String) authentication.getCredentials();

        // Check if the user is already present in the local database
        if (localUserExists(username)) {
            logger.debug("User {} exists locally. Bypassing legacy migration.", username);
            return null;
        }

        // Unrecognized account locally, lookup in the legacy database
        logger.info("Unrecognized local account {}. Querying legacy database...", username);
        LegacyUser legacyUser = lookupLegacyUser(username);
        if (legacyUser == null) {
            logger.debug("User {} not found in legacy database.", username);
            return null;
        }

        // Support verifying both legacy seed MD5 hashes and standard BCrypt password hashes
        boolean passwordMatches = false;
        String storedHash = legacyUser.passwordHash;
        if (storedHash == null) {
            storedHash = "";
        }

        if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
            passwordMatches = passwordEncoder.matches(clearTextPassword, storedHash);
        } else {
            // Treat as MD5 hash
            String md5Hex = org.apache.commons.codec.digest.DigestUtils.md5Hex(clearTextPassword);
            passwordMatches = md5Hex.equalsIgnoreCase(storedHash);
        }

        if (!passwordMatches) {
            logger.warn("Password verification failed for legacy user: {}", username);
            throw new BadCredentialsException("Bad credentials for legacy user " + username);
        }

        // Successful validation. Immediate persistence and migration into the central directory.
        logger.info("Legacy user {} verified successfully. Initiating silent migration.", username);
        migrateUser(legacyUser, clearTextPassword);

        // Load the migrated user details using the standard local userDetails service
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        // Return a fully authenticated token
        return new UsernamePasswordAuthenticationToken(userDetails, clearTextPassword, userDetails.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private boolean localUserExists(String username) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM user_account WHERE user_name = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Error checking if local user exists: " + username, e);
        }
        return false;
    }

    private LegacyUser lookupLegacyUser(String username) {
        if (legacyDbUrl == null || legacyDbUrl.trim().isEmpty() || legacyDbUrl.startsWith("${")) {
            logger.warn("Legacy database URL is not configured. Bypassing legacy lookup.");
            return null;
        }

        // Exclude users who utilize external identity providers (empty, *, etc.)
        String query = "SELECT passwd, first_name, last_name, email, active_study, " +
                "institutional_affiliation, user_type_id, enabled, account_non_locked " +
                "FROM user_account WHERE user_name = ? AND passwd IS NOT NULL AND passwd <> '' AND passwd <> '*'";

        try {
            // Ensure connection to legacy database has read-only privileges
            try (Connection conn = getLegacyConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setString(1, username);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            LegacyUser user = new LegacyUser();
                            user.username = username;
                            user.passwordHash = rs.getString("passwd");
                            user.firstName = rs.getString("first_name");
                            user.lastName = rs.getString("last_name");
                            user.email = rs.getString("email");
                            user.activeStudyId = rs.getInt("active_study");
                            user.institutionalAffiliation = rs.getString("institutional_affiliation");
                            user.userTypeId = rs.getInt("user_type_id");
                            user.enabled = rs.getBoolean("enabled");
                            user.accountNonLocked = rs.getBoolean("account_non_locked");
                            return user;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error querying legacy database for user: " + username, e);
        }
        return null;
    }

    protected Connection getLegacyConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(legacyDbUrl, legacyDbUser, legacyDbPassword);
        conn.setReadOnly(true);
        return conn;
    }

    private void migrateUser(LegacyUser legacyUser, String clearTextPassword) {
        // Re-encrypt the password using modern BCrypt
        String newBcryptHash = passwordEncoder.encode(clearTextPassword);

        // Retrieve a new user id from the sequence
        int newUserId = getNextUserId();

        // Default values for standard attributes if missing
        String fName = legacyUser.firstName != null ? legacyUser.firstName : "";
        String lName = legacyUser.lastName != null ? legacyUser.lastName : "";
        String email = legacyUser.email != null ? legacyUser.email : legacyUser.username + "@example.com";
        String affiliation = legacyUser.institutionalAffiliation != null ? legacyUser.institutionalAffiliation : "";
        int studyId = legacyUser.activeStudyId > 0 && isStudyIdValid(legacyUser.activeStudyId) ? legacyUser.activeStudyId : getDefaultStudyId();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO user_account (" +
                     "    user_id, user_name, passwd, first_name, last_name, email, " +
                     "    active_study, institutional_affiliation, status_id, owner_id, " +
                     "    date_created, passwd_challenge_question, passwd_challenge_answer, phone, " +
                     "    user_type_id, enabled, account_non_locked, lock_counter, run_webservices, " +
                     "    access_code, enable_api_key, api_key" +
                     ") VALUES (" +
                     "    ?, ?, ?, ?, ?, ?, " +
                     "    ?, ?, 1, 1, " +
                     "    NOW(), '', '', '', " +
                     "    ?, ?, ?, 0, FALSE, " +
                     "    '', FALSE, ''" +
                     ")")) {
            ps.setInt(1, newUserId);
            ps.setString(2, legacyUser.username);
            ps.setString(3, newBcryptHash);
            ps.setString(4, fName);
            ps.setString(5, lName);
            ps.setString(6, email);
            ps.setInt(7, studyId);
            ps.setString(8, affiliation);
            ps.setInt(9, legacyUser.userTypeId > 0 ? legacyUser.userTypeId : 2);
            ps.setBoolean(10, legacyUser.enabled);
            ps.setBoolean(11, legacyUser.accountNonLocked);
            ps.executeUpdate();
            logger.info("Successfully migrated user {} to local database with modern BCrypt hash.", legacyUser.username);

            // Also provision a default authority (role) so that standard JdbcDaoImpl doesn't throw UsernameNotFoundException due to missing authorities.
            try (PreparedStatement psAuth = conn.prepareStatement("INSERT INTO authorities (username, authority) VALUES (?, 'ROLE_USER')")) {
                psAuth.setString(1, legacyUser.username);
                psAuth.executeUpdate();
            } catch (SQLException ae) {
                logger.warn("Failed to provision default authority for migrated user: " + legacyUser.username, ae);
            }
        } catch (SQLException e) {
            logger.error("Failed to migrate legacy user: " + legacyUser.username, e);
            throw new RuntimeException("Migration failed for user " + legacyUser.username, e);
        }
    }

    private int getNextUserId() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT nextval('user_account_user_id_seq')");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to retrieve next user_id value from sequence.", e);
        }
        return (int) (System.currentTimeMillis() & 0xfffffff); // Fallback if sequence lookup fails
    }

    private int getDefaultStudyId() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT study_id FROM study ORDER BY study_id ASC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to retrieve default study id", e);
        }
        return 1;
    }

    private boolean isStudyIdValid(int studyId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM study WHERE study_id = ?")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Failed to validate study id: " + studyId, e);
        }
        return false;
    }

    private static class LegacyUser {
        String username;
        String passwordHash;
        String firstName;
        String lastName;
        String email;
        int activeStudyId;
        String institutionalAffiliation;
        int userTypeId;
        boolean enabled;
        boolean accountNonLocked;
    }
}
