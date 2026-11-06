package sage.party;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import sage.epg.sd.gson.JsonElement;
import sage.epg.sd.gson.JsonObject;
import sage.epg.sd.gson.JsonParser;

/** Strict, bounded Party Mode v1 envelopes; see {@code docs/PartyModeV1.md}. */
public final class PartyProtocol
{
  /** Existing MiniClient SetProperty extension name, in both directions. */
  public static final String PROPERTY = "PARTY_V1";
  /** Maximum UTF-8 envelope length, before parsing. */
  public static final int MAX_BYTES = 8192;
  /** All guarantees are required; the NG version or client ID is not authorization. */
  public static final Set<String> REQUIRED = Set.of(
      "PARTY_V1",
      "PARTY_JOIN_CODE_V1",
      "PARTY_MONOTONIC_V1",
      "PARTY_SCHEDULED_V1",
      "PARTY_HEARTBEAT_V1",
      "PARTY_PLAYER_ADAPTER_V1",
      "PARTY_LOCAL_STOP_V1");
  /** Optional guarantee required before admitting an in-progress recording. */
  public static final String GROWING_MEDIA = "PARTY_GROWING_MEDIA_V1";

  private PartyProtocol() {}

  /** Parses one bounded, shallow JSON object, rejecting non-v1 envelopes. */
  public static JsonObject parse(String json)
  {
    if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new Fault("INVALID_REQUEST");
    // Bound nesting before Gson recursion, including adversarial unnegotiated traffic.
    int depth = 0;
    boolean quoted = false, escaped = false;
    for (char c : json.toCharArray())
    {
      if (escaped) { escaped = false; continue; }
      if (quoted && c == '\\') { escaped = true; continue; }
      if (c == '"') quoted = !quoted;
      if (!quoted && (c == '{' || c == '[') && ++depth > 4)
        throw new Fault("INVALID_REQUEST");
      if (!quoted && (c == '}' || c == ']')) depth--;
    }
    try
    {
      JsonObject o = new JsonParser().parse(json).getAsJsonObject();
      if (number(o, "schemaVersion", 1, 1) != 1) throw new Fault("INVALID_REQUEST");
      text(o, "type");
      text(o, "requestId");
      return o;
    }
    catch (IllegalStateException | IllegalArgumentException | sage.epg.sd.gson.JsonParseException e)
    {
      throw new Fault("INVALID_REQUEST");
    }
  }

  /** Reads a bounded ASCII identifier or enum string. */
  public static String text(JsonObject o, String key)
  {
    JsonElement e = o.get(key);
    if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
      throw new Fault("INVALID_REQUEST");
    String s = e.getAsString();
    if (!s.matches("[A-Za-z0-9_.:-]{1,128}")) throw new Fault("INVALID_REQUEST");
    return s;
  }

  /** Reads an integral JSON number in an inclusive safe-integer range. */
  public static long number(JsonObject o, String key, long min, long max)
  {
    JsonElement e = o.get(key);
    if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
      throw new Fault("INVALID_REQUEST");
    try
    {
      long n = new java.math.BigDecimal(e.getAsString()).longValueExact();
      if (n < min || n > max) throw new Fault("INVALID_REQUEST");
      return n;
    }
    catch (ArithmeticException | NumberFormatException ex) { throw new Fault("INVALID_REQUEST"); }
  }

  /** Reads a required JSON boolean without string coercion. */
  public static boolean flag(JsonObject o, String key)
  {
    JsonElement e = o.get(key);
    if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())
      throw new Fault("INVALID_REQUEST");
    return e.getAsBoolean();
  }

  /** Constructs an outbound v1 envelope. */
  public static JsonObject envelope(String type)
  {
    JsonObject o = new JsonObject();
    o.addProperty("schemaVersion", 1);
    o.addProperty("type", type);
    return o;
  }

  /** Copies an envelope without relying on the vendored Gson's package-private deepCopy. */
  public static JsonObject copy(JsonObject value)
  {
    return new JsonParser().parse(value.toString()).getAsJsonObject();
  }

  /** A safe wire error code, never containing request contents or credentials. */
  public static final class Fault extends RuntimeException
  {
    /** Creates a protocol failure with a non-secret machine code. */
    public Fault(String code) { super(code); }
  }
}
