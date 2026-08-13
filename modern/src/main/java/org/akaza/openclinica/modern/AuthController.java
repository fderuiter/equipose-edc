package org.akaza.openclinica.modern;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.akaza.openclinica.repository.UnifiedRepository;
import org.akaza.openclinica.bean.login.UserAccountBean;
import javax.sql.DataSource;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtEncoder encoder;
    private final KeyPair keyPair;
    private final DataSource dataSource;

    public AuthController(JwtEncoder encoder, KeyPair keyPair, DataSource dataSource) {
        this.encoder = encoder;
        this.keyPair = keyPair;
        this.dataSource = dataSource;
    }

    @PostMapping("/token")
    public String token(
            @org.springframework.web.bind.annotation.RequestParam(value = "tenant_id", required = false) String tenantId,
            Authentication authentication) {
        Instant now = Instant.now();
        // 10 hours token lifetime
        long expiry = 36000L;
        String username = authentication != null ? authentication.getName() : "service_account";
        
        UnifiedRepository repo = new UnifiedRepository(dataSource);
        UserAccountBean userBean = repo.getUserAccountBeanByUserName(username);

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("self")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(expiry))
                .subject(username);
                
        if (userBean != null) {
            claimsBuilder.claim("user_id", (long) userBean.getId());
            claimsBuilder.claim("active_study_id", (long) userBean.getActiveStudyId());
        }
        if (tenantId != null) {
            claimsBuilder.claim("tenant_id", tenantId);
        }
        
        JwtClaimsSet claims = claimsBuilder.build();
        return this.encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    @GetMapping("/jwks.json")
    public Map<String, Object> jwks() {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
            .keyID("openclinica-jwt-key")
            .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return jwkSet.toJSONObject();
    }
}
