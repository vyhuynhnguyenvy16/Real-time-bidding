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
 * Seam: the campaigns table itself. The CHECK constraints are the last line of defence, so a writer
 * that bypasses the domain (a script, another service) still cannot store a campaign it would
 * reject. Rows are written with raw SQL on purpose.
 */
class CampaignConstraintsTest {

  private static final UUID ADVERTISER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-10T00:00:00Z");
  private static final OffsetDateTime END = OffsetDateTime.parse("2026-12-10T00:00:00Z");

  private JdbcClient jdbc;

  @BeforeEach
  void setUp() {
    PostgresTestSupport.emptyTables();
    jdbc = JdbcClient.create(PostgresTestSupport.dataSource());
    jdbc.sql("INSERT INTO advertisers (id, name, status) VALUES (:id, 'Advertiser', 'ACTIVE')")
        .param("id", ADVERTISER)
        .update();
  }

  /** The valid example of docs/api-contract.md 3.2, as column values. */
  private static Map<String, Object> validRow() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID());
    row.put("advertiser_id", ADVERTISER);
    row.put("name", "Thể thao tháng 11");
    row.put("status", "DRAFT");
    row.put("daily_budget_micros", 100_000_000L);
    row.put("total_budget_micros", 1_500_000_000L);
    row.put("max_bid_cpm_micros", 2_000_000L);
    row.put("bid_strategy", "FIXED_CPM");
    row.put("fixed_cpm_micros", 850_000L);
    row.put("target_cpc_micros", null);
    row.put("pacing_mode", "EVEN");
    row.put("freq_cap_count", 3);
    row.put("start_at", START);
    row.put("end_at", END);
    row.put("version", 1L);
    row.put("created_at", START);
    row.put("updated_at", START);
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
        jdbc.sql("INSERT INTO campaigns (" + columns + ") VALUES (" + placeholders + ")");
    row.forEach(statement::param);
    statement.update();
  }

  private static Arguments rejected(String label, Map<String, Object> row) {
    return Arguments.of(label, row);
  }

  static Stream<Arguments> rowsTheDatabaseMustReject() {
    return Stream.of(
        rejected("empty name", rowWith("name", "")),
        rejected("blank name", rowWith("name", "   ")),
        rejected("name of 121 characters", rowWith("name", "a".repeat(121))),
        rejected("unknown status", rowWith("status", "WEIRD")),
        rejected("zero daily budget", rowWith("daily_budget_micros", 0L)),
        rejected("total below daily", rowWith("total_budget_micros", 99_999_999L)),
        rejected("max bid above daily budget", rowWith("max_bid_cpm_micros", 100_000_001L)),
        rejected("zero max bid", rowWith("max_bid_cpm_micros", 0L)),
        rejected("unknown pacing mode", rowWith("pacing_mode", "FAST")),
        rejected("frequency cap of zero", rowWith("freq_cap_count", 0)),
        rejected("end equal to start", rowWith("end_at", START)),
        rejected("version zero", rowWith("version", 0L)),
        rejected("unknown strategy", rowWith("bid_strategy", "OTHER")),
        rejected("fixed CPM without a price", rowWith("fixed_cpm_micros", null)),
        rejected("fixed CPM above max bid", rowWith("fixed_cpm_micros", 2_000_001L)),
        rejected("zero fixed CPM", rowWith("fixed_cpm_micros", 0L)),
        rejected("fixed CPM with a CPC price too", rowWith("target_cpc_micros", 5L)),
        rejected(
            "target CPC of zero",
            rowWith(
                "bid_strategy", "TARGET_CPC", "fixed_cpm_micros", null, "target_cpc_micros", 0L)),
        rejected(
            "target CPC with a fixed CPM too",
            rowWith("bid_strategy", "TARGET_CPC", "target_cpc_micros", 5L)));
  }

  @ParameterizedTest(name = "{0} is rejected")
  @MethodSource("rowsTheDatabaseMustReject")
  void rejectsRowsThatBreakADomainRule(String label, Map<String, Object> row) {
    assertThatThrownBy(() -> insert(row)).isInstanceOf(DataIntegrityViolationException.class);
  }

  static Stream<Arguments> rowsOnTheBoundary() {
    return Stream.of(
        Arguments.of("the valid example", validRow()),
        Arguments.of("name of exactly 120 characters", rowWith("name", "a".repeat(120))),
        Arguments.of("daily budget equal to total", rowWith("total_budget_micros", 100_000_000L)),
        Arguments.of(
            "max bid equal to daily budget",
            rowWith("max_bid_cpm_micros", 100_000_000L, "fixed_cpm_micros", 100_000_000L)),
        Arguments.of("no frequency cap", rowWith("freq_cap_count", null)),
        Arguments.of(
            "a valid target CPC strategy",
            rowWith(
                "bid_strategy", "TARGET_CPC", "fixed_cpm_micros", null, "target_cpc_micros", 1L)));
  }

  @ParameterizedTest(name = "{0} is accepted")
  @MethodSource("rowsOnTheBoundary")
  void acceptsRowsOnTheBoundary(String label, Map<String, Object> row) {
    assertThatCode(() -> insert(row)).doesNotThrowAnyException();
  }
}
