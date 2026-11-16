package com.shiftgrab.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shiftgrab.TestcontainersConfiguration;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * End-to-end authentication against a real Keycloak started from the same realm file as docker-compose:
 * real token issuance, signature verification via JWKS, issuer and audience validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class KeycloakIntegrationTests {

    @Container
    static final KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.8.2")
            .withRealmImportFile("/realm-shiftgrab.json");

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/shiftgrab");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void realKeycloakTokenIsAcceptedAndProvisionsTheUser() throws Exception {
        String accessToken = passwordGrant("staff1", "staff123");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("An Nguyen"))
                .andExpect(jsonPath("$.email").value("staff1@shiftgrab.local"))
                .andExpect(jsonPath("$.role").value("STAFF"));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String accessToken = passwordGrant("staff1", "staff123");
        String tampered = accessToken.substring(0, accessToken.length() - 4) + "AAAA";

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @SuppressWarnings("unchecked")
    private static String passwordGrant(String username, String password) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "shiftgrab-dev-cli");
        form.add("username", username);
        form.add("password", password);
        Map<String, Object> response = RestClient.create()
                .post()
                .uri(keycloak.getAuthServerUrl() + "/realms/shiftgrab/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        return (String) response.get("access_token");
    }
}
