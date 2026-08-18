# QQ Official Provider Development Design

## 1. Purpose and fixed boundaries

This document is the implementation specification for an external Overflow
Provider that connects to the QQ Bot Open Platform API v2. Its delivery unit is
an independently built Fat JAR loaded by Overflow Gateway through
`ServiceLoader`; it does not change Gateway, the Mirai adapter, or the Provider
API.

The Provider supports QQ **C2C (single chat)** and **group chat** only. QQ Guild,
Guild text channels, and Guild direct messages are deliberately out of scope.
The implementation must not use QQ Guild APIs as a substitute for group APIs.

The binary contract is pinned to:

```text
cn.chahuyun:overflow-provider-api:0.1.0-gateway-20260720.085313-2
```

Do not resolve `0.1.0-gateway-SNAPSHOT` at build or release time. The Provider
must be rebuilt and re-audited before changing this coordinate.

The source of truth for available capabilities is the intersection of this
artifact's public types and QQ's current v2 endpoint/event documentation. A
QQ feature is implemented only when that intersection has a direct, lossless
mapping. Do not expose a synthetic Operation, custom standard capability, or
`NativeOperations` workaround merely to make an otherwise unsupported QQ API
available.

## 2. Transport decision

QQ API v2 uses two different transport roles:

| Concern | Required transport | Design decision |
| --- | --- | --- |
| Receive events | QQ Gateway WebSocket | Required and exclusive |
| Identify, heartbeat, Resume | QQ Gateway WebSocket | Required and exclusive |
| Obtain access token | HTTPS OpenAPI | Required |
| Send, recall, inspect, upload, or manage data | HTTPS OpenAPI | Required |

The QQ WebSocket protocol defines Hello, Identify, heartbeat and Resume frames;
it does **not** define a business-message send opcode. HTTP is therefore not a
fallback for WebSocket message delivery: it is the official and sole outbound
business API. A WebSocket being online is nevertheless a precondition for
certain QQ functions and for this Provider to declare its active capabilities.

No Webhook and no HTTP event polling fallback is implemented. On a WebSocket
failure the Provider first attempts Resume and then Identify, as specified in
section 6. If it cannot establish a valid event stream before the configured
retry budget expires, its session becomes `FAILED`; it must not claim to be
online just because HTTPS requests could still succeed.

Official references:

- [Access token](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html)
- [WebSocket event protocol](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html)
- [Message overview and reply windows](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html)
- [Send C2C message](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html)
- [Send group message](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html)

## 3. Project and packaging

Create a standalone Gradle/Kotlin project named `overflow-provider-official-qq`.
It derives its basic SPI and Shadow setup from the Provider development-kit
template, but it is not placed under this repository's existing `docs/`
directory.

```text
overflow-provider-official-qq/
  build.gradle.kts
  settings.gradle.kts
  src/main/kotlin/.../QqOfficialPlatformProvider.kt
  src/main/kotlin/.../QqOfficialSession.kt
  src/main/kotlin/.../api/QqOpenApiClient.kt
  src/main/kotlin/.../gateway/QqGatewaySupervisor.kt
  src/main/kotlin/.../mapping/QqEventMapper.kt
  src/main/kotlin/.../mapping/QqMessageMapper.kt
  src/main/kotlin/.../state/*.kt
  src/test/kotlin/...
  src/main/resources/META-INF/services/top.mrxiaom.overflow.provider.PlatformProvider
```

Use a Java 8 bytecode target. Package private HTTP/WebSocket dependencies in a
Shadow JAR and relocate dependencies if their packages can collide with the
host. Do not package `overflow-provider-api`, Kotlin stdlib, coroutines,
serialization, or `slf4j-api`.

The Provider must publish exactly one implementation through:

```text
META-INF/services/top.mrxiaom.overflow.provider.PlatformProvider
```

The implementation class is `QqOfficialPlatformProvider`, has a public
no-argument constructor, uses descriptor ID `official-qq`, and performs no
network I/O in its constructor or in `createSession`.

## 4. Configuration contract

All configuration belongs below the Gateway Provider instance's `config`
object. Secrets are never committed, returned by diagnostics, or logged.

```json
{
  "providers": [{
    "enabled": true,
    "provider": "official-qq",
    "instance": "main",
    "config": {
      "appId": "123456789",
      "clientSecret": "replace-in-local-config-only",
      "environment": "production",
      "intents": ["GROUP_AND_C2C_EVENT"],
      "eventQueueCapacity": 256,
      "reconnect": {
        "initialDelayMillis": 1000,
        "maxDelayMillis": 30000,
        "maxAttempts": 20
      },
      "outbound": {
        "certification": "unverified",
        "queueCapacity": 100,
        "enqueueTimeoutMillis": 5000
      }
    }
  }]
}
```

