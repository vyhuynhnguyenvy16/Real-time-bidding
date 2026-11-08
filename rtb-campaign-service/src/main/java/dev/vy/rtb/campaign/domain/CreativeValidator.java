package dev.vy.rtb.campaign.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creative rules (PR05 brief Q1, Q2). Markup that looks dangerous is rejected, never "fixed":
 * rewriting HTML is how sanitizers leak. This is the first layer; the exchange still sandboxes.
 */
final class CreativeValidator {

  static final int MAX_NAME = 120;
  static final int MAX_MARKUP_BYTES = 20 * 1024;
  static final int MAX_URL = 2048;
  static final Set<String> SIZES = Set.of("300x250", "728x90", "320x50", "300x600");
  static final Set<String> MACROS = Set.of("AUCTION_PRICE", "CLICK_URL");

  private static final Pattern FORBIDDEN_TAG =
      Pattern.compile("<\\s*/?\\s*(script|iframe|object|embed)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern JS_URL =
      Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE);
  private static final Pattern EVENT_HANDLER =
      Pattern.compile("[\\s\"'/]on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
  private static final Pattern MACRO = Pattern.compile("\\$\\{([^}]*)}");

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
    if (name == null || name.isBlank() || name.length() > MAX_NAME) {
      violations.add(new Violation("name", "must be 1 to " + MAX_NAME + " characters"));
    }
  }

  private static void markup(String markup, List<Violation> violations) {
    if (markup == null || markup.isBlank()) {
      violations.add(new Violation("markup", "is required"));
      return;
    }
    if (markup.getBytes(StandardCharsets.UTF_8).length > MAX_MARKUP_BYTES) {
      violations.add(new Violation("markup", "must be at most " + MAX_MARKUP_BYTES + " bytes"));
    }
    if (FORBIDDEN_TAG.matcher(markup).find()
        || JS_URL.matcher(markup).find()
        || EVENT_HANDLER.matcher(markup).find()) {
      violations.add(
          new Violation(
              "markup", "must not contain script, iframe, object, embed, javascript: or on*="));
    }
    Matcher macros = MACRO.matcher(markup);
    while (macros.find()) {
      if (!MACROS.contains(macros.group(1))) {
        violations.add(
            new Violation("markup", "unknown macro ${" + macros.group(1) + "}; allowed " + MACROS));
        break;
      }
    }
  }

  private static void landingUrl(String url, List<Violation> violations) {
    if (url == null || url.length() > MAX_URL || !isHttps(url)) {
      violations.add(
          new Violation(
              "landingUrl", "must be an https URL of at most " + MAX_URL + " characters"));
    }
  }

  private static boolean isHttps(String url) {
    try {
      URI uri = new URI(url);
      return "https".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank();
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
