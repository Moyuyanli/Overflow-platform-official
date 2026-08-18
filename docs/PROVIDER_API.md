# Provider SPI v1 API Contract

本文件描述 `overflow-provider-api` 的公开边界。Provider 只依赖此 API；不得依赖
Mirai、`overflow-gateway-core` internal 或内置 OneBot 实现。

## 责任边界

| Gateway 负责 | Provider 负责 |
| --- | --- |
| 加载、注册、Session 路由、Mirai 适配、虚拟 ID 分配 | 平台 SDK、认证、协议、配置、重连、事件转换 |
| 外部 JAR ClassLoader、生命周期调度、Capability 契约检查 | 生成稳定键、实现 Operations、背压和平台游标 |
| 无法安全保留的 ID 的持久化虚拟化 | 可安全透传的原生 ID 的双向映射 |

Provider 不应向 Gateway 暴露 Wire DTO。平台专用功能使用 `NativeOperations`，平台专用
事件或消息元素使用 `BridgeEvent.Native` / `BridgeMessageElement.Native`。

## 入口与配置

实现 `PlatformProvider`，并通过 Java `ServiceLoader` 注册。实现类必须有公开无参构造函数。

```kotlin
public class MyPlatformProvider : PlatformProvider {
    override val platform = MessagePlatform.TELEGRAM
    override val descriptor = ProviderDescriptor(
        id = "my-platform",
        displayName = "My Platform",
        version = "1.0.0",
        spiVersion = ProviderSpi.CURRENT,
    )

    override suspend fun validateConfig(config: JsonObject): ConfigValidation = TODO()

    override suspend fun createSession(
        config: ProviderInstanceConfig,
        context: ProviderContext,
    ): PlatformSession = TODO()
}
```

`ProviderContext` 由 Gateway 提供：

- `providerId`、`instanceId`：当前实例标识。
- `logger`：写入宿主日志。
- `scope`：所有长生命周期协程的父 scope；`stop()` 后 Gateway 会取消它。
- `dataDirectory`：当前 Provider Session 的持久数据目录。

`validateConfig` 只验证 Provider 自己的 `config` JSON。错误应以
`ConfigViolation(path, code, message)` 返回；不要抛出普通异常作为用户配置提示。

## 会话生命周期

`PlatformSession` 是一个配置实例对应的在线账号/连接。

```text
CREATED -> STARTING -> ONLINE <-> RECONNECTING
                        \-> FAILED
ONLINE/RECONNECTING/FAILED -> STOPPING -> STOPPED
```

1. `createSession()` 返回时必须是 `CREATED`，禁止连接远端平台。
2. `start()` 设置 `STARTING`，连接和鉴权，并只在会话可用时返回 `RemoteSelf`。
3. Gateway 在 `start()` 返回后订阅 `events`。Provider 必须缓存启动期事件，推荐
   `Channel<BridgeEvent>(capacity = 256).receiveAsFlow()`。
4. 可恢复连接失败进入 `RECONNECTING`，恢复后回到 `ONLINE`；不可恢复错误进入 `FAILED`。
5. `stop()` 必须可重复调用且不抛出；关闭自身 SDK 创建的 socket、线程池和文件，然后关闭事件流。
6. 事件队列必须有界。满时选择并记录明确的丢弃或反压策略，不能无限占用内存。

## 稳定键与原生 ID

所有远端实体使用 `RemoteEntityKey(EntityKind, stableKey)` 标识。`stableKey` 必须在同一
账号与实体类型内唯一，并跨重连、进程重启保持不变。

推荐键：`user:123`、`group:456`、`member:456:123`、`channel:guild-1:channel-2`。
禁止昵称、临时列表下标、当前时间和随机 UUID。

默认情况下，Gateway 将 StableKey 映射为持久化 Mirai 虚拟数值 ID。只有当平台 ID 可安全
放入 Mirai 的正数 `Long` / `Int` 且可双向恢复时，才实现 `NativeIdOperations`：

