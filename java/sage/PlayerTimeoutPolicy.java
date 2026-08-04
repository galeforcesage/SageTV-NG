/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

/**
 * Centralized, profile-aware resolution of the player reconnect / reply timeout
 * knobs used by {@link MiniPlayer} and {@link MiniClientSageRenderer} when
 * acquiring and using the shared player-socket-channel.
 *
 * <h3>Why this exists</h3>
 * A slow or reconnecting bridge can make playback acquisition stack up two
 * independent budgets: the bounded player-socket-channel acquisition
 * ({@code acquirePlayerSocketChannel} &times; the inner
 * {@code getSocketChannelInfo} wait) and, separately, the openURL reply-timeout
 * ({@code ui/remote_player_connection_timeout}). This class externalizes every
 * one of those constants and makes them tunable <em>per client profile</em>,
 * so NG-capable sessions can opt into tighter, faster-failing budgets without
 * penalizing legacy native clients.
 *
 * <h3>Hard compatibility contract</h3>
 * The <b>base</b> defaults are byte-for-byte the historical values, and the
 * per-profile tier is <b>only ever consulted for NG-capable sessions</b>. A
 * legacy / non-NG session (or any call made with no context, e.g. an
 * {@code EXTERNAL} player with a {@code null} renderer) resolves exactly as it
 * did before this class existed: the base property if explicitly set, otherwise
 * the historical constant. Absence of any profile configuration therefore
 * reproduces today's behavior exactly.
 *
 * <h3>Resolution cascade</h3>
 * For an NG-capable session the value is the first of these that is explicitly
 * present, else the NG default:
 * <ol>
 *   <li>{@code miniplayer/profile/<profileId>/<suffix>}</li>
 *   <li>{@code miniplayer/profile/ng_default/<suffix>}</li>
 *   <li>the base property (e.g. {@code miniplayer/player_socket_reconnect_attempts})</li>
 *   <li>the built-in NG default (the tightened knob)</li>
 * </ol>
 * For a legacy session only the base property (then the legacy default) is
 * consulted; the profile tier is never read.
 */
public final class PlayerTimeoutPolicy
{
  private PlayerTimeoutPolicy() {}

  /** Node prefix under which per-profile timeout overrides live. */
  public static final String PROFILE_PREFIX = "miniplayer/profile/";
  /** The synthetic profile id whose tier applies to every NG session. */
  public static final String NG_DEFAULT_ID = "ng_default";

  // --- Base property keys (legacy knobs; unchanged defaults) -------------
  public static final String BASE_INITIAL_WAIT = "miniplayer/player_socket_reconnect_initial_wait_ms";
  public static final String BASE_EXPIRE_WAIT   = "miniplayer/player_socket_reconnect_expire_wait_ms";
  public static final String BASE_ATTEMPTS      = "miniplayer/player_socket_reconnect_attempts";
  public static final String BASE_BACKOFF       = "miniplayer/player_socket_reconnect_backoff_ms";
  public static final String BASE_CONN_TIMEOUT  = "ui/remote_player_connection_timeout";
  /** Unified soonest-wins playback-attempt budget (NG opt-in; inactive for legacy). */
  public static final String BASE_PLAYBACK_DEADLINE = "miniplayer/player_socket_playback_deadline_ms";

  // --- Per-profile suffixes (last path segment of each base key) ---------
  public static final String SUF_INITIAL_WAIT = "player_socket_reconnect_initial_wait_ms";
  public static final String SUF_EXPIRE_WAIT   = "player_socket_reconnect_expire_wait_ms";
  public static final String SUF_ATTEMPTS      = "player_socket_reconnect_attempts";
  public static final String SUF_BACKOFF       = "player_socket_reconnect_backoff_ms";
  public static final String SUF_CONN_TIMEOUT  = "remote_player_connection_timeout";
  public static final String SUF_PLAYBACK_DEADLINE = "player_socket_playback_deadline_ms";

