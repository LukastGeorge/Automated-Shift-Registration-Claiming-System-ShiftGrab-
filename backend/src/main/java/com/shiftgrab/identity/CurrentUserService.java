package com.shiftgrab.identity;

import java.util.Collection;
import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import com.shiftgrab.identity.internal.UserAccountRepository;

/**
 * Resolves the authenticated caller to a local {@link UserAccount}, provisioning it on first use (SG-10 AC4).
 * Profile data (name, e-mail, role) is owned by Keycloak and refreshed whenever the token disagrees with the
 * stored copy; ShiftGrab-owned data (hourly rate, preferences) is never overwritten.
 */
@Service
public class CurrentUserService {

    private final UserAccountRepository repository;

    CurrentUserService(UserAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * Role of the caller, read from the token only. Use this for read-only authorisation decisions: unlike
     * {@link #currentUser()} it never writes, so it is safe inside read-only transactions.
     */
    public Role currentRole() {
        return roleFrom(jwtAuthentication().getAuthorities());
    }

    public UserAccount currentUser() {
        JwtAuthenticationToken token = jwtAuthentication();
        Jwt jwt = token.getToken();
        Role role = roleFrom(token.getAuthorities());
        String fullName = firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("preferred_username"),
                jwt.getSubject());
        String email = firstNonBlank(jwt.getClaimAsString("email"), jwt.getSubject() + "@users.shiftgrab.invalid");

        return repository.findByKeycloakSub(jwt.getSubject())
                .filter(existing -> Objects.equals(existing.fullName(), fullName)
                        && Objects.equals(existing.email(), email)
                        && existing.role() == role)
                .orElseGet(() -> repository.upsertProfile(jwt.getSubject(), fullName, email, role));
    }

    private static JwtAuthenticationToken jwtAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AuthenticationCredentialsNotFoundException("No JWT authentication in security context");
        }
        return token;
    }

    /** MANAGER wins if a user somehow holds both roles; a token with neither role is rejected. */
    private static Role roleFrom(Collection<? extends GrantedAuthority> authorities) {
        boolean manager = authorities.stream().anyMatch(a -> "ROLE_MANAGER".equals(a.getAuthority()));
        boolean staff = authorities.stream().anyMatch(a -> "ROLE_STAFF".equals(a.getAuthority()));
        if (manager) {
            return Role.MANAGER;
        }
        if (staff) {
            return Role.STAFF;
        }
        throw new AccessDeniedException("Token has no ShiftGrab role");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("No non-blank value");
    }
}
