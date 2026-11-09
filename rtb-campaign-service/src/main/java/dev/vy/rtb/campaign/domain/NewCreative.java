package dev.vy.rtb.campaign.domain;

/** Unvalidated creative input; {@link Creative#create} applies the rules. */
public record NewCreative(String name, int width, int height, String markup, String landingUrl) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String name;
    private int width;
    private int height;
    private String markup;
    private String landingUrl;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder width(int width) {
      this.width = width;
      return this;
    }

    public Builder height(int height) {
      this.height = height;
      return this;
    }

    public Builder markup(String markup) {
      this.markup = markup;
      return this;
    }

    public Builder landingUrl(String landingUrl) {
      this.landingUrl = landingUrl;
      return this;
    }

    public NewCreative build() {
      return new NewCreative(name, width, height, markup, landingUrl);
    }
  }
}
