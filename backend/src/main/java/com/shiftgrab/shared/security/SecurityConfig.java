package com.shiftgrab.shared.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless resource server (ADR-0003). Every API call carries a Keycloak-issued JWT whose signature,
 * issuer and audience ({@code shiftgrab-api}) are validated; no server-side session exists.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
            JsonMapper jsonMapper) throws Exception {
        var problemHandler = new ProblemDetailsSecurityHandler(jsonMapper);
        return http
                // Bearer tokens are not sent automatically by browsers, so CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // WebSocket handshake: browsers cannot send a bearer header here; the token is checked on
                        // the STOMP CONNECT frame instead (realtime module, SG-40).
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        // Role rules on the URL run before Spring MVC validates bodies and parameters, so the
                        // wrong role gets 403 without learning the endpoint's input rules (SG-65). The
                        // controllers' @PreAuthorize stays as a second layer.
                        .requestMatchers(HttpMethod.POST, "/api/v1/shifts").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/shifts/*").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/shifts/*/cancel").hasRole("MANAGER")
                        .requestMatchers("/api/v1/shift-templates/**", "/api/v1/users/**", "/api/v1/audit-log/**",
                                "/api/v1/reports/**")
                        .hasRole("MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/shifts/*/registrations").hasRole("STAFF")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/registrations/*").hasRole("STAFF")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .build();
    }

    @Bean
    KeycloakRealmRoleConverter keycloakRealmRoleConverter() {
        return new KeycloakRealmRoleConverter();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(KeycloakRealmRoleConverter roleConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roleConverter);
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${shiftgrab.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match"));
        config.setExposedHeaders(List.of("Location", "ETag", "Retry-After"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
