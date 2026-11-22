package com.shiftgrab.identity.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.identity.Role;
import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.shared.web.PageResponse;

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
     *
     * <p>Runs in its own transaction: profile provisioning can happen on any request, including inside a
     * read-only transaction (a user's first {@code GET}), which would otherwise reject the write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

    public Optional<UserAccount> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id")
                .param("id", id)
                .query(UserAccountRepository::map)
                .optional();
    }

    public List<UserAccount> findStaffWithReminders() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE role = 'STAFF' AND reminder_opt_in ORDER BY id")
                .query(UserAccountRepository::map)
                .list();
    }

    public UserAccount updateReminderOptIn(long id, boolean optIn) {
        return jdbc.sql("UPDATE users SET reminder_opt_in = :optIn WHERE id = :id RETURNING " + COLUMNS)
                .param("id", id)
                .param("optIn", optIn)
                .query(UserAccountRepository::map)
                .single();
    }

    /** Locks the row so that two managers changing the same rate are serialised and the old value is exact. */
    public Optional<UserAccount> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(UserAccountRepository::map)
                .optional();
    }

    public UserAccount updateHourlyRate(long id, BigDecimal hourlyRate) {
        return jdbc.sql("UPDATE users SET hourly_rate = :rate WHERE id = :id RETURNING " + COLUMNS)
                .param("id", id)
                .param("rate", hourlyRate)
                .query(UserAccountRepository::map)
                .single();
    }

    /** Users sorted by name; {@code role} null means all roles. */
    public PageResponse<UserAccount> search(Role role, int page, int size) {
        String where = role == null ? "" : " WHERE role = :role";
        Map<String, Object> params = new HashMap<>();
        if (role != null) {
            params.put("role", role.name());
        }
        long total = jdbc.sql("SELECT count(*) FROM users" + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<UserAccount> content = jdbc.sql("SELECT " + COLUMNS + " FROM users" + where
                        + " ORDER BY full_name, id LIMIT :limit OFFSET :offset")
                .params(params)
                .query(UserAccountRepository::map)
                .list();
        return PageResponse.of(content, page, size, total);
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
