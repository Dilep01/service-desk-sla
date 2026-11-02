package dev.servicedesk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AccessTest {
  @Test
  void unknownRolesDeny() {
    assertEquals("FALSE", Access.visibility("SUPERUSER"));
  }

  @Test
  void staffAreScopedToTeams() {
    assertTrue(Access.visibility("AGENT").contains("tm.tenant_id=ticket.tenant_id"));
    assertEquals(Access.visibility("AGENT"), Access.visibility("LEAD"));
  }
}