  // --- Historical (legacy) defaults: preserve current behavior exactly ---
  public static final long LEGACY_INITIAL_WAIT_MS = 5000L;
  public static final long LEGACY_EXPIRE_WAIT_MS  = 30000L;
  public static final int  LEGACY_ATTEMPTS        = 2;
  public static final long LEGACY_BACKOFF_MS      = 250L;
  public static final long LEGACY_CONN_TIMEOUT_MS = 30000L;

  // --- NG opt-in defaults: the RECOMMENDED values to write into the
  //     ng_default property tier (miniplayer/profile/ng_default/<suffix>).
  //     These are NOT a hardcoded resolution fallback -- an un-tuned NG session
  //     resolves through the base property exactly like legacy. They become
  //     effective only once seeded into the tier (see seedRecommendedNgDefaults)
  //     or otherwise configured, which keeps NG tightening strictly opt-in.
  public static final long NG_INITIAL_WAIT_MS = 3000L;
  public static final long NG_EXPIRE_WAIT_MS  = 15000L;
  public static final int  NG_ATTEMPTS        = 3;
  public static final long NG_BACKOFF_MS      = 250L;
  public static final long NG_CONN_TIMEOUT_MS = 15000L;
  /**
   * NG unified soonest-wins budget for a single playback attempt. Defaults to
   * the NG expire wait so that, once wired, a stalled attempt fails at the
   * earliest single deadline rather than serially exhausting acquisition and
   * then a fresh reply wait.
   */
  public static final long NG_PLAYBACK_DEADLINE_MS = 15000L;

  /**
   * The minimal facts about a session needed to resolve a profiled timeout:
   * whether it is NG-capable and, if so, its resolved profile id (may be null).
   */
  public interface ProfileContext
  {
    boolean isNg();
    /** @return the resolved profile id, or {@code null} if none. */
    String profileId();
  }

  /** Shared legacy context: never NG, never reads the profile tier. */
  public static final ProfileContext LEGACY = new ProfileContext()
  {
    public boolean isNg() { return false; }
    public String profileId() { return null; }
  };

  /** Build an explicit context (mainly for tests). */
  public static ProfileContext of(final boolean ng, final String profileId)
  {
    if (!ng)
      return LEGACY;
    final String pid = profileId;
    return new ProfileContext()
    {
      public boolean isNg() { return true; }
      public String profileId() { return pid; }
    };
  }

  /**
   * Derive the context from a renderer. A {@code null} renderer or a
   * non-NG-capable session yields {@link #LEGACY} (base/legacy resolution only).
   */
  public static ProfileContext forRenderer(MiniClientSageRenderer mcsr)
  {
    if (mcsr == null || !mcsr.isNgCapableSession())
      return LEGACY;
    sage.client.ClientProfile p = mcsr.getResolvedProfile();
    return of(true, (p == null) ? null : p.getProfileId());
  }

  private static boolean present(String key)
  {
    return key != null && Sage.get(key, null) != null;
  }

  /**
   * Resolve a long-valued knob through the profile cascade defined by the work
   * order: {@code miniplayer/profile/<profileId>/<suffix>} &rarr;
   * {@code miniplayer/profile/ng_default/<suffix>} &rarr; the base property
   * ({@code baseKey}) &rarr; the historical {@code legacyDefault} constant.
   *
   * <p>Legacy sessions (or a {@code null} context) consult <b>only</b> the base
   * property, falling back to {@code legacyDefault} -- byte-for-byte the
   * pre-existing behavior. NG sessions additionally consult the two profile
   * tiers first, but when neither is present they fall through to the exact
   * same {@code Sage.getLong(baseKey, legacyDefault)} the legacy path uses, so
   * an un-tuned NG session behaves identically to legacy. The NG tightened
   * values are therefore delivered purely by populating the {@code ng_default}
   * (or per-profile) property tier -- they are opt-in, never a hardcoded
   * override of the base default.
   */
  public static long resolveLong(ProfileContext ctx, String suffix, String baseKey,
      long legacyDefault)
  {
    if (ctx != null && ctx.isNg())
    {
      String pid = ctx.profileId();
      if (pid != null && pid.length() > 0)
      {
        String k = PROFILE_PREFIX + pid + "/" + suffix;
        if (present(k)) return Sage.getLong(k, legacyDefault);
      }
      String kd = PROFILE_PREFIX + NG_DEFAULT_ID + "/" + suffix;
      if (present(kd)) return Sage.getLong(kd, legacyDefault);
    }
    return Sage.getLong(baseKey, legacyDefault);
  }

