package sage.party;

import java.util.HashSet;
import java.util.Set;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/** Covers the complete short-code namespace and its fail-closed lifecycle. */
public class PartyJoinCodesTest
{
  @Test public void allThousandValuesIncludeLeadingZerosHaveNoCollisionsAndExhaustExplicitly()
  {
    PartyJoinCodes codes = new PartyJoinCodes();
    Set<String> all = new HashSet<>();
    for (int i = 0; i < 1000; i++)
    {
      PartyJoinCodes.Grant grant = codes.issue("p" + i, 0, PartyJoinCodes.LIFETIME_MS);
      assertTrue(all.add(grant.code()));
      assertTrue(grant.code().matches("[0-9]{3}"));
      assertEquals(codes.resolve(grant.code(), 0), "p" + i);
    }
    assertTrue(all.contains("000")); assertTrue(all.contains("999"));
    assertThrows(PartyProtocol.Fault.class,
        () -> codes.issue("exhausted", 0, PartyJoinCodes.LIFETIME_MS));
    codes.remove("p0", 0);
    assertThrows(PartyProtocol.Fault.class,
        () -> codes.issue("quarantined", 0, PartyJoinCodes.LIFETIME_MS));
    String previous = all.stream().filter(code -> {
      try { codes.resolve(code, 1); return false; }
      catch (PartyProtocol.Fault expected) { return true; }
    }).findFirst().orElseThrow();
    assertEquals(codes.issue("available", PartyJoinCodes.REUSE_QUARANTINE_MS,
        PartyJoinCodes.LIFETIME_MS).code(), previous);
    codes.clear();
    assertThrows(PartyProtocol.Fault.class,
        () -> codes.resolve(previous, PartyJoinCodes.REUSE_QUARANTINE_MS + 1));
  }

  @Test public void partyLifetimeRotationCooldownRetirementAndEpochResetNeverReviveOldGrant()
  {
    PartyJoinCodes codes = new PartyJoinCodes();
    long partyExpires = PartyJoinCodes.LIFETIME_MS;
    String old = codes.issue("p", 0, partyExpires).code();
    assertThrows(PartyProtocol.Fault.class, () -> codes.issue("p", 9999, partyExpires));
    assertEquals(codes.rotationRetryAfter("p", 9999), 1);
    String next = codes.issue("p", 10000, partyExpires).code();
    assertNotEquals(old, next);
    assertThrows(PartyProtocol.Fault.class, () -> codes.resolve(old, 10000));
    assertEquals(codes.resolve(next, partyExpires - 1), "p");
    assertThrows(PartyProtocol.Fault.class, () -> codes.resolve(next, partyExpires));
    assertThrows(PartyProtocol.Fault.class, () -> codes.current("p", partyExpires));
    assertThrows(PartyProtocol.Fault.class, () -> codes.resolve("1", partyExpires));
    assertThrows(PartyProtocol.Fault.class, () -> codes.resolve("１２３", partyExpires));
  }
}
