package edu.harbor;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@SpringBootApplication
@EnableScheduling
public class HarborApplication {
    private static final Logger logger = LoggerFactory.getLogger(HarborApplication.class);
    public static void main(String[] args) {
        SpringApplication.run(HarborApplication.class, args);
    }

    @Bean
    ApplicationRunner provisionInitialStaff(JdbcTemplate jdbc, PasswordEncoder encoder) {
        return args -> {
            provision(jdbc, encoder, System.getenv("STAFF_EMAIL"), System.getenv("STAFF_PASSWORD"), "ROLE_STAFF");
            provision(jdbc, encoder, System.getenv("COMMITTEE_EMAIL"), System.getenv("COMMITTEE_PASSWORD"), "ROLE_COMMITTEE");
        };
    }

    private static void provision(JdbcTemplate jdbc, PasswordEncoder encoder, String email, String password, String role) {
        if ((email == null || email.isBlank()) && (password == null || password.isBlank())) {
            logger.warn("No initial account provisioned for {}. Configure its email and password before staff use.", role);
            return;
        }
        if (email == null || email.isBlank() || password == null || password.length() < 16) {
            throw new IllegalStateException(role + " provisioning requires an email and a password of at least 16 characters.");
        }
        String normalizedEmail = email.trim().toLowerCase();
        jdbc.update("""
                INSERT INTO app_users(username, password, enabled) VALUES (?, ?, TRUE)
                ON CONFLICT (username) DO NOTHING
                """, normalizedEmail, encoder.encode(password));
        jdbc.update("INSERT INTO authorities(username, authority) VALUES (?, ?) ON CONFLICT DO NOTHING",
                normalizedEmail, role);
    }

    @Bean
    ApplicationRunner validateConfiguration(DataCipher dataCipher) {
        return args -> dataCipher.validateConfiguration();
    }

    @Bean
    RetentionJob retentionJob(JdbcTemplate jdbc,
                              org.springframework.core.env.Environment environment) {
        return new RetentionJob(jdbc,
                Integer.parseInt(environment.getProperty("app.report-retention-days", "365")),
                Integer.parseInt(environment.getProperty("app.audit-retention-days", "730")));
    }

    static class RetentionJob {
        private final JdbcTemplate jdbc;
        private final int reportDays;
        private final int auditDays;

        RetentionJob(JdbcTemplate jdbc, int reportDays, int auditDays) {
            this.jdbc = jdbc;
            this.reportDays = Math.max(30, reportDays);
            this.auditDays = Math.max(90, auditDays);
        }

        @Scheduled(cron = "0 15 3 * * *")
        void deleteExpiredRecords() {
            jdbc.update("DELETE FROM reports WHERE created_at < NOW() - (? * INTERVAL '1 day')", reportDays);
            jdbc.update("DELETE FROM audit_log WHERE created_at < NOW() - (? * INTERVAL '1 day')", auditDays);
        }
    }
}