`validateConfig` returns `ConfigViolation` entries, never connection failures,
for the following conditions:

| Path | Code | Rule |
| --- | --- | --- |
| `appId` | `required` / `invalid` | Nonblank QQ AppID string |
| `clientSecret` | `required` | Nonblank secret |
| `environment` | `invalid` | `production` or `sandbox` |
| `intents` | `missing-required` | Includes `GROUP_AND_C2C_EVENT` |
| `eventQueueCapacity` | `out-of-range` | Integer 1 through 4096 |
| `reconnect.*` | `invalid` | Positive values; max delay >= initial delay; attempts >= 1 |
| `outbound.*` | `invalid` | Recognized certification and bounded positive queue/timeout |

The implementation uses QQ's documented API base for the selected environment;
it does not expose arbitrary endpoint URLs in normal configuration. Test-only
HTTP and WSS endpoint overrides are injected through constructors, not user
configuration.

## 5. Components and ownership

```text
Gateway
  -> QqOfficialPlatformProvider
       -> QqOfficialSession
            -> QqTokenManager ------> QQ HTTPS token endpoint
            -> QqOpenApiClient -----> QQ HTTPS C2C/group endpoints
            -> QqGatewaySupervisor -> QQ Gateway WebSocket
                 -> QqEventMapper -> bounded BridgeEvent channel
            -> QqGatewayCheckpointStore  (ProviderContext.dataDirectory)
            -> EventDedupeStore          (ProviderContext.dataDirectory)
            -> ReplyContextStore         (ProviderContext.dataDirectory)
            -> OutboundRateLimiter
```

### `QqOfficialPlatformProvider`

- Owns descriptor and config validation.
- Creates a session in `CREATED` state only.
- Uses one independent data directory and key namespace per Gateway instance.

### `QqOfficialSession`

- Owns `SessionState`, `CapabilitySet`, operations and resource shutdown.
- Exposes events with `Channel<BridgeEvent>(eventQueueCapacity).receiveAsFlow()`.
- Starts the gateway supervisor as a child of `ProviderContext.scope` before
  returning from `start`; READY is required before it returns `RemoteSelf`.
- Has an atomic, idempotent `stop(cause)` that closes HTTP/WebSocket resources,
  stops coroutines it owns, closes the event channel, and ends in `STOPPED`.

### `QqTokenManager`

- Calls `POST /app/getAppAccessToken` with `appId` and `clientSecret`.
- Caches the access token in memory only and has a mutex/single-flight refresh
  path. Treat it as stale at `expiresAt - 60 seconds`.
- Adds `Authorization: QQBot <access-token>` only inside the HTTP client.
- Does not persist, serialize or log the secret or token. Failure to obtain a
  token makes startup fail without printing request bodies.

### `QqOpenApiClient`

- Centralizes JSON codecs, authorization, correlation, request timeouts and
  error translation. It records QQ `trace_id` from the response body or
  `X-Tps-trace-ID` response header in structured logs.
- A request succeeds only when both its HTTP response semantics and QQ
  `err_code` indicate success. HTTP 201/202 responses with an error body are
  not treated as success.
- Retries once after an authentication failure by invalidating and refreshing
  the token. It may retry a documented transient, idempotent read once. It
  never automatically retries an outbound POST whose server acceptance is
  unknown, preventing duplicate messages.

### `QqGatewaySupervisor`

- Obtains the WSS endpoint through the official gateway endpoint, then opens
  one `[0, 1]` shard only. Guild sharding is out of scope.
- Processes `Hello`, `READY`, event dispatches, heartbeat ACKs and `RESUMED`.
- Sends Identify using `QQBot <access-token>` and configured intents. It begins
  heartbeat scheduling from the Hello interval and includes the latest received
  sequence in heartbeat payloads.
- Uses Resume after transient close when a valid checkpoint exists. On resume
  rejection (including 4006/4007) it clears the checkpoint and performs a fresh
  Identify. It retries 4009 via Resume; it fails permanently for 4914/4915;
  it applies bounded exponential backoff and jitter for other retryable cases.

### Persistent state

All files are scoped under `ProviderContext.dataDirectory`, atomically written
through a temporary sibling file and replace/move. On a corrupt state file,
log the file name and cause without secrets, discard it, and Identify anew.

| Store | Key / data | Bound and expiry |
| --- | --- | --- |
| `QqGatewayCheckpointStore` | WSS `session_id`, latest acknowledged event `seq`, updated time | One record; retained only while resumable |
| `EventDedupeStore` | event ID, or `scene + msg_id + msg_idx` | TTL 24 hours; maximum 50,000 entries; evict oldest expiry first |
| `ReplyContextStore` | source message ID, target, source event ID, next `msg_seq`, expiry | C2C 60 minutes; group 5 minutes; maximum 10,000 entries |

