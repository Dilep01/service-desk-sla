package dev.servicedesk;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {
  BusinessCalendar calendar(Set<LocalDate> holidays) {
    return new BusinessCalendar(
        ZoneId.of("Europe/London"),
        LocalTime.of(9, 0),
        LocalTime.of(17, 0),
        EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        holidays);
  }

  @Test
  void weekendAcrossDst() {
    assertEquals(
        Instant.parse("2026-03-30T09:30:00Z"),
        calendar(Set.of()).add(Instant.parse("2026-03-27T16:30:00Z"), Duration.ofHours(2)));
  }

  @Test
  void holidaySkipped() {
    assertEquals(
        Instant.parse("2026-03-31T09:30:00Z"),
        calendar(Set.of(LocalDate.of(2026, 3, 30)))
            .add(Instant.parse("2026-03-27T16:30:00Z"), Duration.ofHours(2)));
  }

  @Test
  void exactClose() {
    assertEquals(
        Instant.parse("2026-03-27T17:00:00Z"),
        calendar(Set.of()).add(Instant.parse("2026-03-27T16:30:00Z"), Duration.ofMinutes(30)));
  }

  @Test
  void elapsedBusinessTimeExcludesWeekend() {
    assertEquals(
        Duration.ofHours(2),
        calendar(Set.of())
            .between(Instant.parse("2026-03-27T16:30:00Z"), Instant.parse("2026-03-30T09:30:00Z")));
  }

  @Test
  void nonworkingPauseConsumesNoBudget() {
    assertEquals(
        Duration.ZERO,
        calendar(Set.of())
            .between(Instant.parse("2026-03-27T18:00:00Z"), Instant.parse("2026-03-29T18:00:00Z")));
  }
}
