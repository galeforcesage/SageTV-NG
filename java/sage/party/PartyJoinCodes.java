package sage.party;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import static sage.party.PartyProtocol.*;

/** Server-local short confirmation codes; never independent authentication credentials. */
final class PartyJoinCodes
{
  static final long LIFETIME_MS = 120000, ROTATE_COOLDOWN_MS = 10000;
  record Grant(String code, long expiresAt) {}
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Grant> active = new HashMap<>();
  private final Map<String, Long> retired = new HashMap<>();

  Grant issue(String party, long now)
  {
    expire(now);
    Grant old = active.get(party);
    if (old != null && now < old.expiresAt - LIFETIME_MS + ROTATE_COOLDOWN_MS)
      throw new Fault("CODE_ROTATION_COOLDOWN");
    ArrayList<String> free = new ArrayList<>();
    java.util.Set<String> used = new java.util.HashSet<>(retired.keySet());
    for (Grant grant : active.values()) used.add(grant.code);
    for (int i = 0; i < 1000; i++)
    {
      String candidate = String.format(java.util.Locale.ROOT, "%03d", i);
      if (!used.contains(candidate)) free.add(candidate);
    }
    if (free.isEmpty()) throw new Fault("JOIN_CODE_UNAVAILABLE");
    Grant grant = new Grant(free.get(random.nextInt(free.size())), now + LIFETIME_MS);
    remove(party, now);
    active.put(party, grant);
    return grant;
  }

  Grant current(String party, long now)
  {
    expire(now);
    Grant grant = active.get(party);
    if (grant == null) throw new Fault("JOIN_CODE_EXPIRED");
    return grant;
  }

  String resolve(String code, long now)
  {
    expire(now);
    if (!code.matches("[0-9]{3}")) throw new Fault("JOIN_DENIED");
    for (Map.Entry<String, Grant> entry : active.entrySet())
      if (entry.getValue().code.equals(code)) return entry.getKey();
    throw new Fault("JOIN_DENIED");
  }

  void remove(String party, long now)
  {
    Grant grant = active.remove(party);
    if (grant != null) retired.put(grant.code, now + LIFETIME_MS);
  }

  private void expire(long now)
  {
    retired.entrySet().removeIf(entry -> entry.getValue() <= now);
    active.entrySet().removeIf(entry -> {
      if (entry.getValue().expiresAt > now) return false;
      long until = entry.getValue().expiresAt + LIFETIME_MS;
      if (until > now) retired.put(entry.getValue().code, until);
      return true;
    });
  }

  long rotationRetryAfter(String party, long now)
  {
    Grant grant = active.get(party);
    return grant == null ? 0 : Math.max(0, grant.expiresAt - LIFETIME_MS + ROTATE_COOLDOWN_MS - now);
  }

  void clear() { active.clear(); retired.clear(); }
}
