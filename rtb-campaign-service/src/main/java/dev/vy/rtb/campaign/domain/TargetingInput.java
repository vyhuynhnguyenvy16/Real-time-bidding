package dev.vy.rtb.campaign.domain;

import java.util.List;
import java.util.Map;

/**
 * Targeting as the advertiser typed it. {@code scheduleHours} maps "all" or a weekday ("mon".."sun")
 * to the hours (0-23, Asia/Ho_Chi_Minh) the campaign may run; an empty map means "always".
 */
public record TargetingInput(
    List<String> countries,
    List<Integer> deviceTypes,
    List<String> categories,
    List<String> publisherBlocklist,
    Map<String, List<Integer>> scheduleHours) {}
