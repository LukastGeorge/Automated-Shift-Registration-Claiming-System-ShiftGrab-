package com.shiftgrab.concurrency;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Real signed tokens for black-box tests over HTTP, without Keycloak: the backend instances started by these
 * tests verify HMAC signatures with the same key. Never used outside tests.
 */
final class TestJwt {

    private static final byte[] SECRET = "shiftgrab-concurrency-tests-secret-key-32b".getBytes(StandardCharsets.UTF_8);

    private TestJwt() {
    }

    static String staff(String subject) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("name", "Staff " + subject)
                    .claim("email", subject + "@load.test")
                    .claim("realm_access", Map.of("roles", List.of("STAFF")))
                    .issueTime(java.util.Date.from(Instant.now()))
                    .expirationTime(java.util.Date.from(Instant.now().plusSeconds(600)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replaces the Keycloak-backed decoder in the backend instances started by the tests. */
    @TestConfiguration(proxyBeanMethods = false)
    static class DecoderConfig {

        @Bean
        JwtDecoder testJwtDecoder() {
            return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(SECRET, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        }
    }
}