```kotlin
override val nativeIds = object : NativeIdOperations {
    override fun resolveLong(entity: RemoteEntityKey): Long? = TODO()
    override fun reverseLong(kind: EntityKind, id: Long): RemoteEntityKey? = TODO()
}
```

不能安全转换时返回 `null`，让 Gateway 虚拟化。原生 Bot ID 与现有 Bot 冲突时 Gateway 也会回退。

## Bridge Model

Provider 将协议数据转换为以下通用模型：

- 身份与联系人：`RemoteSelf`、`RemoteUser`、`RemoteGroup`、`RemoteMember`。
- 引用：`RemoteUserRef`、`RemoteGroupRef`、`RemoteMemberRef`、`RemoteMessageRef`、
  `RemoteRequestRef`；私聊目标为 `RemoteDirectRef`，群消息目标为
  `RemoteGroupConversationRef`。
- 消息：`BridgeMessage` 的有序 `BridgeMessageElement` 列表，包含文本、提及、媒体、文件、
  回复、转发、卡片、位置、互动元素及 `Unsupported` / `Native`。
- 事件：`BridgeEvent`，包括消息收到/撤回、好友、请求、成员/群变更、禁言、名片、戳一戳和
  `Native` 事件。

所有 `BridgeEvent.occurredAtEpochSeconds` 使用 Unix 秒。事件中出现的引用必须与
Operations 返回的实体遵循同一 StableKey 规则。

未知消息段不要伪装成文本；用 `BridgeMessageElement.Unsupported(type, payload)` 保存它。

## Operations 与 Capabilities

`identity` 是唯一必需 Operations。其余 Operations 不支持时返回 `null`。Capability 是动态
`StateFlow<CapabilitySet>`：声明的能力要求相应 Operations 非空，且对应方法应确实可用。
Gateway 会拒绝违约 Session。

| Capability | Operations | 典型方法 |
| --- | --- | --- |
| `message.send.private` / `message.send.group` | `messages` | `send` |
| `message.recall` / `message.get` / `message.reaction` / `message.forward.resolve` | `messages` | `recall`、`get`、`react`、`resolveForward` |
| `contact.delete` / `contact.profile` / `interaction.nudge` | `contacts` | `delete`、`queryProfile`、`nudge` |
| `group.members.list` / `group.admin` | `members` | 列表、查询、踢出、禁言、管理员 |
| `group.rename` / `group.settings` / `group.announcements` / `group.essences` / `group.files` | `groups` | 群资料、公告、精华、文件 |
| `member.card` / `member.title` / `member.anonymous.mute` | `members` | 名片、头衔、匿名禁言 |
| `request.handle` | `requests` | `accept`、`reject` |
| `resource.file.upload` / `resource.upload` / `resource.download` | `resources` | 文件与二进制资源 |
| `identity.clients` | `identity` | `listClients` |
| `native.action` | `native` | `execute(action, parameters)` |

未宣告的可选能力可以抛出 `UnsupportedOperationException`。平台私有 Capability 使用自己的
命名空间，例如 `acme.poll.create`；不要重新定义标准 Capability 的含义。

## 最小可用实现

最小 Provider 需要：

1. `PlatformProvider`、配置校验与 `PlatformSession`。
2. `identity.getSelf()`。
3. 有界 `events` Flow 和幂等关闭。

要接入常见消息平台，通常还需要：

- 私聊/群聊：`MessageOperations.send`，消息事件使用 `BridgeEvent.MessageReceived`。
- 联系人和频道/群：`ContactOperations`、`GroupOperations`、`MemberOperations`。
- 申请/邀请：`RequestOperations` 与 `BridgeEvent.RequestReceived`。
- 文件/媒体：`ResourceOperations` 和 `ResourceRef`。

每个 `PlatformSession` 表示一个账号。多账号应使用多个 `instance` 配置和独立的稳定键作用域。
