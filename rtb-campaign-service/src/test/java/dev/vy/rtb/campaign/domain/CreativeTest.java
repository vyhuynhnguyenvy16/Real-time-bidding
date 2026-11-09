package dev.vy.rtb.campaign.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Seam: Creative creation rules (PR05 brief Q1, Q2) and its lifecycle. */
class CreativeTest {

  private static final UUID ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final UUID CAMPAIGN = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ADVERTISER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  static NewCreative.Builder valid() {
    return NewCreative.builder()
        .name("Banner A")
        .width(300)
        .height(250)
        .markup("<a href=\"${CLICK_URL}\"><img src=\"https://cdn.example.com/a.png\"></a>")
        .landingUrl("https://example.com/landing?x=1");
  }

  private static Creative draft(NewCreative input) {
    return Creative.create(ID, CAMPAIGN, ADVERTISER, input);
  }

  @Test
  void aValidCreativeStartsActive() {
    Creative creative = draft(valid().build());

    assertThat(creative.status()).isEqualTo(CreativeStatus.ACTIVE);
    assertThat(creative.campaignId()).isEqualTo(CAMPAIGN);
    assertThat(creative.advertiserId()).isEqualTo(ADVERTISER);
    assertThat(creative.width()).isEqualTo(300);
    assertThat(creative.landingUrl()).isEqualTo("https://example.com/landing?x=1");
  }

