package dev.servicedesk;

import java.time.*;
import java.util.*;

public final class Sla {
  private Sla() {}

  public static Duration response(String priority) {
    return switch (priority) {
      case "P1" -> Duration.ofMinutes(15);
      case "P2" -> Duration.ofHours(1);
      case "P3" -> Duration.ofHours(4);
      case "P4" -> Duration.ofHours(8);
      default -> throw new IllegalArgumentException("Invalid priority");
    };
  }

  public static Duration resolution(String priority) {
    return switch (priority) {
      case "P1" -> Duration.ofHours(4);
      case "P2" -> Duration.ofHours(8);
      case "P3" -> Duration.ofHours(24);
      case "P4" -> Duration.ofHours(72);
      default -> throw new IllegalArgumentException("Invalid priority");
    };
  }

  public static boolean transition(String from, String to) {
    return Map.of(
            "OPEN",
            Set.of("IN_PROGRESS", "CANCELLED"),
            "IN_PROGRESS",
            Set.of("WAITING_FOR_CUSTOMER", "RESOLVED", "CANCELLED"),
            "WAITING_FOR_CUSTOMER",
            Set.of("IN_PROGRESS", "CANCELLED"),
            "RESOLVED",
            Set.of("CLOSED", "IN_PROGRESS"))
        .getOrDefault(from, Set.of())
        .contains(to);
  }
}
