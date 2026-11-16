package com.shiftgrab.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.shiftgrab.identity.Role;
import com.shiftgrab.identity.UserAccount;

@Repository
public class UserAccountRepository {

    private static final String COLUMNS = "id, keycloak_sub, full_name, email, role, hourly_rate, reminder_opt_in";

    private final JdbcClient jdbc;

    UserAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByKeycloakSub(String keycloakSub) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE keycloak_sub = :sub")
                .param("sub", keycloakSub)
                .query(UserAccountRepository::map)
                .optional();
    }

    /**
     * Insert-or-update in one statement. Two first requests from the same user racing each other both
     * succeed: the loser of the race hits ON CONFLICT and updates instead of failing on the unique key.
     */
    public UserAccount upsertProfile(String keycloakSub, String fullName, String email, Role role) {
        return jdbc.sql("""
                INSERT INTO users (keycloak_sub, full_name, email, role)
                VALUES (:sub, :fullName, :email, :role)
                ON CONFLICT (keycloak_sub) DO UPDATE
                   SET full_name = EXCLUDED.full_name, email = EXCLUDED.email, role = EXCLUDED.role
                RETURNING %s
                """.formatted(COLUMNS))
                .param("sub", keycloakSub)
                .param("fullName", fullName)
                .param("email", email)
                .param("role", role.name())
                .query(UserAccountRepository::map)
                .single();
    }

    private static UserAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
                rs.getLong("id"),
                rs.getString("keycloak_sub"),
                rs.getString("full_name"),
                rs.getString("email"),
                Role.valueOf(rs.getString("role")),
                rs.getBigDecimal("hourly_rate"),
                rs.getBoolean("reminder_opt_in"));
    }
}
