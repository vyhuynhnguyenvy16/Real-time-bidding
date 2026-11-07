package dev.vy.rtb.campaign.domain;

/** One validation failure: which field, and a human readable reason. */
public record Violation(String field, String message) {}