The checkpoint sequence is advanced only after mapping succeeds, dedupe accepts
the event, and the mapped `BridgeEvent` is accepted by the bounded event queue.
This deliberately favors replay over silent loss after a crash.

## 6. Session and capability state machine

```text
CREATED --start--> STARTING --READY--> ONLINE
                              |          |
                              |          +--WebSocket closed--> RECONNECTING
                              |                                  |     |
                              |                                  |     +--retry budget exhausted--> FAILED
                              |                                  +--Resume/Identify succeeds--> ONLINE
                              +--bad credentials/permanent error--> FAILED

ONLINE | RECONNECTING | FAILED --stop--> STOPPING --> STOPPED
```

`start` fetches a token, starts the WSS protocol and waits for READY. It returns
the `RemoteSelf` created from READY only after state is `ONLINE`. If a
recoverable disconnect happens after readiness but before `start` returns,
return only once the session is `ONLINE` again; otherwise fail startup.

Before READY, and whenever state is `RECONNECTING`, `FAILED`, `STOPPING`, or
`STOPPED`, publish `CapabilitySet.Empty`. Publish the audited capability set
only in `ONLINE`. `identity` remains a non-null mandatory operation but
`getSelf()` throws a typed offline/session error outside ONLINE; no optional
operation may be invoked as if it were connected.

An event queue full condition is explicit: attempt nonblocking enqueue, log an
event type and sequence without message text, do not advance the checkpoint,
close the WebSocket, and re-enter recovery. This obtains a replay instead of
dropping a user message while keeping memory bounded.

## 7. Identity and message mapping

QQ OpenIDs are opaque strings and cannot safely implement `NativeIdOperations`.
Use Gateway virtual IDs with these stable keys, prefixed internally with the
session `appId` namespace to prevent accidental cross-bot mixing:

| Entity | Stable key suffix |
| --- | --- |
| Bot | `bot:{appId}` |
| User | `user:{user_openid}` |
| Group | `group:{group_openid}` |
| Group member | `member:{group_openid}:{member_openid}` |
| Message | `message:{c2c|group}:{id}` |
| Direct conversation | `direct:{user_openid}` |
| Group conversation | `conversation:group:{group_openid}` |

The actual Provider key is `app:{appId}:{suffix}`. Never derive a key from
display names, mutable remarks, random data, list positions, WSS sequence, or
timestamps.

Map C2C and group message events to `BridgeEvent.MessageReceived` using
`RemoteDirectRef` and `RemoteGroupConversationRef` respectively. Preserve event
time as Unix epoch seconds. Text maps to `BridgeMessageElement.Text`; mapped
attachments become `Image`, `Audio`, `Video`, or `File` `ResourceRef` values
only when QQ provides sufficient URL/name/type metadata. ARK cards, message
types and elements that cannot be represented faithfully become
`BridgeMessageElement.Unsupported` with sanitized structured payload. Never
flatten an unknown card into a text approximation.

QQ event identifiers and reply metadata are Provider-internal state; the Bridge
event source remains the stable `RemoteMessageRef`. The mapper records each
valid inbound message in `ReplyContextStore` before its Bridge event is
enqueued. A reply send obtains a context matching its target or explicit
`BridgeMessageElement.Reply`, rejects expired/missing context as a clear
operation failure, and atomically assigns a unique `msg_seq` for that QQ source
message.

## 8. Capability audit and delivery matrix

Before implementation, download the pinned JAR and its sources, record SHA-256
in the release evidence, and inspect these public types:

```powershell
$v = '0.1.0-gateway-20260720.085313-2'
$repo = 'https://central.sonatype.com/repository/maven-snapshots/cn/chahuyun/overflow-provider-api/0.1.0-gateway-SNAPSHOT'
Invoke-WebRequest "$repo/overflow-provider-api-$v-sources.jar" -OutFile ".\overflow-provider-api-$v-sources.jar"
jar tf ".\overflow-provider-api-$v-sources.jar"
```

The audit must inspect `ProviderApi.kt`, `Operations.kt`, `Events.kt`,
`Identity.kt`, `Message.kt` and `ExtendedModels.kt`. The pinned sources expose
`IdentityOperations`, `MessageOperations`, `ContactOperations`,
`GroupOperations`, `MemberOperations`, `RequestOperations`, and
`ResourceOperations`; all except identity are optional. They also expose the
standard capability keys below.

The implementation matrix is intentionally conservative:

