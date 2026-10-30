package sage.party;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Framing adapter for the existing type-1 MiniClient SetProperty control channel. */
public final class PartyWire
{
  private PartyWire() {}

  /**
   * Consumes Party Mode properties only; unrelated legacy replies are untouched.
   * @param payload existing property payload, without the MiniClient header
   * @param receiver bounded JSON consumer bound to the authenticated renderer
   * @return whether the property belongs to Party Mode, including malformed Party traffic
   */
  public static boolean accept(byte[] payload, Consumer<String> receiver)
  {
    if (payload == null || payload.length < 4) return false;
    int names = (payload[0] & 255) * 256 + (payload[1] & 255);
    int values = (payload[2] & 255) * 256 + (payload[3] & 255);
    if (names != PartyProtocol.PROPERTY.length() || payload.length < 4 + names) return false;
    String name = new String(payload, 4, names, StandardCharsets.US_ASCII);
    if (!PartyProtocol.PROPERTY.equals(name)) return false;
    if (values > PartyProtocol.MAX_BYTES || payload.length != 4 + names + values)
      receiver.accept("");
    else receiver.accept(new String(payload, 4 + names, values, StandardCharsets.UTF_8));
    return true;
  }
}