  /** Integer form of {@link #resolveLong}. */
  public static int resolveInt(ProfileContext ctx, String suffix, String baseKey,
      int legacyDefault)
  {
    if (ctx != null && ctx.isNg())
    {
      String pid = ctx.profileId();
      if (pid != null && pid.length() > 0)
      {
        String k = PROFILE_PREFIX + pid + "/" + suffix;
        if (present(k)) return Sage.getInt(k, legacyDefault);
      }
      String kd = PROFILE_PREFIX + NG_DEFAULT_ID + "/" + suffix;
      if (present(kd)) return Sage.getInt(kd, legacyDefault);
    }
    return Sage.getInt(baseKey, legacyDefault);
  }

  // --- Typed accessors for the five knobs --------------------------------

  /** Initial (reconnect-capable) player-socket acquisition wait, ms. */
  public static long initialWaitMs(ProfileContext ctx)
  {
    return Math.max(0L, resolveLong(ctx, SUF_INITIAL_WAIT, BASE_INITIAL_WAIT,
        LEGACY_INITIAL_WAIT_MS));
  }

  /** Full player-socket acquisition expire wait, ms. */
  public static long expireWaitMs(ProfileContext ctx)
  {
    return Math.max(0L, resolveLong(ctx, SUF_EXPIRE_WAIT, BASE_EXPIRE_WAIT,
        LEGACY_EXPIRE_WAIT_MS));
  }

  /** Bounded acquisition attempts (always &gt;= 1). */
  public static int attempts(ProfileContext ctx)
  {
    return Math.max(1, resolveInt(ctx, SUF_ATTEMPTS, BASE_ATTEMPTS,
        LEGACY_ATTEMPTS));
  }

  /** Delay between acquisition attempts, ms (always &gt;= 0). */
  public static long backoffMs(ProfileContext ctx)
  {
    return Math.max(0L, resolveLong(ctx, SUF_BACKOFF, BASE_BACKOFF,
        LEGACY_BACKOFF_MS));
  }

  /** openURL / reply connection timeout, ms. */
  public static long connectionTimeoutMs(ProfileContext ctx)
  {
    return Math.max(0L, resolveLong(ctx, SUF_CONN_TIMEOUT, BASE_CONN_TIMEOUT,
        LEGACY_CONN_TIMEOUT_MS));
  }

  /**
   * The unified soonest-wins budget for a single playback attempt. Returns a
   * value &gt; 0 (active) only for NG sessions; legacy sessions return 0,
   * signalling "no unified cap -- keep the independent, stacking behavior".
   */
  public static long playbackDeadlineMs(ProfileContext ctx)
  {
    if (ctx == null || !ctx.isNg())
      return 0L;
    return Math.max(0L, resolveLong(ctx, SUF_PLAYBACK_DEADLINE, BASE_PLAYBACK_DEADLINE,
        0L));
  }

  /**
   * Compute the worst-case player-socket acquisition wall time (ms) for a
   * reconnect-capable client, given the resolved knobs. This mirrors the real
   * {@code getSocketChannelInfo}/{@code acquirePlayerSocketChannel} loops:
   * <ul>
   *   <li>Inside one {@code getSocketChannelInfo} call the wait clock is a
   *       single {@code startWait}; the reconnect pass runs until
   *       {@code initialWait} then the expire pass continues until
   *       {@code expireWait}. So one acquisition ~= {@code max(initial,expire)}
   *       = {@code expireWait} (NOT their sum).</li>
   *   <li>{@code acquirePlayerSocketChannel} calls that up to {@code attempts}
   *       times, separated by {@code backoff}.</li>
   * </ul>
   * i.e. {@code attempts*expireWait + (attempts-1)*backoff}.
   */
  public static long worstCaseAcquisitionMs(ProfileContext ctx)
  {
    int a = attempts(ctx);
    long expire = expireWaitMs(ctx);
    long backoff = backoffMs(ctx);
    return (long) a * expire + (long) (a - 1) * backoff;
  }

