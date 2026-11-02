package dev.servicedesk;

import java.time.*;
import java.util.Set;

/** Immutable business-time schedule using actual elapsed instants across DST. */
public record BusinessCalendar(
    ZoneId zone, LocalTime opens, LocalTime closes, Set<DayOfWeek> days, Set<LocalDate> holidays) {
  public BusinessCalendar {
    if (!opens.isBefore(closes) || days.isEmpty())
      throw new IllegalArgumentException("A non-empty daytime schedule is required");
    days = Set.copyOf(days);
    holidays = Set.copyOf(holidays);
  }

  public Instant add(Instant start, Duration amount) {
    if (amount.isNegative()) throw new IllegalArgumentException("Negative duration");
    ZonedDateTime cursor = start.atZone(zone);
    Duration remaining = amount;
    for (int guard = 0; guard < 36600; guard++) {
      if (remaining.isZero()) return cursor.toInstant();
      LocalDate date = cursor.toLocalDate();
      if (!days.contains(date.getDayOfWeek()) || holidays.contains(date)) {
        cursor = date.plusDays(1).atTime(opens).atZone(zone);
        continue;
      }
      ZonedDateTime begin = date.atTime(opens).atZone(zone), end = date.atTime(closes).atZone(zone);
      if (cursor.isBefore(begin)) cursor = begin;
      if (!cursor.isBefore(end)) {
        cursor = date.plusDays(1).atTime(opens).atZone(zone);
        continue;
      }
      Duration available = Duration.between(cursor, end);
      if (remaining.compareTo(available) <= 0) return cursor.plus(remaining).toInstant();
      remaining = remaining.minus(available);
      cursor = date.plusDays(1).atTime(opens).atZone(zone);
    }
    throw new IllegalArgumentException("Duration exceeds supported calendar horizon");
  }

  public Duration between(Instant start, Instant end) {
    if (!end.isAfter(start)) return Duration.ZERO;
    LocalDate date = start.atZone(zone).toLocalDate(), last = end.atZone(zone).toLocalDate();
    Duration total = Duration.ZERO;
    for (int guard = 0; !date.isAfter(last); guard++, date = date.plusDays(1)) {
      if (guard >= 36600)
        throw new IllegalArgumentException("Duration exceeds supported calendar horizon");
      if (!days.contains(date.getDayOfWeek()) || holidays.contains(date)) continue;
      Instant a = date.atTime(opens).atZone(zone).toInstant(),
          b = date.atTime(closes).atZone(zone).toInstant();
      if (a.isBefore(start)) a = start;
      if (b.isAfter(end)) b = end;
      if (b.isAfter(a)) total = total.plus(Duration.between(a, b));
    }
    return total;
  }
}
