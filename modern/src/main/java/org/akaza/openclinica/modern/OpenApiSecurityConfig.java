package org.akaza.openclinica.modern;

import org.akaza.openclinica.web.filter.UnifiedSessionAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class OpenApiSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
            .authorizeHttpRequests(authorize -> authorize
                .anyRequest().permitAll()
            )
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<UnifiedSessionAuthenticationFilter> apiSecurityFilterRegistration(UnifiedSessionAuthenticationFilter apiSecurityFilter) {
        FilterRegistrationBean<UnifiedSessionAuthenticationFilter> registration = new FilterRegistrationBean<>(apiSecurityFilter);
        registration.setEnabled(false);
        return registration;
    }
}