  /**
   * Seed the recommended NG opt-in values into the {@code ng_default} property
   * tier. This is the delivery mechanism for NG tightening: it writes each
   * {@code miniplayer/profile/ng_default/<suffix>} knob only when that key is
   * <b>absent</b>, so it never clobbers an explicit operator override and is
   * fully idempotent. Base properties and legacy resolution are untouched, so
   * legacy sessions remain byte-for-byte unchanged. Intended to be invoked once
   * when NG support is enabled (e.g. at first NG session bring-up / migration).
   *
   * @return the number of keys newly written (0 when everything was already set)
   */
  public static int seedRecommendedNgDefaults()
  {
    int written = 0;
    written += seedIfAbsent(SUF_INITIAL_WAIT, Long.toString(NG_INITIAL_WAIT_MS));
    written += seedIfAbsent(SUF_EXPIRE_WAIT,  Long.toString(NG_EXPIRE_WAIT_MS));
    written += seedIfAbsent(SUF_ATTEMPTS,     Integer.toString(NG_ATTEMPTS));
    written += seedIfAbsent(SUF_BACKOFF,      Long.toString(NG_BACKOFF_MS));
    written += seedIfAbsent(SUF_CONN_TIMEOUT, Long.toString(NG_CONN_TIMEOUT_MS));
    return written;
  }

  private static int seedIfAbsent(String suffix, String value)
  {
    String key = PROFILE_PREFIX + NG_DEFAULT_ID + "/" + suffix;
    if (present(key)) return 0;
    Sage.put(key, value);
    return 1;
  }

  /**
   * A single absolute deadline for a whole playback attempt, used to make the
   * acquisition budget and the reply-timeout "soonest-wins" (min) instead of
   * additive (sum). Inactive instances impose no cap and preserve the exact
   * legacy stacking behavior.
   *
   * <p>Pure value type: all methods take an explicit {@code nowMs} so the logic
   * is deterministic and unit-testable without a clock.
   */
  public static final class PlaybackDeadline
  {
    private final boolean active;
    private final long deadlineAtMs;

    private PlaybackDeadline(boolean active, long deadlineAtMs)
    {
      this.active = active;
      this.deadlineAtMs = deadlineAtMs;
    }

    /** An inactive deadline: never caps anything (legacy behavior). */
    public static PlaybackDeadline none()
    {
      return new PlaybackDeadline(false, 0L);
    }

    /**
     * Start a deadline {@code budgetMs} from {@code nowMs}. A non-positive
     * budget yields {@link #none()} (i.e. legacy / no cap).
     */
    public static PlaybackDeadline startingAt(long nowMs, long budgetMs)
    {
      if (budgetMs <= 0L)
        return none();
      return new PlaybackDeadline(true, nowMs + budgetMs);
    }

    /** Convenience: build from a context's unified budget. */
    public static PlaybackDeadline forContext(ProfileContext ctx, long nowMs)
    {
      return startingAt(nowMs, playbackDeadlineMs(ctx));
    }

    public boolean isActive() { return active; }

    public long deadlineAtMs() { return deadlineAtMs; }

    /** Remaining budget in ms; {@link Long#MAX_VALUE} when inactive. */
    public long remaining(long nowMs)
    {
      return active ? (deadlineAtMs - nowMs) : Long.MAX_VALUE;
    }

    public boolean expired(long nowMs)
    {
      return active && nowMs >= deadlineAtMs;
    }

    /**
     * Soonest-wins: cap an individual timeout by the remaining unified budget.
     * When inactive, returns {@code ownTimeoutMs} unchanged (legacy). When
     * active, returns {@code min(ownTimeoutMs, max(0, remaining))} -- clamped
     * at 0 so an already-expired deadline fails fast rather than waiting.
     */
    public long effectiveWait(long ownTimeoutMs, long nowMs)
    {
      if (!active)
        return ownTimeoutMs;
      long rem = deadlineAtMs - nowMs;
      if (rem < 0L) rem = 0L;
      return Math.min(ownTimeoutMs, rem);
    }
  }
}
