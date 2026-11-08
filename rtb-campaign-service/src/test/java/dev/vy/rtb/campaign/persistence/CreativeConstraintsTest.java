package dev.vy.rtb.campaign.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seam: the creatives table. As with campaigns, CHECK constraints stop a writer that bypasses the
 * domain from storing a creative it would reject. Rows are written with raw SQL on purpose.
 */
class CreativeConstraintsTest {

  private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID CAMPAIGN = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final OffsetDateTime T = OffsetDateTime.parse("2026-11-10T00:00:00Z");

  private JdbcClient jdbc;

  @BeforeEach
  void setUp() {
    PostgresTestSupport.emptyTables();
    jdbc = JdbcClient.create(PostgresTestSupport.dataSource());
    for (UUID advertiser : new UUID[] {A, B}) {
      jdbc.sql("INSERT INTO advertisers (id, name, status) VALUES (:id, 'Advertiser', 'ACTIVE')")
          .param("id", advertiser)
          .update();
    }
    jdbc.sql(
            """
            INSERT INTO campaigns (id, advertiser_id, name, status, daily_budget_micros,
                total_budget_micros, max_bid_cpm_micros, bid_strategy, fixed_cpm_micros,
                pacing_mode, start_at, end_at, version, created_at, updated_at)
            VALUES (:id, :a, 'C', 'DRAFT', 100000000, 1500000000, 2000000, 'FIXED_CPM', 850000,
                'EVEN', :t, :t2, 1, :t, :t)
            """)
        .param("id", CAMPAIGN)
        .param("a", A)
        .param("t", T)
        .param("t2", T.plusDays(30))
        .update();
  }

  private static Map<String, Object> validRow() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID());
    row.put("campaign_id", CAMPAIGN);
    row.put("advertiser_id", A);
    row.put("name", "Banner A");
    row.put("width", 300);
    row.put("height", 250);
    row.put("markup", "<a href=\"${CLICK_URL}\"><img src=\"https://cdn.example.com/a.png\"></a>");
    row.put("landing_url", "https://example.com/landing");
    row.put("status", "ACTIVE");
    row.put("version", 1L);
    row.put("created_at", T);
    row.put("updated_at", T);
    return row;
  }

  private static Map<String, Object> rowWith(Object... overrides) {
    Map<String, Object> row = validRow();
    for (int i = 0; i < overrides.length; i += 2) {
      row.put((String) overrides[i], overrides[i + 1]);
    }
    return row;
  }

  private void insert(Map<String, Object> row) {
    String columns = String.join(", ", row.keySet());
    String placeholders = ":" + String.join(", :", row.keySet());
    JdbcClient.StatementSpec statement =
        jdbc.sql("INSERT INTO creatives (" + columns + ") VALUES (" + placeholders + ")");
    row.forEach(statement::param);
    statement.update();
  }

  static Stream<Arguments> rejected() {
    return Stream.of(
        Arguments.of("blank name", rowWith("name", "  ")),
        Arguments.of("name of 121 characters", rowWith("name", "a".repeat(121))),
        Arguments.of("size 301x250", rowWith("width", 301)),
        Arguments.of("size 728x250", rowWith("width", 728)),
        Arguments.of("unknown status", rowWith("status", "DELETED")),
        Arguments.of("version zero", rowWith("version", 0L)),
        Arguments.of("empty markup", rowWith("markup", "")),
        Arguments.of("markup over 20 KB", rowWith("markup", "a".repeat(20 * 1024 + 1))),
        Arguments.of("script tag", rowWith("markup", "<SCRIPT>x</SCRIPT>")),
        Arguments.of("iframe tag", rowWith("markup", "<iframe src=x>")),
        Arguments.of("javascript url", rowWith("markup", "<a href=\"JavaScript:x\">")),
        Arguments.of("event handler", rowWith("markup", "<img src=x onerror=y>")),
        Arguments.of("http landing url", rowWith("landing_url", "http://example.com")),
        Arguments.of("landing url without host", rowWith("landing_url", "https://")),
        Arguments.of(
            "landing url over 2048", rowWith("landing_url", "https://a.com/" + "a".repeat(2048))),
        Arguments.of("unknown campaign", rowWith("campaign_id", UUID.randomUUID())),
        Arguments.of("advertiser that does not own the campaign", rowWith("advertiser_id", B)));
  }

  @ParameterizedTest(name = "{0} is rejected")
  @MethodSource("rejected")
  void rejectsRowsThatBreakARule(String label, Map<String, Object> row) {
    assertThatThrownBy(() -> insert(row)).isInstanceOf(DataIntegrityViolationException.class);
  }

  static Stream<Arguments> accepted() {
    return Stream.of(
        Arguments.of("the valid example", validRow()),
        Arguments.of("name of 120 characters", rowWith("name", "a".repeat(120))),
        Arguments.of("size 728x90", rowWith("width", 728, "height", 90)),
        Arguments.of("size 320x50", rowWith("width", 320, "height", 50)),
        Arguments.of("size 300x600", rowWith("height", 600)),
        Arguments.of("markup of exactly 20 KB", rowWith("markup", "a".repeat(20 * 1024))),
        Arguments.of("the word onion in text", rowWith("markup", "<p>onion and iframes</p>")),
        Arguments.of("paused", rowWith("status", "PAUSED")),
        Arguments.of("archived", rowWith("status", "ARCHIVED")));
  }

  @ParameterizedTest(name = "{0} is accepted")
  @MethodSource("accepted")
  void acceptsRowsOnTheBoundary(String label, Map<String, Object> row) {
    assertThatCode(() -> insert(row)).doesNotThrowAnyException();
  }
}
