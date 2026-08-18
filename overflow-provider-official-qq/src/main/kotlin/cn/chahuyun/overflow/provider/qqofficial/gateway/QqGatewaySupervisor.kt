package cn.chahuyun.overflow.provider.qqofficial.gateway

import cn.chahuyun.overflow.provider.qqofficial.QqMessageScene
import cn.chahuyun.overflow.provider.qqofficial.QqOfficialConfig
import cn.chahuyun.overflow.provider.qqofficial.QqStableKeys
import cn.chahuyun.overflow.provider.qqofficial.api.QqOpenApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.Logger
import top.mrxiaom.overflow.bridge.BridgeEvent
import top.mrxiaom.overflow.bridge.BridgeMessage
import top.mrxiaom.overflow.bridge.BridgeMessageElement
import top.mrxiaom.overflow.bridge.RemoteGroup
import top.mrxiaom.overflow.bridge.RemoteMember
import top.mrxiaom.overflow.bridge.RemoteSelf
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class QqGatewaySupervisor(
    private val config: QqOfficialConfig,
    private val keys: QqStableKeys,
    private val api: QqOpenApiClient,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val eventSink: (BridgeEvent) -> Boolean,
    private val onRecovering: () -> Unit,
    private val onOnline: () -> Unit,
    private val onFailed: (Throwable) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .build(),
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val closed = AtomicBoolean(false)
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var sequence: Long? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var accessToken: String? = null

    suspend fun startAndAwaitReady(): RemoteSelf {
        var attempt = 0
        var delayMillis = config.reconnect.initialDelayMillis
        var lastFailure: Throwable? = null
        while (!closed.get() && attempt < config.reconnect.maxAttempts) {
            attempt++
            val ready = CompletableDeferred<RemoteSelf>()
            try {
                accessToken = api.accessToken()
                val gatewayUrl = api.gatewayUrl()
                val listener = Listener(ready)
                webSocket = client.newWebSocket(Request.Builder().url(gatewayUrl).build(), listener)
                val self = ready.await()
                onOnline()
                return self
            } catch (error: Throwable) {
                if (closed.get()) throw error
                lastFailure = error
                onRecovering()
                logger.warn(
                    "QQ gateway startup attempt {} failed: {}",
                    attempt,
                    error.message ?: error.javaClass.name,
                )
                delay(delayMillis)
                delayMillis = (delayMillis * 2).coerceAtMost(config.reconnect.maxDelayMillis)
            }
        }
        val failure = IllegalStateException(
            "QQ gateway did not become READY after ${config.reconnect.maxAttempts} attempts",
            lastFailure,
        )
        onFailed(failure)
        throw failure
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeatJob?.cancel()
        webSocket?.close(1000, "session stopped")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private inner class Listener(
        private val ready: CompletableDeferred<RemoteSelf>,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            logger.info("QQ gateway websocket opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val payload = runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse {
                    logger.warn("QQ gateway sent non-json frame")
                    return
                }
            payload.long("s")?.let { sequence = it }
            when (payload.int("op")) {
                0 -> handleDispatch(payload, ready)
                7 -> webSocket.close(4000, "server requested reconnect")
                9 -> {
                    sessionId = null
                    identify(webSocket)
                }
                10 -> {
                    startHeartbeat(webSocket, payload.objectOrNull("d")?.long("heartbeat_interval") ?: 45000L)
                    identify(webSocket)
                }
                11 -> logger.debug("QQ gateway heartbeat ack seq={}", sequence)
                else -> logger.debug("QQ gateway ignored opcode {}", payload.int("op"))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            if (!closed.get()) {
                onRecovering()
                logger.warn("QQ gateway websocket closed code={} reason={}", code, reason)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            heartbeatJob?.cancel()
            if (!closed.get()) {
                if (!ready.isCompleted) ready.completeExceptionally(t)
                onFailed(t)
            }
        }
    }

    private fun handleDispatch(payload: JsonObject, ready: CompletableDeferred<RemoteSelf>) {
        val type = payload.string("t") ?: return
        val data = payload.objectOrNull("d") ?: JsonObject(emptyMap())
        when (type) {
            "READY" -> {
                sessionId = data.string("session_id")
                val user = data.objectOrNull("user") ?: data
                val displayName = user.string("username") ?: user.string("name") ?: config.appId
                if (!ready.isCompleted) ready.complete(keys.self(displayName))
            }
            "RESUMED" -> onOnline()
            "C2C_MESSAGE_CREATE" -> emitMessage(data, QqMessageScene.C2C)
            "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE" -> emitMessage(data, QqMessageScene.GROUP)
            else -> logger.debug("QQ gateway ignored dispatch type={}", type)
        }
    }

    private fun emitMessage(data: JsonObject, scene: QqMessageScene) {
        val messageId = data.string("id") ?: data.string("msg_id") ?: run {
            logger.warn("QQ gateway message dispatch missing message id scene={} fields={}", scene.wireName, data.keys)
            return
        }
        val content = data.string("content").orEmpty()
        val occurredAt = data.epochSeconds("timestamp") ?: Instant.now().epochSecond
        val author = data.objectOrNull("author") ?: data.objectOrNull("sender") ?: JsonObject(emptyMap())
        val userOpenId = author.string("user_openid")
            ?: author.string("member_openid")
            ?: data.string("user_openid")
            ?: run {
                logger.warn("QQ gateway message dispatch missing author open id scene={} fields={}", scene.wireName, data.keys)
                return
            }
        val sender = keys.userEntity(
            userOpenId = userOpenId,
            displayName = author.string("username") ?: author.string("name"),
        )
        val subject = when (scene) {
            QqMessageScene.C2C -> keys.direct(userOpenId)
            QqMessageScene.GROUP -> {
                val groupOpenId = data.string("group_openid") ?: run {
                    logger.warn("QQ gateway group message dispatch missing group open id fields={}", data.keys)
                    return
                }
                keys.groupConversation(groupOpenId)
            }
        }
        val member = if (scene == QqMessageScene.GROUP) {
            val groupOpenId = data.string("group_openid") ?: return
            RemoteMember(
                ref = keys.member(groupOpenId, userOpenId),
                user = sender,
            )
        } else {
            null
        }
        val bridgeMessage = BridgeMessage(
            listOf(BridgeMessageElement.Text(content))
        )
        val event = BridgeEvent.MessageReceived(
            sender = sender,
            subject = subject,
            message = bridgeMessage,
            source = keys.message(scene, messageId),
            occurredAtEpochSeconds = occurredAt,
            member = member,
        )
        if (!eventSink(event)) {
            logger.warn("QQ gateway event queue is full; closing websocket for replay scene={} seq={}", scene.wireName, sequence)
            webSocket?.close(4000, "event queue full")
        }
    }

    private fun identify(webSocket: WebSocket) {
        val token = accessToken ?: return
        val resumeSessionId = sessionId
        val payload = if (resumeSessionId != null) {
            buildJsonObject {
                put("op", JsonPrimitive(6))
                put("d", buildJsonObject {
                    put("token", JsonPrimitive("QQBot $token"))
                    put("session_id", JsonPrimitive(resumeSessionId))
                    sequence?.let { put("seq", JsonPrimitive(it)) }
                })
            }
        } else {
            buildJsonObject {
                put("op", JsonPrimitive(2))
                put("d", buildJsonObject {
                    put("token", JsonPrimitive("QQBot $token"))
                    put("intents", JsonPrimitive(config.intentsMask))
                    put("shard", buildJsonArray {
                        add(JsonPrimitive(0))
                        add(JsonPrimitive(1))
                    })
                    put("properties", buildJsonObject {
                        put("os", JsonPrimitive(System.getProperty("os.name") ?: "unknown"))
                        put("browser", JsonPrimitive("overflow-provider-official-qq"))
                        put("device", JsonPrimitive("overflow-provider-official-qq"))
                    })
                })
            }
        }
        webSocket.send(payload.toString())
    }

    private fun startHeartbeat(webSocket: WebSocket, intervalMillis: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (!closed.get()) {
                delay(intervalMillis)
                val heartbeat = buildJsonObject {
                    put("op", JsonPrimitive(1))
                    put("d", sequence?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
                }
                if (!webSocket.send(heartbeat.toString())) {
                    logger.warn("QQ gateway heartbeat send failed")
                    break
                }
            }
        }
    }
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    this[name] as? JsonObject

private fun JsonObject.epochSeconds(name: String): Long? {
    val primitive = this[name]?.jsonPrimitive ?: return null
    primitive.longOrNull?.let { return it }
    val text = primitive.contentOrNull ?: return null
    return runCatching { Instant.parse(text).epochSecond }.getOrNull()
}
