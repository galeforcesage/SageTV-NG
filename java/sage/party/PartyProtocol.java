package sage.party;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import sage.epg.sd.gson.JsonElement;
import sage.epg.sd.gson.JsonObject;
import sage.epg.sd.gson.JsonParser;

/** Strict, bounded Party Mode v1 envelopes; see {@code docs/PartyModeV1.md}. */
public final class PartyProtocol
{
  /** Final pre-release contract revision implemented by this server. */
  public static final int CONTRACT_REVISION = 10;
  /** Capability token for the exact implemented contract revision. */
  public static final String CONTRACT_CAPABILITY = "PARTY_CONTRACT_REV_010";
  /** Existing MiniClient SetProperty extension name, in both directions. */
  public static final String PROPERTY = "PARTY_V1";
  /** Maximum UTF-8 envelope length, before parsing. */
  public static final int MAX_BYTES = 8192;
  /** All guarantees are required; the NG version or client ID is not authorization. */
  public static final Set<String> REQUIRED = Set.of(
      "PARTY_V1",
      CONTRACT_CAPABILITY,
      "PARTY_JOIN_CODE_V1",
      "PARTY_INVITATION_V1",
      "PARTY_MONOTONIC_V1",
      "PARTY_SCHEDULED_V1",
      "PARTY_HEARTBEAT_V1",
      "PARTY_PLAYER_ADAPTER_V1",
      "PARTY_OPEN_BINDING_V1",
      "PARTY_PRESENTED_FRAME_V1",
      "PARTY_ARMED_REVEAL_V1",
      "PARTY_LOCAL_STOP_V1");
  /** Optional guarantee required before admitting an in-progress recording. */
  public static final String GROWING_MEDIA = "PARTY_GROWING_MEDIA_V1";
  /** Optional direct-push stream epoch and recording-origin handshake. */
  public static final String PUSH_EPOCH_ORIGIN = "PARTY_PUSH_EPOCH_ORIGIN_V1";

  private PartyProtocol() {}

  /** Parses one bounded, shallow JSON object, rejecting non-v1 envelopes. */
  public static JsonObject parse(String json)
  {
    if (json == null)
      throw invalid("envelope", "payload", "missing");
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw invalid("envelope", "payload", "too_large");
    // Bound nesting before Gson recursion, including adversarial unnegotiated traffic.
    int depth = 0;
    boolean quoted = false, escaped = false;
    for (char c : json.toCharArray())
    {
      if (escaped) { escaped = false; continue; }
      if (quoted && c == '\\') { escaped = true; continue; }
      if (c == '"') quoted = !quoted;
      if (!quoted && (c == '{' || c == '[') && ++depth > 4)
        throw invalid("envelope", "structure", "nesting_too_deep");
      if (!quoted && (c == '}' || c == ']')) depth--;
    }
    try
    {
      JsonObject o = new JsonParser().parse(json).getAsJsonObject();
      number(o, "schemaVersion", 1, 1);
      text(o, "type");
      text(o, "requestId");
      return o;
    }
    catch (IllegalStateException | IllegalArgumentException | sage.epg.sd.gson.JsonParseException e)
    {
      throw invalid("envelope", "payload", "malformed_json");
    }
  }

  /** Reads a bounded ASCII identifier or enum string. */
  public static String text(JsonObject o, String key)
  {
    JsonElement e = o.get(key);
    if (e == null) throw invalid("field", key, "missing");
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
      throw invalid("field", key, "wrong_type");
    String s = e.getAsString();
    if (!s.matches("[A-Za-z0-9_.:-]{1,128}"))
      throw invalid("field", key, "invalid_format");
    return s;
  }

  /** Reads an integral JSON number in an inclusive safe-integer range. */
  public static long number(JsonObject o, String key, long min, long max)
  {
    JsonElement e = o.get(key);
    if (e == null) throw invalid("field", key, "missing");
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
      throw invalid("field", key, "wrong_type");
    try
    {
      long n = new java.math.BigDecimal(e.getAsString()).longValueExact();
      if (n < min || n > max) throw invalid("field", key, "out_of_range");
      return n;
    }
    catch (ArithmeticException | NumberFormatException ex)
    {
      throw invalid("field", key, "non_integral");
    }
  }

  /** Reads a required JSON boolean without string coercion. */
  public static boolean flag(JsonObject o, String key)
  {
    JsonElement e = o.get(key);
    if (e == null) throw invalid("field", key, "missing");
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())
      throw invalid("field", key, "wrong_type");
    return e.getAsBoolean();
  }

  /** Creates a wire-compatible invalid request with safe server-only diagnostics. */
  public static Fault invalid(String stage, String field, String reason)
  {
    return new Fault("INVALID_REQUEST", stage, field, reason);
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
    private final String stage;
    private final String field;
    private final String reason;

    /** Creates a protocol failure with a non-secret machine code. */
    public Fault(String code) { this(code, "-", "-", "-"); }

    private Fault(String code, String stage, String field, String reason)
    {
      super(code);
      this.stage = stage;
      this.field = field;
      this.reason = reason;
    }

    /** Safe server-only validation stage. */
    public String stage() { return stage; }
    /** Safe server-only field name. */
    public String field() { return field; }
    /** Safe server-only failure category. */
    public String reason() { return reason; }
  }
}
