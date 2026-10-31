package sage.party;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.Function;

/** Reuses existing Placeshifter records; never treats encryption or NG identity as login. */
public final class PartyAuthentication
{
  private PartyAuthentication() {}

  /** Verifies the exact existing STV username/digest semantics against enabled user records. */
  public static boolean verify(Function<String, String> properties, String user, String digest)
  {
    if (!enabled(properties, user) || digest == null || digest.isEmpty()) return false;
    String expected = properties.apply("miniclient/" + user + "/pwd");
    return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        digest.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean enabled(Function<String, String> properties, String user)
  {
    if (user == null || user.isEmpty()) return false;
    String users = properties.apply("miniclient/users");
    if (users == null) return false;
    boolean enabled = false;
    for (String item : users.split(";"))
    {
      String[] parts = item.split(",", -1);
      if (parts.length == 2 && parts[0].equals(user) && "true".equalsIgnoreCase(parts[1]))
        enabled = true;
    }
    String stv = properties.apply("miniclient/" + user + "/stv");
    return enabled && stv != null && !stv.isEmpty() && properties.apply("miniclient/" + user + "/pwd") != null;
  }

  /** Finds exactly one enabled principal after the existing cached secret was verified. */
  public static String cachedPrincipal(Function<String, String> properties, String clientName)
  {
    String users = properties.apply("miniclient/users"), found = null;
    if (users == null) return null;
    for (String item : users.split(";"))
    {
      String user = item.split(",", -1)[0];
      String cached = properties.apply("miniclient/" + user + "/cached_auth");
      if (enabled(properties, user) && cached != null
          && Arrays.asList(cached.split(",", -1)).contains(clientName))
      {
        if (found != null) return null;
        found = user;
      }
    }
    return found;
  }
}
