package sage.party;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import sage.epg.sd.gson.JsonObject;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import static sage.party.PartyProtocol.*;

public class PartyProtocolTest
{
  @DataProvider
  public Object[][] directiveEventTypes()
  {
    return new Object[][] { { "HOLD_CONFIRM" }, { "REVEAL" }, { "CANCEL" } };
  }

  @Test(dataProvider = "directiveEventTypes")
  public void directiveEventsContainCompleteTuple(String type)
  {
    JsonObject event =
        PartyService.directiveEvent(type, "party-1", "directive-1", 2, 3);

    assertEquals(event.get("type").getAsString(), type);
    assertEquals(event.get("partyId").getAsString(), "party-1");
    assertEquals(event.get("stateVersion").getAsLong(), 2L);
    assertEquals(event.get("commandSeq").getAsLong(), 3L);
    assertEquals(event.get("directiveId").getAsString(), "directive-1");
  }

  @Test
  public void invalidNumberReportsFieldWithoutValue()
  {
    JsonObject request = envelope("CREATE");
    request.addProperty("requestId", "create-1");
    request.addProperty("schemaVersion", 1);
    request.addProperty("requestSeq", 1.5);

    Fault failure;
    try
    {
      number(request, "requestSeq", 1, 100);
      fail("expected invalid request");
      return;
    }
    catch (Fault expected)
    {
      failure = expected;
    }

    assertEquals(failure.getMessage(), "INVALID_REQUEST");
    assertEquals(failure.stage(), "field");
    assertEquals(failure.field(), "requestSeq");
    assertEquals(failure.reason(), "non_integral");
  }

  @Test
  public void rejectionDiagnosticExcludesCredentialsAndValues()
  {
    JsonObject request = envelope("CREATE");
    request.addProperty("requestId", "create-2");
    request.addProperty("requestSeq", 2);
    request.addProperty("schemaVersion", 1);
    request.addProperty("serverEpoch", "epoch-secret");
    request.addProperty("sessionToken", "token-secret");
    request.addProperty("startMode", "invalid-secret");

    String diagnostic = PartyService.rejectionDiagnostic(
        request, invalid("create", "startMode", "invalid_enum"));

    assertTrue(diagnostic.contains("type=CREATE"));
    assertTrue(diagnostic.contains("requestId=create-2"));
    assertTrue(diagnostic.contains("requestSeq=2"));
    assertTrue(diagnostic.contains("stage=create field=startMode reason=invalid_enum"));
    assertFalse(diagnostic.contains("epoch-secret"));
    assertFalse(diagnostic.contains("token-secret"));
    assertFalse(diagnostic.contains("invalid-secret"));
  }
}
