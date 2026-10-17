package edu.harbor;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class HarborController {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Set<String> CATEGORIES = Set.of(
            "Harassment or unwanted attention", "Discrimination", "Bullying or intimidation", "Something else");
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "RECEIVED", "Received", "REVIEWING", "Under review",
            "FOLLOW_UP", "Follow-up in progress", "CLOSED", "Closed");
    private final JdbcTemplate jdbc;
    private final DataCipher cipher;
    private final SecureRandom random = new SecureRandom();
    private final int reportRetentionDays;
    private final int auditRetentionDays;

    public HarborController(JdbcTemplate jdbc, DataCipher cipher,
                            @Value("${app.report-retention-days:365}") int reportRetentionDays,
                            @Value("${app.audit-retention-days:730}") int auditRetentionDays) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.reportRetentionDays = Math.max(30, reportRetentionDays);
        this.auditRetentionDays = Math.max(90, auditRetentionDays);
    }

    public record NewReport(
            @NotBlank @Size(max = 120) String category,
            @NotBlank @Size(max = 8000) String details,
            @Size(max = 200) String person,
            @Size(max = 320) String contact) { }

    public record NewStatus(@NotBlank @Size(max = 20) String reference,
                            @NotBlank @Size(max = 80) String accessCode) { }
    public record StatusUpdate(@NotBlank String status, @Size(max = 1000) String note,
                               @Size(max = 320) String assignedTo) { }
    public record PatternDecision(@NotBlank String decision) { }
    private record ReportRow(UUID id, String reference, String category, String details,
                             String person, String contact, String status, String publicUpdate,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    private record PatternRow(UUID id, String reference, String category, String details,
                              String person, OffsetDateTime createdAt) { }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken token, HttpServletResponse response) {
        noCache(response);
        return Map.of("token", token.getToken());
    }

    @GetMapping("/auth/me")
    public Map<String, Object> identity(Authentication authentication, HttpServletResponse response) {
        noCache(response);
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(value -> value.equals("ROLE_STAFF") || value.equals("ROLE_COMMITTEE"))
                .map(value -> value.substring(5)).findFirst().orElse("REPORTER");
        return Map.of("role", role, "reportRetentionDays", reportRetentionDays,
                "auditRetentionDays", auditRetentionDays);
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createReport(@Valid @RequestBody NewReport request) {
        if (!CATEGORIES.contains(request.category())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a report category from the list.");
        }
        String reference = newReference();
        String accessCode = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reports(id, reference_code, access_hash, category, details_cipher, person_cipher, contact_cipher)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, reference, hash(accessCode), request.category(), cipher.encrypt(request.details().trim()),
                cipher.encrypt(blankToNull(request.person())), cipher.encrypt(blankToNull(request.contact())));
        audit("REPORTER", "REPORT_SUBMITTED", id);
        return Map.of("reference", reference, "accessCode", accessCode);
    }

    @PostMapping("/status")
    public Map<String, Object> checkStatus(@Valid @RequestBody NewStatus request, HttpServletResponse response) {
        noCache(response);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, public_update, updated_at
                FROM reports WHERE reference_code = ? AND access_hash = ?
                """, request.reference().trim().toUpperCase(), hash(request.accessCode()));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Those details did not match a report. Check both codes and try again.");
        }
        Map<String, Object> row = rows.getFirst();
        String status = (String) row.get("status");
        return Map.of("status", status, "statusLabel", STATUS_LABELS.getOrDefault(status, "In progress"),
                "publicUpdate", Optional.ofNullable((String) row.get("public_update")).orElse(""),
                "updatedAt", row.get("updated_at").toString());
    }

    @GetMapping("/cases")
    public List<Map<String, Object>> cases(Authentication authentication, HttpServletResponse response) {
        noCache(response);
        if (!hasRole(authentication, "ROLE_STAFF")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This workspace is for case staff.");
        }
        audit(authentication.getName(), "CASE_QUEUE_VIEWED", null);
        return jdbc.query("""
                SELECT id, reference_code, category, details_cipher, status, assigned_to, created_at
                FROM reports ORDER BY created_at DESC LIMIT 100
                """, (rs, rowNum) -> {
            String details = cipher.decrypt(rs.getString("details_cipher"));
            return Map.<String, Object>of(
                    "id", rs.getObject("id", UUID.class).toString(),
                    "reference", rs.getString("reference_code"),
                    "category", rs.getString("category"),
                    "preview", details.length() > 190 ? details.substring(0, 190) + "…" : details,
                    "status", rs.getString("status"),
                    "statusLabel", STATUS_LABELS.getOrDefault(rs.getString("status"), "In progress"),
                    "assignedTo", rs.getString("assigned_to") == null ? "" : rs.getString("assigned_to"),
                    "createdAt", rs.getObject("created_at", OffsetDateTime.class).toString());
        });
    }

    @GetMapping("/cases/{id}")
    public Map<String, Object> caseDetails(@PathVariable UUID id, Authentication authentication) {
        requireStaff(authentication);
        ReportRow report = findReport(id);
        audit(authentication.getName(), "CASE_VIEWED", id);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", report.reference());
        details.put("category", report.category());
        details.put("details", report.details());
        details.put("person", report.person());
        details.put("contact", report.contact());
        details.put("status", report.status());
        details.put("createdAt", report.createdAt().toString());
        details.put("actions", jdbc.query("""
                SELECT action, actor, note_cipher, created_at
                FROM case_actions WHERE report_id = ? ORDER BY created_at ASC
                """, (rs, rowNum) -> Map.of(
                "action", rs.getString("action"),
                "actor", rs.getString("actor"),
                "note", Optional.ofNullable(cipher.decrypt(rs.getString("note_cipher"))).orElse(""),
                "createdAt", rs.getObject("created_at", OffsetDateTime.class).toString()), id));
        return details;
    }

    @PostMapping("/cases/{id}/update")
    public Map<String, String> updateCase(@PathVariable UUID id, @Valid @RequestBody StatusUpdate request,
                                           Authentication authentication) {
        requireStaff(authentication);
        if (!STATUS_LABELS.containsKey(request.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid case status.");
        }
        findReport(id);
        String note = blankToNull(request.note());
        String assignee = request.assignedTo() == null ? null : request.assignedTo().trim().toLowerCase(Locale.ROOT);
        if (assignee != null && !assignee.isEmpty()
                && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM app_users u JOIN authorities a ON a.username=u.username
                              WHERE u.username=? AND u.enabled=TRUE AND a.authority='ROLE_STAFF')
                """, Boolean.class, assignee))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an active case staff account for assignment.");
        }
        String publicUpdate = note == null ? null : "The support team added an update to your case.";
        jdbc.update("""
                UPDATE reports SET status = ?,
                    assigned_to = CASE WHEN ? IS NULL THEN assigned_to ELSE NULLIF(?, '') END,
                    public_update = COALESCE(?, public_update), updated_at = NOW()
                WHERE id = ?
                """, request.status(), assignee, assignee, publicUpdate, id);
        jdbc.update("INSERT INTO case_actions(id, report_id, actor, action, note_cipher) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, authentication.getName(), request.status(), cipher.encrypt(note));
        audit(authentication.getName(), "CASE_UPDATED", id);
        if (assignee != null) {
            jdbc.update("INSERT INTO case_actions(id, report_id, actor, action, note_cipher) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), id, authentication.getName(), "ASSIGNED_TO", cipher.encrypt(assignee));
            audit(authentication.getName(), assignee.isEmpty() ? "CASE_UNASSIGNED" : "CASE_ASSIGNED", id);
        }
        return Map.of("status", request.status());
    }

    @GetMapping("/patterns")
    public List<Map<String, Object>> patterns(Authentication authentication, HttpServletResponse response) {
        requireCommittee(authentication);
        noCache(response);
        List<PatternRow> reports = jdbc.query("""
                SELECT id, reference_code, category, details_cipher, person_cipher, created_at FROM reports
                WHERE created_at > NOW() - INTERVAL '180 days'
                ORDER BY created_at DESC LIMIT 100
                """, (rs, rowNum) -> new PatternRow(rs.getObject("id", UUID.class),
                rs.getString("reference_code"), rs.getString("category"),
                cipher.decrypt(rs.getString("details_cipher")), cipher.decrypt(rs.getString("person_cipher")),
                rs.getObject("created_at", OffsetDateTime.class)));
        Set<String> reviewedPairs = new HashSet<>(jdbc.query("""
                SELECT first_id, second_id FROM pattern_reviews
                """, (rs, rowNum) -> rs.getObject("first_id", UUID.class) + "|" +
                rs.getObject("second_id", UUID.class)));
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (int i = 0; i < reports.size(); i++) {
            for (int j = i + 1; j < reports.size(); j++) {
                PatternRow first = reports.get(i);
                PatternRow second = reports.get(j);
                UUID lower = first.id().compareTo(second.id()) < 0 ? first.id() : second.id();
                UUID upper = first.id().compareTo(second.id()) < 0 ? second.id() : first.id();
                if (reviewedPairs.contains(lower + "|" + upper)) continue;
                int similarity = similarity(first, second);
                if (similarity < 25) continue;
                suggestions.add(Map.of("firstId", first.id().toString(), "secondId", second.id().toString(),
                        "firstReference", first.reference(), "secondReference", second.reference(),
                        "firstCategory", first.category(), "secondCategory", second.category(),
                        "firstDate", first.createdAt().toLocalDate().toString(),
                        "secondDate", second.createdAt().toLocalDate().toString(),
                        "similarity", similarity));
            }
        }
        audit(authentication.getName(), "PATTERN_LEADS_VIEWED", null);
        return suggestions;
    }

    @PostMapping("/patterns/{firstId}/{secondId}/review")
    public Map<String, String> reviewPattern(@PathVariable UUID firstId, @PathVariable UUID secondId,
                                             @Valid @RequestBody PatternDecision decision,
                                             Authentication authentication) {
        requireCommittee(authentication);
        if (firstId.equals(secondId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose two different reports.");
        if (!decision.decision().equals("CONFIRM") && !decision.decision().equals("DISMISS")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose confirm or dismiss.");
        }
        UUID lower = firstId.compareTo(secondId) < 0 ? firstId : secondId;
        UUID upper = firstId.compareTo(secondId) < 0 ? secondId : firstId;
        findReport(lower);
        findReport(upper);
        jdbc.update("""
                INSERT INTO pattern_reviews(first_id, second_id, decision, reviewer)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (first_id, second_id) DO UPDATE SET decision=EXCLUDED.decision,
                    reviewer=EXCLUDED.reviewer, reviewed_at=NOW()
                """, lower, upper, decision.decision(), authentication.getName());
        audit(authentication.getName(), decision.decision().equals("CONFIRM") ? "PATTERN_LINK_CONFIRMED" : "PATTERN_SUGGESTION_DISMISSED", null);
        return Map.of("decision", decision.decision());
    }

    @GetMapping("/admin/retention")
    public Map<String, Integer> retention(HttpServletResponse response) {
        noCache(response);
        return Map.of("reportRetentionDays", reportRetentionDays, "auditRetentionDays", auditRetentionDays);
    }

    private void requireStaff(Authentication authentication) {
        if (!hasRole(authentication, "ROLE_STAFF")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This workspace is for case staff.");
        }
    }

    private void requireCommittee(Authentication authentication) {
        if (!hasRole(authentication, "ROLE_COMMITTEE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pattern leads are available only to authorized committee members.");
        }
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals(role));
    }

    private ReportRow findReport(UUID id) {
        List<ReportRow> rows = jdbc.query("""
                SELECT id, reference_code, category, details_cipher, person_cipher, contact_cipher, status,
                       public_update, created_at, updated_at FROM reports WHERE id = ?
                """, (rs, rowNum) -> toReport(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That case is not available.");
        return rows.getFirst();
    }

    private ReportRow toReport(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReportRow(rs.getObject("id", UUID.class), rs.getString("reference_code"),
                rs.getString("category"), cipher.decrypt(rs.getString("details_cipher")),
                cipher.decrypt(rs.getString("person_cipher")), cipher.decrypt(rs.getString("contact_cipher")),
                rs.getString("status"), rs.getString("public_update"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private int similarity(PatternRow first, PatternRow second) {
        Set<String> firstWords = words(first.details());
        Set<String> secondWords = words(second.details());
        Set<String> intersection = new HashSet<>(firstWords);
        intersection.retainAll(secondWords);
        Set<String> union = new HashSet<>(firstWords);
        union.addAll(secondWords);
        double phraseScore = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
        boolean sameTarget = first.person() != null && second.person() != null
                && first.person().trim().equalsIgnoreCase(second.person().trim());
        boolean sameCategory = first.category().equals(second.category());
        double score = Math.min(1, phraseScore * 0.65 + (sameCategory ? 0.12 : 0) + (sameTarget ? 0.45 : 0));
        return (int) Math.round(score * 100);
    }

    private Set<String> words(String text) {
        Set<String> stopWords = Set.of("about", "after", "again", "been", "being", "could", "their", "there",
                "these", "those", "this", "that", "they", "them", "then", "when", "where", "which", "while",
                "with", "would", "from", "have", "what", "were", "your", "into", "also", "very", "more");
        Set<String> tokens = new HashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 5 && !stopWords.contains(word)) tokens.add(word);
        }
        return tokens;
    }

    private void audit(String actor, String action, UUID recordId) {
        jdbc.update("INSERT INTO audit_log(id, actor, action, record_id) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), actor, action, recordId);
    }

    private void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
    }

    private String newReference() {
        StringBuilder result = new StringBuilder("CR-");
        for (int i = 0; i < 10; i++) result.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return result.toString();
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect access code");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
