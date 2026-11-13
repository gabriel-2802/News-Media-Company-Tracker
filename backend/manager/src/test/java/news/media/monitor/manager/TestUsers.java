package news.media.monitor.manager;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Throwaway users in the live users schema; delete() cascades to subscriptions, notifications and alerts. */
final class TestUsers {

    private TestUsers() {
    }

    static String email() {
        return "it-" + UUID.randomUUID() + "@example.test";
    }

    static long create(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject("INSERT INTO users.users (email, password, name) VALUES (?, 'x', 'IT') RETURNING id",
                Long.class, email);
    }

    static void deleteAll(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM users.users WHERE email LIKE 'it-%@example.test'");
    }
}
