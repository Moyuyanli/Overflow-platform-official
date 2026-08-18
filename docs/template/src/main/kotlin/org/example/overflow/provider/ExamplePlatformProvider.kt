package org.example.overflow.provider

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.mrxiaom.overflow.bridge.BridgeEvent
import top.mrxiaom.overflow.bridge.EntityKind
import top.mrxiaom.overflow.bridge.RemoteBotRef
import top.mrxiaom.overflow.bridge.RemoteEntityKey
import top.mrxiaom.overflow.bridge.RemoteSelf
import top.mrxiaom.overflow.operation.IdentityOperations
import top.mrxiaom.overflow.provider.CapabilitySet
import top.mrxiaom.overflow.provider.ConfigValidation
import top.mrxiaom.overflow.provider.ConfigViolation
import top.mrxiaom.overflow.provider.MessagePlatform
import top.mrxiaom.overflow.provider.PlatformProvider
import top.mrxiaom.overflow.provider.PlatformSession
import top.mrxiaom.overflow.provider.ProviderContext
import top.mrxiaom.overflow.provider.ProviderDescriptor
import top.mrxiaom.overflow.provider.ProviderInstanceConfig
import top.mrxiaom.overflow.provider.ProviderSpi
import top.mrxiaom.overflow.provider.SessionDescriptor
import top.mrxiaom.overflow.provider.SessionState
import java.util.concurrent.atomic.AtomicBoolean

public class ExamplePlatformProvider : PlatformProvider {
    override val platform: MessagePlatform = MessagePlatform.OTHER
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = "example",
        displayName = "Example Provider",
        version = "1.0.0",
        spiVersion = ProviderSpi.CURRENT,
        vendor = "Example Vendor",
    )

    override suspend fun validateConfig(config: JsonObject): ConfigValidation {
        val account = config["account"]?.jsonPrimitive?.contentOrNull
        return if (account.isNullOrBlank()) {
            ConfigValidation(listOf(ConfigViolation("account", "required", "must not be blank")))
        } else {
            ConfigValidation.Valid
        }
    }

    override suspend fun createSession(
        config: ProviderInstanceConfig,
        context: ProviderContext,
    ): PlatformSession = ExampleSession(
        instanceId = config.instanceId,
        account = requireNotNull(config.data["account"]?.jsonPrimitive?.contentOrNull),
        context = context,
    )
}

private class ExampleSession(
    instanceId: String,
    private val account: String,
    private val context: ProviderContext,
) : PlatformSession {
    override val descriptor: SessionDescriptor = SessionDescriptor(
        providerId = "example",
        instanceId = instanceId,
        displayName = "Example/$account",
    )
    override val state = MutableStateFlow(SessionState.CREATED)
    private val eventChannel = Channel<BridgeEvent>(capacity = 256)
    override val events = eventChannel.receiveAsFlow()
    override val capabilities = MutableStateFlow(CapabilitySet.Empty)
    private val stopped = AtomicBoolean(false)
    private val self = RemoteSelf(
        ref = RemoteBotRef(RemoteEntityKey(EntityKind.BOT, "account:$account")),
        accountStableKey = account,
        displayName = account,
    )

    override val identity: IdentityOperations = object : IdentityOperations {
        override suspend fun getSelf(): RemoteSelf = self
    }

    override suspend fun start(): RemoteSelf {
        check(state.compareAndSet(SessionState.CREATED, SessionState.STARTING)) { "Session already started" }
        context.logger.info("Starting example account {}", account)
        // Connect and authenticate the platform SDK here. Launch long-running
        // collectors in context.scope and send converted events to eventChannel.
        state.value = SessionState.ONLINE
        return self
    }

    override suspend fun stop(cause: Throwable?) {
        if (!stopped.compareAndSet(false, true)) return
        state.value = SessionState.STOPPING
        // Close SDK-owned sockets, executors and files here.
        eventChannel.close(cause)
        state.value = SessionState.STOPPED
    }
}
