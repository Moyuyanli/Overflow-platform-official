package cn.chahuyun.overflow.provider.qqofficial

import cn.chahuyun.overflow.provider.qqofficial.api.QqOpenApiClient
import cn.chahuyun.overflow.provider.qqofficial.gateway.QqGatewaySupervisor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import top.mrxiaom.overflow.bridge.BridgeEvent
import top.mrxiaom.overflow.bridge.RemoteSelf
import top.mrxiaom.overflow.operation.IdentityOperations
import top.mrxiaom.overflow.operation.MessageOperations
import top.mrxiaom.overflow.operation.ResourceOperations
import top.mrxiaom.overflow.provider.CapabilitySet
import top.mrxiaom.overflow.provider.PlatformSession
import top.mrxiaom.overflow.provider.ProviderContext
import top.mrxiaom.overflow.provider.SessionDescriptor
import top.mrxiaom.overflow.provider.SessionState
import top.mrxiaom.overflow.provider.StandardCapabilities
import java.util.concurrent.atomic.AtomicBoolean

internal class QqOfficialSession(
    instanceId: String,
    private val config: QqOfficialConfig,
    private val context: ProviderContext,
) : PlatformSession {
    private val keys = QqStableKeys(config.appId)
    private val api = QqOpenApiClient(config, context.logger)
    private val eventChannel = Channel<BridgeEvent>(capacity = config.eventQueueCapacity)
    private val stopped = AtomicBoolean(false)
    private var self: RemoteSelf? = null
    private var gateway: QqGatewaySupervisor? = null

    override val descriptor: SessionDescriptor = SessionDescriptor(
        providerId = "official-qq",
        instanceId = instanceId,
        displayName = "QQ Official/${config.appId}",
    )
    override val state = MutableStateFlow(SessionState.CREATED)
    override val events = eventChannel.receiveAsFlow()
    override val capabilities = MutableStateFlow(CapabilitySet.Empty)
    override val identity: IdentityOperations = object : IdentityOperations {
        override suspend fun getSelf(): RemoteSelf =
            self ?: throw IllegalStateException("official-qq session is not online")
    }
    override val messages: MessageOperations = QqMessageOperations(
        state = state,
        keys = keys,
        api = api,
        logger = context.logger,
    )
    override val resources: ResourceOperations = QqResourceOperations(keys, api)

    override suspend fun start(): RemoteSelf {
        check(state.compareAndSet(SessionState.CREATED, SessionState.STARTING)) { "Session already started" }
        context.logger.info("Starting official-qq provider instance={} appId={}", descriptor.instanceId, config.appId)
        val supervisor = QqGatewaySupervisor(
            config = config,
            keys = keys,
            api = api,
            scope = context.scope,
            logger = context.logger,
            eventSink = { eventChannel.trySend(it).isSuccess },
            onRecovering = {
                state.value = SessionState.RECONNECTING
                capabilities.value = CapabilitySet.Empty
            },
            onOnline = {
                state.value = SessionState.ONLINE
                capabilities.value = ONLINE_CAPABILITIES
            },
            onFailed = {
                state.value = SessionState.FAILED
                capabilities.value = CapabilitySet.Empty
            },
        )
        gateway = supervisor
        return try {
            supervisor.startAndAwaitReady().also {
                self = it
                state.value = SessionState.ONLINE
                capabilities.value = ONLINE_CAPABILITIES
            }
        } catch (error: Throwable) {
            state.value = SessionState.FAILED
            capabilities.value = CapabilitySet.Empty
            throw error
        }
    }

    override suspend fun stop(cause: Throwable?) {
        if (!stopped.compareAndSet(false, true)) return
        state.value = SessionState.STOPPING
        capabilities.value = CapabilitySet.Empty
        runCatching { gateway?.close() }
        runCatching { api.close() }
        eventChannel.close(cause)
        state.value = SessionState.STOPPED
    }

    private companion object {
        val ONLINE_CAPABILITIES: CapabilitySet = CapabilitySet.of(
            StandardCapabilities.SEND_PRIVATE_MESSAGE,
            StandardCapabilities.SEND_GROUP_MESSAGE,
            StandardCapabilities.RESOURCE_UPLOAD,
        )
    }
}