  static Stream<Arguments> invalid() {
    String longMarkup = "a".repeat(20 * 1024 + 1);
    return Stream.of(
        Arguments.of("name", valid().name("").build()),
        Arguments.of("name", valid().name("   ").build()),
        Arguments.of("name", valid().name("a".repeat(121)).build()),
        Arguments.of("size", valid().width(301).build()),
        Arguments.of("size", valid().width(728).height(250).build()),
        Arguments.of("markup", valid().markup("").build()),
        Arguments.of("markup", valid().markup(longMarkup).build()),
        Arguments.of("markup", valid().markup("<script>alert(1)</script>").build()),
        Arguments.of("markup", valid().markup("<SCRIPT src=x></SCRIPT>").build()),
        Arguments.of("markup", valid().markup("<iframe src=\"https://a.b\"></iframe>").build()),
        Arguments.of("markup", valid().markup("<a href=\"javascript:alert(1)\">x</a>").build()),
        Arguments.of("markup", valid().markup("<a href=\"JaVaScRiPt:alert(1)\">x</a>").build()),
        Arguments.of("markup", valid().markup("<img src=x onerror=alert(1)>").build()),
        Arguments.of("markup", valid().markup("<div onClick = \"x()\">y</div>").build()),
        Arguments.of("markup", valid().markup("<img src=\"https://a.b/${USER_ID}\">").build()),
        Arguments.of("markup", valid().markup("<a href=\"java\tscript:alert(1)\">x</a>").build()),
        Arguments.of("markup", valid().markup("<a href=\"java\nscript:alert(1)\">x</a>").build()),
        Arguments.of(
            "markup", valid().markup("<a href=\"&#106;avascript:alert(1)\">x</a>").build()),
        Arguments.of(
            "markup", valid().markup("<a href=\"javascript&colon;alert(1)\">x</a>").build()),
        Arguments.of("markup", valid().markup("<base href=\"//evil.example/\">").build()),
        Arguments.of(
            "markup", valid().markup("<meta http-equiv=refresh content=\"0;url=x\">").build()),
        Arguments.of("markup", valid().markup("<form action=\"https://evil.example\">").build()),
        Arguments.of("markup", valid().markup("<style>body{display:none}</style>").build()),
        Arguments.of("markup", valid().markup("<svg onload=alert(1)>").build()),
        Arguments.of("markup", valid().markup("<img src=\"https://a.b/${AUCTION_PRICE\">").build()),
        Arguments.of("markup", valid().markup("<p>a\u0000b</p>").build()),
        Arguments.of("markup", valid().markup("<p>\ud800</p>").build()),
        Arguments.of("name", valid().name("a\u0000b").build()),
        Arguments.of("name", valid().name("\udc00").build()),
        Arguments.of("landingUrl", valid().landingUrl("https://example.com/\u0000").build()),
        Arguments.of("landingUrl", valid().landingUrl("https://google.com@evil.example/").build()),
        Arguments.of("landingUrl", valid().landingUrl("https://user:pw@example.com/").build()),
        Arguments.of(
            "landingUrl", valid().landingUrl("https://example.com/" + "a".repeat(493)).build()),
        Arguments.of("landingUrl", valid().landingUrl("http://example.com").build()),
        Arguments.of("landingUrl", valid().landingUrl("javascript:alert(1)").build()),
        Arguments.of("landingUrl", valid().landingUrl("not a url").build()),
        Arguments.of("landingUrl", valid().landingUrl("https://").build()),
        Arguments.of(
            "landingUrl", valid().landingUrl("https://example.com/" + "a".repeat(2048)).build()));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("invalid")
  void rejectsInputThatBreaksARule(String field, NewCreative input) {
    assertThatThrownBy(() -> draft(input))
        .isInstanceOfSatisfying(
            CampaignValidationException.class,
            e -> assertThat(e.violations()).extracting(Violation::field).contains(field));
  }

  @Test
  void reportsEveryViolationAtOnce() {
    NewCreative input = valid().name("").width(1).markup("<script></script>").build();

    assertThatThrownBy(() -> draft(input))
        .isInstanceOfSatisfying(
            CampaignValidationException.class,
            e ->
                assertThat(e.violations())
                    .extracting(Violation::field)
                    .contains("name", "size", "markup"));
  }

  @ParameterizedTest
  @MethodSource("allowedSizes")
  void acceptsEveryAllowedSize(int width, int height) {
    assertThatCode(() -> draft(valid().width(width).height(height).build()))
        .doesNotThrowAnyException();
  }

  static Stream<Arguments> allowedSizes() {
    return Stream.of(
        Arguments.of(300, 250),
        Arguments.of(728, 90),
        Arguments.of(320, 50),
        Arguments.of(300, 600));
  }

  @Test
  void acceptsTheWhitelistedMacrosAndBoundaryLengths() {
    NewCreative input =
        valid()
            .name("a".repeat(120))
            .markup("x".repeat(20 * 1024 - 30) + "${AUCTION_PRICE}${CLICK_URL}")
            .landingUrl("https://example.com/" + "a".repeat(512 - 20))
            .build();

    assertThatCode(() -> draft(input)).doesNotThrowAnyException();
  }

  @Test
  void aNameIsStrippedAndCountedInCodePointsLikeTheDatabaseDoes() {
    // 100 emoji are 200 UTF-16 units but 100 characters; padding is not part of the name.
    assertThatCode(() -> draft(valid().name("😀".repeat(100)).build())).doesNotThrowAnyException();
    assertThatCode(() -> draft(valid().name("  " + "a".repeat(120) + "  ").build()))
        .doesNotThrowAnyException();
    assertThat(draft(valid().name("  padded  ").build()).name()).isEqualTo("padded");
  }

  @Test
  void hostileInputIsRejectedQuicklyNotAfterQuadraticBacktracking() {
    String[] hostile = {
      "<" + " ".repeat(20_000) + "x",
      "${".repeat(10_000),
      " on" + "a".repeat(20_000),
      "j" + " ".repeat(20_000),
      "<" + " ".repeat(300_000), // far over the size limit: must not even reach the patterns
    };
    for (String markup : hostile) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () -> {
            try {
              draft(valid().markup(markup).build());
            } catch (CampaignValidationException expected) {
              // rejection is fine; only the time matters here
            }
          });
    }
  }

  @Test
  void markupSizeIsCountedInBytesNotCharacters() {
    // 7000 three-byte characters = 21000 bytes > 20 KB although only 7000 characters.
    NewCreative input = valid().markup("€".repeat(7000)).build();

    assertThatThrownBy(() -> draft(input)).isInstanceOf(CampaignValidationException.class);
  }

  // --- lifecycle ----------------------------------------------------------------------------

  @Test
  void pauseResumeArchiveFollowTheStateMachine() {
    Creative active = draft(valid().build());

    Creative paused = active.pause();
    assertThat(paused.status()).isEqualTo(CreativeStatus.PAUSED);
    assertThat(paused.resume().status()).isEqualTo(CreativeStatus.ACTIVE);
    assertThat(active.archive().status()).isEqualTo(CreativeStatus.ARCHIVED);
    assertThat(paused.archive().status()).isEqualTo(CreativeStatus.ARCHIVED);
  }

  @Test
  void repeatingATransitionIsANoOp() {
    Creative active = draft(valid().build());
    Creative paused = active.pause();
    Creative archived = active.archive();

    assertThat(active.resume()).isSameAs(active);
    assertThat(paused.pause()).isSameAs(paused);
    assertThat(archived.archive()).isSameAs(archived);
  }

  @Test
  void anArchivedCreativeCannotComeBack() {
    Creative archived = draft(valid().build()).archive();

    assertThatThrownBy(archived::pause).isInstanceOf(CreativeStateException.class);
    assertThatThrownBy(archived::resume).isInstanceOf(CreativeStateException.class);
  }

  @Test
  void reviseKeepsIdentityAndStatusButRevalidates() {
    Creative paused = draft(valid().build()).pause();

    Creative revised = paused.revise(valid().name("Banner B").width(728).height(90).build());

    assertThat(revised.id()).isEqualTo(ID);
    assertThat(revised.status()).isEqualTo(CreativeStatus.PAUSED);
    assertThat(revised.name()).isEqualTo("Banner B");
    assertThatThrownBy(() -> paused.revise(valid().width(1).build()))
        .isInstanceOf(CampaignValidationException.class);
  }

  @Test
  void anArchivedCreativeCannotBeRevised() {
    Creative archived = draft(valid().build()).archive();

    assertThatThrownBy(() -> archived.revise(valid().build()))
        .isInstanceOf(CreativeStateException.class);
  }

  @Test
  void equalCreativesAreEqual() {
    assertThat(draft(valid().build())).isEqualTo(draft(valid().build()));
    assertThat(List.of(draft(valid().build()))).hasSize(1);
  }
}
