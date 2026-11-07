package dev.vy.rtb.campaign.persistence;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL array literals ("{a,b}") and the reverse, so no driver-specific array type is needed.
 */
final class PgArrays {

  private PgArrays() {}

  static String textArray(List<String> values) {
    List<String> quoted = new ArrayList<>(values.size());
    for (String value : values) {
      quoted.add("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }
    return "{" + String.join(",", quoted) + "}";
  }

  static String intArray(List<Integer> values) {
    return "{" + String.join(",", values.stream().map(String::valueOf).toList()) + "}";
  }

  static List<String> toStrings(Array array) throws SQLException {
    Object[] elements = (Object[]) array.getArray();
    List<String> out = new ArrayList<>(elements.length);
    for (Object element : elements) {
      out.add((String) element);
    }
    return List.copyOf(out);
  }

  static List<Integer> toInts(Array array) throws SQLException {
    Object[] elements = (Object[]) array.getArray();
    List<Integer> out = new ArrayList<>(elements.length);
    for (Object element : elements) {
      out.add(((Number) element).intValue());
    }
    return List.copyOf(out);
  }
}
