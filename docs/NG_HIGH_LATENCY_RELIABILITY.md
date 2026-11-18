# NG high-latency connection reliability

SageTV-NG treats transport progress separately from client type and network
topology. Successful reads, completed replies, channel attachment, and media
command progress extend an idle deadline. They never extend the absolute cap
for the current operation or playback attempt.

This policy does not infer link quality from an address, VPN, subnet, device
model, or `localConnection`. It also does not change playback delivery for
other clients.

## UI transport

The 8-byte MiniClient introduction uses:

- `ui/remote_ui_connection_timeout`: idle timeout, default 15 seconds.
- `ui/remote_ui_connection_absolute_timeout_ms`: absolute cap, default four
  times the idle timeout with a 60-second minimum.

UI command replies use:

- `ui/miniclient_reply_timeout_ms`: idle timeout, default 60 seconds.
- `ui/miniclient_reply_absolute_timeout_ms`: absolute cap, default 120 seconds.

UI replies are an untagged FIFO. Once a reply actually reaches either timeout,
the transport is closed and may reconnect through the existing bounded
reconnect path. Continuing to issue commands could otherwise consume the late
reply as the result of a newer command.

Fresh UI transports own their map entry before the superseded renderer is
closed. Cleanup removes an entry only when it still owns that exact socket.
Only the first valid reconnect socket may replace a healthy active transport;
delayed duplicates are rejected.

## Player transport

`PlayerTimeoutPolicy` resolves legacy base properties and optional NG profile
overrides under:

```text
miniplayer/profile/<profileId>/<suffix>
miniplayer/profile/ng_default/<suffix>
```

Relevant base properties are:

- `miniplayer/player_socket_reconnect_initial_wait_ms`
- `miniplayer/player_socket_reconnect_expire_wait_ms`
- `miniplayer/player_socket_reconnect_attempts`
- `miniplayer/player_socket_reconnect_backoff_ms`
- `ui/remote_player_connection_timeout`
- `ui/remote_player_connection_absolute_timeout_ms`
- `miniplayer/player_socket_playback_deadline_ms`
- `miniplayer/player_socket_playback_absolute_deadline_ms`

The recommended NG profile uses a 15-second idle playback budget and a
120-second absolute cap. Profile defaults are opt-in through configuration or
`PlayerTimeoutPolicy.seedRecommendedNgDefaults()`; absent profile settings,
existing base-property behavior is preserved.

A single NG playback deadline is carried through player-channel acquisition,
INIT, Party binding when applicable, and OPEN. Partial reply bytes extend only
the idle portion. A player socket replaces a healthy mapped channel only when
the server explicitly expects media reconnect or reacquisition.

Legacy clients gain progress-aware command replies and socket ownership
protection without requiring new protocol fields. Their historical stacked
player-channel acquisition timing remains unchanged unless an operator
configures a whole-attempt deadline.

## Impairment validation

Use packet-level impairment only on an isolated test interface or network
namespace. Applying `netem` to a production interface disrupts every flow on
that interface.

For an approximately 900-1200 ms round trip when the same profile is applied at
both ends:

```sh
tc qdisc replace dev TEST_IFACE root netem delay 500ms 100ms 25% reorder 10% 50%
```

Exercise UI connection, player-channel attachment, playback startup, one-channel
reset, and a no-progress peer. Remove the profile afterward:

```sh
tc qdisc del dev TEST_IFACE root
```

Acceptance requires progressing connections to reach UI readiness and complete
playback acquisition, while a no-progress peer still fails within its absolute
cap. Logs must show the affected channel, phase, progress age, and stable
closure reason, and a delayed socket must not replace the current owner.
