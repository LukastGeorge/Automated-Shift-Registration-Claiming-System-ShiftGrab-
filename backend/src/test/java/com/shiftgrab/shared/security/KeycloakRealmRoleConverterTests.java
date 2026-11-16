package com.shiftgrab.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTests {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void mapsApplicationRealmRolesToAuthorities() {
        Jwt jwt = jwtWithClaim("realm_access", Map.of("roles", List.of("STAFF", "MANAGER")));

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_STAFF", "ROLE_MANAGER");
    }

    @Test
    void ignoresKeycloakBuiltInRoles() {
        Jwt jwt = jwtWithClaim("realm_access",
                Map.of("roles", List.of("offline_access", "uma_authorization", "default-roles-shiftgrab", "STAFF")));

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_STAFF");
    }

    @Test
    void returnsNoAuthoritiesWhenClaimIsMissing() {
        Jwt jwt = jwtWithClaim("scope", "openid");

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("token").header("alg", "none").subject("user-1").claim(name, value).build();
    }
}
