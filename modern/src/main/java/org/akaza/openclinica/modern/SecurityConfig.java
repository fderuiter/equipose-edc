package org.akaza.openclinica.modern;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import org.akaza.openclinica.modern.filter.LegacyModernContextBridgeFilter;
import org.akaza.openclinica.repository.UnifiedRepository;
import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final DataSource dataSource;

    public SecurityConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    public SecurityFilterChain unifiedFilterChain(HttpSecurity http) throws Exception {
        UnifiedRepository unifiedRepository = new UnifiedRepository(dataSource);
        LegacyModernContextBridgeFilter bridgeFilter = new LegacyModernContextBridgeFilter(dataSource, unifiedRepository);

        http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/auth/token", "/api/auth/jwks.json", "/api/auth/config", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()))
            .oauth2Login(withDefaults())
            .saml2Login(withDefaults())
            .csrf(csrf -> csrf.disable())
            .httpBasic(withDefaults())
            .addFilterAfter(bridgeFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.withUsername("service_account")
            .password("{noop}password")
            .authorities("read")
            .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public KeyPair keyPair() {
        try {
            Path privateKeyPath = Paths.get("jwt-private.key");
            Path publicKeyPath = Paths.get("jwt-public.key");

            if (Files.exists(privateKeyPath) && Files.exists(publicKeyPath)) {
                byte[] privateKeyBytes = Files.readAllBytes(privateKeyPath);
                byte[] publicKeyBytes = Files.readAllBytes(publicKeyPath);

                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
                RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

                return new KeyPair(publicKey, privateKey);
            } else {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
                keyPairGenerator.initialize(2048);
                KeyPair keyPair = keyPairGenerator.generateKeyPair();

                Files.write(privateKeyPath, keyPair.getPrivate().getEncoded());
                Files.write(publicKeyPath, keyPair.getPublic().getEncoded());

                return keyPair;
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Bean
    public JwtDecoder jwtDecoder(KeyPair keyPair) {
        JwtDecoder localDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        return new DynamicJwtDecoder(localDecoder);
    }

    private static class DynamicJwtDecoder implements JwtDecoder {
        private final JwtDecoder localDecoder;
        private final java.util.concurrent.ConcurrentHashMap<String, JwtDecoder> externalDecoders = new java.util.concurrent.ConcurrentHashMap<>();

        public DynamicJwtDecoder(JwtDecoder localDecoder) {
            this.localDecoder = localDecoder;
        }

        @Override
        public Jwt decode(String token) throws JwtException {
            String provider = System.getenv("OIDC_PROVIDER");
            if (provider == null || provider.trim().isEmpty() || "local".equalsIgnoreCase(provider)) {
                return localDecoder.decode(token);
            }

            String jwkSetUri = System.getenv("OIDC_JWK_SET_URI");
            String issuerUri = System.getenv("OIDC_ISSUER_URI");

            if ((jwkSetUri == null || jwkSetUri.trim().isEmpty()) && (issuerUri == null || issuerUri.trim().isEmpty())) {
                return localDecoder.decode(token);
            }

            String lookupKey = jwkSetUri != null ? jwkSetUri : issuerUri;
            JwtDecoder externalDecoder = externalDecoders.computeIfAbsent(lookupKey, key -> {
                if (jwkSetUri != null && !jwkSetUri.trim().isEmpty()) {
                    return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
                } else {
                    return NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
                }
            });

            return externalDecoder.decode(token);
        }
    }

    @Bean
    public JwtEncoder jwtEncoder(KeyPair keyPair) {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
            .privateKey((RSAPrivateKey) keyPair.getPrivate())
            .keyID("openclinica-jwt-key")
            .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }
}
