package com.shiftgrab.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.shiftgrab.identity.internal.UserAccountRepository;

/** Claim fallbacks and sync rules of {@link CurrentUserService}; the HTTP flow is in {@link IdentityApiTests}. */
class CurrentUserServiceTests {

    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final CurrentUserService service = new CurrentUserService(repository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void failsWithoutJwtAuthentication() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("user", "password", List.of()));

        assertThatThrownBy(service::currentUser).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void returnsStoredProfileWithoutWritingWhenTokenIsUnchanged() {
        authenticate(jwt("sub-1", "An Nguyen", "an@shiftgrab.test", null), "ROLE_STAFF");
        UserAccount stored = account("sub-1", "An Nguyen", "an@shiftgrab.test", Role.STAFF);
        when(repository.findByKeycloakSub("sub-1")).thenReturn(Optional.of(stored));

        assertThat(service.currentUser()).isSameAs(stored);
        verify(repository, never()).upsertProfile(any(), any(), any(), any());
    }

    @Test
    void managerRoleWinsWhenUserHoldsBothRoles() {
        authenticate(jwt("sub-1", "Lan Tran", "lan@shiftgrab.test", null), "ROLE_STAFF", "ROLE_MANAGER");
        when(repository.findByKeycloakSub("sub-1")).thenReturn(Optional.empty());
        when(repository.upsertProfile("sub-1", "Lan Tran", "lan@shiftgrab.test", Role.MANAGER))
                .thenReturn(account("sub-1", "Lan Tran", "lan@shiftgrab.test", Role.MANAGER));

        assertThat(service.currentUser().isManager()).isTrue();
    }

    @Test
    void fallsBackToUsernameAndSyntheticEmailWhenClaimsAreMissing() {
        authenticate(jwt("sub-2", " ", null, "binh"), "ROLE_STAFF");
        when(repository.findByKeycloakSub("sub-2")).thenReturn(Optional.empty());
        UserAccount created = account("sub-2", "binh", "sub-2@users.shiftgrab.invalid", Role.STAFF);
        when(repository.upsertProfile("sub-2", "binh", "sub-2@users.shiftgrab.invalid", Role.STAFF))
                .thenReturn(created);

        assertThat(service.currentUser()).isSameAs(created);
        assertThat(created.isManager()).isFalse();
    }

    @Test
    void fallsBackToSubjectWhenNoNameClaimExists() {
        authenticate(jwt("sub-3", null, "c@shiftgrab.test", null), "ROLE_STAFF");
        when(repository.findByKeycloakSub("sub-3")).thenReturn(Optional.empty());
        UserAccount created = account("sub-3", "sub-3", "c@shiftgrab.test", Role.STAFF);
        when(repository.upsertProfile("sub-3", "sub-3", "c@shiftgrab.test", Role.STAFF)).thenReturn(created);

        assertThat(service.currentUser()).isSameAs(created);
    }

    private static Jwt jwt(String sub, String name, String email, String username) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject(sub);
        if (name != null) {
            builder.claim("name", name);
        }
        if (email != null) {
            builder.claim("email", email);
        }
        if (username != null) {
            builder.claim("preferred_username", username);
        }
        return builder.build();
    }

    private static void authenticate(Jwt jwt, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static UserAccount account(String sub, String name, String email, Role role) {
        return new UserAccount(1L, sub, name, email, role, new BigDecimal("25000.00"), false);
    }
}
