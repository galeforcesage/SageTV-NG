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
      PartyJoinCodes.Grant grant = codes.issue("p" + i, 0);
      assertTrue(all.add(grant.code()));
      assertTrue(grant.code().matches("[0-9]{3}"));
      assertEquals(codes.resolve(grant.code(), 0), "p" + i);
    }
    assertTrue(all.contains("000")); assertTrue(all.contains("999"));
    assertThrows(PartyProtocol.Fault.class, () -> codes.issue("exhausted", 0));
    codes.remove("p0", 0);
    assertThrows(PartyProtocol.Fault.class, () -> codes.issue("quarantined", 0));
    String previous = all.stream().filter(code -> {
      try { codes.resolve(code, 1); return false; }
      catch (PartyProtocol.Fault expected) { return true; }
    }).findFirst().orElseThrow();
    assertEquals(codes.issue("available", 120000).code(), previous);
    codes.clear();
    assertThrows(PartyProtocol.Fault.class, () -> codes.resolve(previous, 120001));
  }

  @Test public void expiryRotationCooldownRetirementAndEpochResetNeverReviveOldGrant()
  {
    PartyJoinCodes codes = new PartyJoinCodes();
    String old = codes.issue("p", 0).code();
    assertThrows(PartyProtocol.Fault.class, () -> codes.issue("p", 9999));
    assertEquals(codes.rotationRetryAfter("p", 9999), 1);
    String next = codes.issue("p", 10000).code();
    assertNotEquals(old, next);
    assertThrows(PartyProtocol.Fault.class, () -> codes.resolve(old, 10000));
    assertEquals(codes.resolve(next, 129999), "p");
    assertThrows(PartyProtocol.Fault.class, () -> codes.resolve(next, 130000));
    assertThrows(PartyProtocol.Fault.class, () -> codes.current("p", 130000));
    assertThrows(PartyProtocol.Fault.class, () -> codes.resolve("1", 130000));
    assertThrows(PartyProtocol.Fault.class, () -> codes.resolve("１２３", 130000));
  }
}