| Overflow API / capability | QQ C2C or group evidence required | Decision |
| --- | --- | --- |
| `IdentityOperations.getSelf` | READY identity / bot endpoint | Required |
| `MessageOperations.send`, `message.send.private` | C2C send endpoint and target mapping | Implement after sandbox proof |
| `MessageOperations.send`, `message.send.group` | group send endpoint and target mapping | Implement after sandbox proof |
| `MessageOperations.recall`, `message.recall` | matching QQ C2C/group recall endpoint | Implement after sandbox proof |
| `MessageOperations.get`, `message.get` | direct QQ fetch-by-message endpoint preserving target semantics | Implement only if audit confirms |
| `MessageOperations.react`, `message.reaction` | C2C/group reaction endpoint and event support | Do not declare unless confirmed |
| `ResourceOperations`, `resource.upload` / `resource.download` | documented C2C/group upload and retrievable resource semantics | Implement only if audit confirms lossless mapping |
| Contacts, groups, members, requests | matching QQ C2C/group endpoint plus matching event/object semantics | Implement only row by row after audit |
| Guild-only operations or events | Not in scope | Never implement |

No row moves to “implemented” until a developer records: QQ endpoint/event URL,
request/response DTO, target and stable-key mapping, capability key, unit test,
mock protocol test, and QQ sandbox evidence. Each declared standard capability
requires the matching non-null operations property and a callable method while
ONLINE; otherwise it must not appear in `CapabilitySet`.

## 9. Outbound policy

`MessageOperations.send` accepts only representations with a confirmed QQ
mapping. The first implementation supports a single text element and a reply
context when available. Mixed or unsupported element lists fail before sending;
they are not silently concatenated. Add media, Markdown, keyboard, card and
other mapping only after their audit rows are accepted.

Use bounded per-session work queues and token buckets. Default C2C policy is
the documented unverified profile (5 QPS and 30 QPM per Bot); default group
policy is 30 QPM per Bot. In both modes, enforce the documented 20 QPM per
target relationship and daily ceiling. An explicit `certification` setting may
select the documented certified profile, but it is an operator attestation and
must be verified in the QQ console before production use.

The limiter rejects a request when it cannot acquire capacity within
`enqueueTimeoutMillis`; it does not grow an unbounded queue. Log operation,
scene, redacted target hash, HTTP status, QQ error code and trace ID, but never
message contents, OpenIDs, AppSecret or access tokens.

## 10. Implementation order

1. Create the external Gradle project, lock the Provider API artifact and add
   SPI/Shadow artifact checks.
2. Run the capability audit and commit its completed matrix with the pinned JAR
   checksum before implementing optional operations.
3. Implement configuration, redacted logging, stable-key helpers, JSON DTOs,
   `QqTokenManager` and `QqOpenApiClient` error classification.
4. Implement checkpoint, dedupe and reply-context stores with atomic files and
   bounded eviction.
5. Implement the WSS supervisor, lifecycle transitions, reconnect policy and
   event backpressure behavior.
6. Implement C2C/group event and text-message mappers, then the audited send
   and recall operations. Add later audited operations independently.
7. Add rate limits, capability publication, artifact verification and release
   evidence generation.

## 11. Test and acceptance plan

### Automated unit tests

- Config validation and secret-redaction tests.
- Stable keys for C2C/group users, members, messages and conversations.
- Token single-flight refresh, 60-second early refresh and redacted failures.
- HTTP classification for 401 refresh-once, 429, 5xx, QQ `err_code`, and trace
  ID extraction; unknown-result outbound POST must not retry.
- C2C/group text and attachment mapping; unknown QQ payload preservation.
- Dedupe TTL/capacity eviction, reply expiry and concurrent `msg_seq` allocation.
- Rate-limit profiles, target quotas, queue timeout and no unbounded buffering.

### Mock protocol tests

- Hello -> Identify -> READY -> heartbeat -> ACK leads to `ONLINE` and active
  capabilities.
- Startup events are buffered until the Gateway subscribes.
- Resume restores a checkpoint and replays events; duplicate replay emits once.
- 4006/4007 clears resume state and Identifies; 4009 retries Resume; 4914/4915
  move to `FAILED`; retry budget exhaustion becomes `FAILED`.
- Full event channel forces recovery and leaves the sequence uncommitted.
- `stop` is idempotent during ONLINE, reconnecting and failed states.

### Provider artifact and QQ sandbox acceptance

- Build the Shadow JAR and assert its implementation class and SPI file exist;
  assert it contains none of the Gateway-supplied Provider API/Kotlin/
  coroutines/serialization/slf4j classes.
- In QQ sandbox, prove C2C and group login, inbound delivery, text send,
  passive reply, duplicate event handling, recall, token refresh, WebSocket
  Resume, rejected Resume -> Identify, process-restart recovery and clean stop.
- Preserve only a redacted configuration, JAR SHA-256, exact API/Gateway/JDK/
  Gradle/Kotlin versions, startup/shutdown logs and relevant QQ trace IDs.

A compiling JAR, a passing mock server, HTTP 200, or a live WebSocket alone is
not integration acceptance. The Provider is accepted only after QQ sandbox
evidence proves the end-to-end scenarios above.
