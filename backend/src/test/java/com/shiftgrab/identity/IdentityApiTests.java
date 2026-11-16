package com.shiftgrab.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.TestcontainersConfiguration;
import com.shiftgrab.shared.security.KeycloakRealmRoleConverter;

/** SG-10 acceptance criteria with simulated JWTs. The real Keycloak round-trip is in {@link KeycloakIntegrationTests}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, IdentityApiTests.ManagerOnlyEndpoint.class })
class IdentityApiTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KeycloakRealmRoleConverter roleConverter;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("AC2: request without a token is rejected with 401")
    void rejectsAnonymousRequests() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC3: STAFF calling a MANAGER endpoint gets 403")
    void staffCannotCallManagerEndpoint() throws Exception {
        mvc.perform(get("/api/test/manager-only").with(token("staff-1", "An Nguyen", "STAFF")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/test/manager-only").with(token("manager-1", "Lan Tran", "MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC4: first call provisions the local profile from token claims")
    void provisionsUserOnFirstCall() throws Exception {
        mvc.perform(get("/api/v1/me").with(token("staff-1", "An Nguyen", "STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("An Nguyen"))
                .andExpect(jsonPath("$.email").value("staff-1@shiftgrab.test"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.hourlyRate").value(25000.00));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("Profile changes in Keycloak are synchronised without creating duplicates")
    void updatesProfileWhenTokenChanges() throws Exception {
        mvc.perform(get("/api/v1/me").with(token("staff-1", "An Nguyen", "STAFF"))).andExpect(status().isOk());
        jdbc.update("UPDATE users SET hourly_rate = 40000 WHERE keycloak_sub = 'staff-1'");

        mvc.perform(get("/api/v1/me").with(token("staff-1", "An Nguyen Van", "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("An Nguyen Van"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.hourlyRate").value(40000.00)); // ShiftGrab-owned data is preserved

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("A token without a ShiftGrab role is rejected with 403")
    void rejectsTokenWithoutApplicationRole() throws Exception {
        mvc.perform(get("/api/v1/me").with(token("outsider", "Outsider", "offline_access")))
                .andExpect(status().isForbidden());
    }

    private JwtRequestPostProcessor token(String sub, String name, String role) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("name", name)
                        .claim("email", sub + "@shiftgrab.test")
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(roleConverter);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class ManagerOnlyEndpoint {

        @GetMapping("/api/test/manager-only")
        @PreAuthorize("hasRole('MANAGER')")
        String managerOnly() {
            return "ok";
        }
    }
}
