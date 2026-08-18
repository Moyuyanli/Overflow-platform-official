# Overflow Provider: QQ Official

`overflow-provider-official-qq` is an external Overflow Gateway Provider for
QQ Bot Open Platform API v2 C2C and group chat.

It is loaded by Gateway through Java `ServiceLoader` from the `adapters/`
directory. It does not depend on Mirai, Gateway internals, or the built-in
OneBot implementation.

## Compatibility

- Provider ID: `official-qq`
- Provider API: `cn.chahuyun:overflow-provider-api:0.1.0-gateway-20260720.085313-2`
- Source JAR observed SHA-256: `F088BDEA59C949B5AD812D2BF4EEFA8214FEEAE450F90EBF30E42778F6F9C620`
- Build JDK: 11 or 17
- Bytecode target: Java 8

SPI v1 is experimental. Rebuild and re-audit this provider when changing the
Gateway or Provider API artifact.

## Implemented Surface

- Configuration validation for QQ AppID, secret, environment, intents, queue
  size, reconnect policy and outbound policy.
- QQ access-token fetch and in-memory refresh with a 60-second early-expiry
  window.
- QQ Gateway WebSocket startup with Hello, Identify, READY, heartbeat ACK and
  basic Resume frame support.
- Bounded startup/runtime event queue.
- Stable opaque keys for bot, user, group, member, conversation and message
  refs. Native numeric IDs are intentionally not exposed.
- `IdentityOperations.getSelf`.
- `MessageOperations.send` for plain text C2C and group messages.
- Basic inbound C2C/group text event mapping.

Unsupported message elements fail before sending. Recall, media, resources,
contacts, member/group management and request handling are not declared until
their QQ endpoint mapping and sandbox evidence are recorded.

## Build

The project does not include a Gradle Wrapper. Use an installed Gradle, or add
one locally:

```powershell
gradle -p overflow-provider-official-qq clean build --console=plain
```

The deployable fat JAR is:

```text
overflow-provider-official-qq/build/libs/overflow-provider-official-qq-0.1.0-SNAPSHOT.jar
```

The build runs `verifyProviderArtifact`, which checks that the Provider
implementation class and SPI file are present and that Gateway-supplied API,
Kotlin, coroutines, serialization and slf4j classes are not bundled.

## Configuration

Put the built JAR in Gateway's `adapters/` directory. The JAR contains
`META-INF/overflow/providers/official-qq.json`; Gateway will append a disabled
`official-qq/main` entry to `overflow.json` on startup when that instance is not
already configured. Fill in local credentials and then set `enabled` to `true`.

Never commit real `clientSecret` values. The provider logs AppID, operation
names, HTTP status, QQ error code and trace IDs, but does not log secrets,
tokens, OpenIDs or message content.

## Acceptance Boundary

A successful build only proves source/API compatibility and artifact shape.
Real integration acceptance still requires QQ sandbox evidence for:

- C2C login, inbound message, outbound text send and passive reply.
- Group login, inbound message and outbound text send.
- Token refresh.
- WebSocket Resume and rejected Resume -> fresh Identify.
- Duplicate event replay behavior.
- Clean shutdown.
