package dev.vy.rtb.campaign.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creative rules (PR05 brief Q1, Q2). Markup that looks dangerous is rejected, never "fixed":
 * rewriting HTML is how sanitizers leak. This is a deny-list, so it is a first layer and not a
 * proof of safety; the exchange still has to sandbox what it renders.
 *
 * <p>The regular expressions are written to run in linear time (no two adjacent unbounded groups)
 * and use explicit ASCII character classes. V2__creatives.sql repeats them with the same classes,
 * so the validator and the database CHECK accept and reject exactly the same text
 * (CreativeParityTest keeps them in step).
 */
final class CreativeValidator {

  static final int MAX_NAME = 120;
  static final int MAX_MARKUP_BYTES = 20 * 1024;
  static final int MAX_URL = 512;
  static final Set<String> SIZES = Set.of("300x250", "728x90", "320x50", "300x600");
  static final Set<String> MACROS = Set.of("AUCTION_PRICE", "CLICK_URL");

  /** Whitespace as browsers skip it inside tags and URLs: space, tab, LF, CR, FF, VT. */
  private static final String WS = "[ \\t\\n\\r\\f\\x0B]";

  private static final int FLAGS = Pattern.CASE_INSENSITIVE;

  // C0 controls other than tab, LF and CR. JSON escapes each as 6 bytes, which could push an event
  // past what Kafka accepts; no banner needs them.
  private static final Pattern CONTROL = Pattern.compile("[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F]");

  private static final Pattern FORBIDDEN_TAG =
      Pattern.compile(
          "<[ \\t\\n\\r\\f\\x0B/]*(script|iframe|object|embed|base|meta|form|link|style|svg|math"
              + "|frame|applet|template)",
          FLAGS);
  // "javascript" with optional whitespace between letters: browsers drop tabs and newlines in URLs.
  private static final Pattern JS_URL =
      Pattern.compile(
          String.join(WS + "*", "j", "a", "v", "a", "s", "c", "r", "i", "p", "t") + WS + "*:",
          FLAGS);
  // Numeric entities and the named ones that spell a scheme (&colon; &Tab; &NewLine; ...).
  private static final Pattern ENTITY =
      Pattern.compile("&(#|(colon|tab|newline|lpar|rpar|sol|bsol);)", FLAGS);
  private static final Pattern EVENT_HANDLER =
      Pattern.compile("[ \\t\\n\\r\\f\\x0B\"'/]on[a-z]+" + WS + "*=", FLAGS);
  // Any "${" that does not start an allowed macro, including an unclosed one.
  private static final Pattern BAD_MACRO =
      Pattern.compile("\\$\\{(?!(AUCTION_PRICE|CLICK_URL)\\})");

  private CreativeValidator() {}

  static List<Violation> validate(NewCreative in) {
    List<Violation> violations = new ArrayList<>();
    name(in.name(), violations);
    if (!SIZES.contains(in.width() + "x" + in.height())) {
      violations.add(new Violation("size", "must be one of " + SIZES));
    }
    markup(in.markup(), violations);
    landingUrl(in.landingUrl(), violations);
    return violations;
  }

  private static void name(String name, List<Violation> violations) {
    // Cheap length test first so an enormous value is never copied by strip().
    boolean ok =
        name != null
            && name.length() <= 4 * MAX_NAME
            && isStorableText(name)
            && !name.isBlank()
            && name.strip().codePointCount(0, name.strip().length()) <= MAX_NAME;
    if (!ok) {
      violations.add(new Violation("name", "must be 1 to " + MAX_NAME + " characters"));
    }
  }

  private static void markup(String markup, List<Violation> violations) {
    if (markup == null || markup.isBlank()) {
      violations.add(new Violation("markup", "is required"));
      return;
    }
    // Every char is at least one byte, so this length test is exact enough and allocates nothing.
    if (markup.length() > MAX_MARKUP_BYTES
        || markup.getBytes(StandardCharsets.UTF_8).length > MAX_MARKUP_BYTES) {
      violations.add(new Violation("markup", "must be at most " + MAX_MARKUP_BYTES + " bytes"));
      return; // never run the patterns on input that is already too large
    }
    if (!isStorableText(markup)) {
      violations.add(new Violation("markup", "must be valid text without NUL characters"));
      return;
    }
    if (CONTROL.matcher(markup).find()) {
      violations.add(
          new Violation(
              "markup", "must not contain control characters other than tab, newline and return"));
    }
    if (FORBIDDEN_TAG.matcher(markup).find()
        || JS_URL.matcher(markup).find()
        || ENTITY.matcher(markup).find()
        || EVENT_HANDLER.matcher(markup).find()) {
      violations.add(
          new Violation(
              "markup",
              "must not contain script-like tags (script, iframe, object, embed, base, meta, form,"
                  + " link, style, svg, math, frame, applet, template), javascript: URLs,"
                  + " character entities (&#...; &colon;) or on*= attributes"));
    }
    if (BAD_MACRO.matcher(markup).find()) {
      violations.add(new Violation("markup", "only the macros " + MACROS + " are allowed"));
    }
  }

  private static void landingUrl(String url, List<Violation> violations) {
    if (url == null || url.length() > MAX_URL || !isStorableText(url) || !isPlainHttps(url)) {
      violations.add(
          new Violation(
              "landingUrl",
              "must be an https URL of at most " + MAX_URL + " characters, without credentials"));
    }
  }

  private static boolean isPlainHttps(String url) {
    try {
      URI uri = new URI(url);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && !uri.getHost().isBlank()
          && uri.getRawUserInfo() == null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /** PostgreSQL text cannot hold NUL, and a lone surrogate is not valid Unicode. */
  private static boolean isStorableText(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == 0) {
        return false;
      }
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
          return false;
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        return false;
      }
    }
    return true;
  }
}
