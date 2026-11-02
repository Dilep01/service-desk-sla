package dev.servicedesk;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SlaTest {
  @Test
  void priorityTargets() {
    assertEquals(Duration.ofMinutes(15), Sla.response("P1"));
    assertEquals(Duration.ofHours(72), Sla.resolution("P4"));
    assertThrows(IllegalArgumentException.class, () -> Sla.response("P5"));
  }

  @Test
  void workflowRejectsSkippingWork() {
    assertFalse(Sla.transition("OPEN", "RESOLVED"));
    assertTrue(Sla.transition("IN_PROGRESS", "RESOLVED"));
    assertFalse(Sla.transition("CLOSED", "OPEN"));
  }
}
