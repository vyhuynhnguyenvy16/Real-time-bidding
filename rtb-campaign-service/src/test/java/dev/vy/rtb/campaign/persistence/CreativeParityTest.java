package dev.vy.rtb.campaign.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vy.rtb.campaign.domain.CampaignValidationException;
import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.NewCreative;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seam: the Java validator and the database CHECKs must agree on every payload. If the validator
 * accepted something the database refuses, the client would see a 500 instead of a 400; if the
 * database accepted something the validator refuses, the "last barrier" would have a hole.
 */
class CreativeParityTest {

  private static final UUID ADVERTISER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID CAMPAIGN = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private JdbcClient jdbc;

  @BeforeEach
  void setUp() {
    PostgresTestSupport.emptyTables();
    jdbc = JdbcClient.create(PostgresTestSupport.dataSource());
    jdbc.sql("INSERT INTO advertisers (id, name, status) VALUES (:id, 'Advertiser', 'ACTIVE')")
        .param("id", ADVERTISER)
        .update();
    jdbc.sql(
            """
            INSERT INTO campaigns (id, advertiser_id, name, status, daily_budget_micros,
                total_budget_micros, max_bid_cpm_micros, bid_strategy, fixed_cpm_micros,
                pacing_mode, start_at, end_at, version, created_at, updated_at)
            VALUES (:id, :a, 'C', 'DRAFT', 100000000, 1500000000, 2000000, 'FIXED_CPM', 850000,
                'EVEN', now(), now() + interval '30 days', 1, now(), now())
            """)
        .param("id", CAMPAIGN)
        .param("a", ADVERTISER)
        .update();
  }

  static Stream<Arguments> markups() {
    return Stream.of(
        // rejected by both
        Arguments.of("<script>x</script>", true),
        Arguments.of("<SCRIPT src=x>", true),
        Arguments.of("< / script>", true),
        Arguments.of("<scriptx>", true),
        Arguments.of("<scripté>", true),
        Arguments.of("<iframe src=x>", true),
        Arguments.of("<base href=//e>", true),
        Arguments.of("<meta http-equiv=refresh>", true),
        Arguments.of("<link rel=stylesheet href=x>", true),
        Arguments.of("<math><mtext>", true),
        Arguments.of("<template>", true),
        Arguments.of("<a href=\"javascript:x\">", true),
        Arguments.of("<a href=\"JaVa\tScRiPt :x\">", true),
        Arguments.of("<a href=\"java\nscript:x\">", true),
        Arguments.of("<a href=\"java\u000bscript:x\">", true),
        Arguments.of("&#x6A;avascript:x", true),
        Arguments.of("javascript&Colon;x", true),
        Arguments.of("java&Tab;script:x", true),
        Arguments.of("<img src=x onerror=y>", true),
        Arguments.of("<div\tonclick = \"y\">", true),
        Arguments.of("<img/onload=y>", true),
        Arguments.of("${USER_ID}", true),
        Arguments.of("${AUCTION_PRICE", true),
        Arguments.of("${", true),
        Arguments.of("${auction_price}", true),
        // accepted by both (same answer for Unicode whitespace on both sides)
        Arguments.of(
            "<a href=\"${CLICK_URL}\"><img src=\"https://a.b/${AUCTION_PRICE}.png\"></a>", false),
        Arguments.of("<p>onion, iframes, a scripture and a baseline</p>", false),
        Arguments.of("<a href=\"https://a.b/?x=1&y=2\">ok</a>", false),
        Arguments.of(
            "<a href=\"javascript :x\">unicode space is not a browser separator</a>", false),
        Arguments.of("<img src=x onerror=1>", false),
        Arguments.of("Java, JavaScript the language, no colon", false),
        Arguments.of("<img src=\"data:image/png;base64,AAAA\">", false));
  }

  @ParameterizedTest(name = "rejected={1}: {0}")
  @MethodSource("markups")
  void theValidatorAndTheDatabaseAgree(String markup, boolean rejected) {
    NewCreative input =
        NewCreative.builder()
            .name("Banner")
            .width(300)
            .height(250)
            .markup(markup)
            .landingUrl("https://example.com")
            .build();

    boolean validatorRejects;
    try {
      Creative.create(UUID.randomUUID(), CAMPAIGN, ADVERTISER, input);
      validatorRejects = false;
    } catch (CampaignValidationException e) {
      validatorRejects = true;
    }
    boolean databaseRejects;
    try {
      jdbc.sql(
              """
              INSERT INTO creatives (id, campaign_id, advertiser_id, name, width, height, markup,
                  landing_url, status, version, created_at, updated_at)
              VALUES (:id, :c, :a, 'Banner', 300, 250, :markup, 'https://example.com', 'ACTIVE',
                  1, :now, :now)
              """)
          .param("id", UUID.randomUUID())
          .param("c", CAMPAIGN)
          .param("a", ADVERTISER)
          .param("markup", markup)
          .param("now", OffsetDateTime.now())
          .update();
      databaseRejects = false;
    } catch (DataIntegrityViolationException e) {
      databaseRejects = true;
    }

    assertThat(validatorRejects).as("validator").isEqualTo(rejected);
    assertThat(databaseRejects).as("database").isEqualTo(rejected);
  }
}
