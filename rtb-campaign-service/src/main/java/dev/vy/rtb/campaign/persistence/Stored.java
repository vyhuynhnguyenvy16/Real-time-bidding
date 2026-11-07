package dev.vy.rtb.campaign.persistence;

import java.time.Instant;

/** A value together with the bookkeeping the database keeps about its row. */
public record Stored<T>(T value, long version, Instant createdAt, Instant updatedAt) {}
