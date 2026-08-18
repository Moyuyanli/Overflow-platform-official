package cn.chahuyun.overflow.provider.qqofficial.api

import cn.chahuyun.overflow.provider.qqofficial.QqEnvironment
import cn.chahuyun.overflow.provider.qqofficial.QqMessageScene
import cn.chahuyun.overflow.provider.qqofficial.QqOfficialConfig
import cn.chahuyun.overflow.provider.qqofficial.QqSendTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.Logger
import java.io.Closeable
import java.net.URLEncoder
import java.time.Clock
import java.util.Base64
import java.util.concurrent.TimeUnit
import top.mrxiaom.overflow.bridge.ResourceKind

internal class QqOpenApiClient(
    private val config: QqOfficialConfig,
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : Closeable {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val tokenManager = QqTokenManager(config, logger, clock, client, json)

    suspend fun gatewayUrl(): String {
        val response = executeAuthorizedGet("${config.environment.openApiBaseUrl}/gateway")
        val url = response.string("url")
            ?: response.string("wss_url")
            ?: throw QqApiException("gateway", 200, null, null, "QQ gateway response did not contain url")
        return url
    }

    suspend fun accessToken(): String = tokenManager.accessToken()

    suspend fun sendText(
        target: QqSendTarget,
        content: String,
        passiveMessageId: String?,
        msgSeq: Int,
    ): QqMessageResult = sendMessage(target, content, passiveMessageId, msgSeq, null)

    suspend fun sendMessage(
        target: QqSendTarget,
        content: String,
        passiveMessageId: String?,
        msgSeq: Int,
        imageFileInfo: String?,
    ): QqMessageResult {
        val endpoint = when (target) {
            is QqSendTarget.Direct -> "${config.environment.openApiBaseUrl}/v2/users/${encode(target.openId)}/messages"
            is QqSendTarget.Group -> "${config.environment.openApiBaseUrl}/v2/groups/${encode(target.openId)}/messages"
        }
        val scene = when (target) {
            is QqSendTarget.Direct -> QqMessageScene.C2C
            is QqSendTarget.Group -> QqMessageScene.GROUP
        }
        val body = buildJsonObject {
            put("msg_type", JsonPrimitive(if (imageFileInfo == null) 0 else 7))
            if (content.isNotEmpty()) put("content", JsonPrimitive(content))
            passiveMessageId?.let { put("msg_id", JsonPrimitive(it)) }
            put("msg_seq", JsonPrimitive(msgSeq))
            imageFileInfo?.let { fileInfo ->
                put("media", buildJsonObject {
                    put("file_info", JsonPrimitive(fileInfo))
                })
            }
        }.toString()
        val response = executeAuthorizedJsonPost(endpoint, body, outbound = true)
        val id = response.string("id")
            ?: response.string("message_id")
            ?: response.string("msg_id")
            ?: throw QqApiException("send.${scene.wireName}", 200, null, null, "QQ send response did not contain id")
        return QqMessageResult(
            scene = scene,
            messageId = id,
            timestampEpochSeconds = response.epochSeconds("timestamp") ?: clock.instant().epochSecond,
        )
    }

    suspend fun uploadMedia(
        target: QqSendTarget,
        bytes: ByteArray,
        kind: ResourceKind,
    ): String {
        require(kind == ResourceKind.IMAGE) {
            "official-qq media upload currently supports images only"
        }
        val endpoint = when (target) {
            is QqSendTarget.Direct -> "${config.environment.openApiBaseUrl}/v2/users/${encode(target.openId)}/files"
            is QqSendTarget.Group -> "${config.environment.openApiBaseUrl}/v2/groups/${encode(target.openId)}/files"
        }
        val body = buildJsonObject {
            put("file_type", JsonPrimitive(1))
            put("file_data", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
            put("srv_send_msg", JsonPrimitive(false))
        }.toString()
        return executeAuthorizedJsonPost(endpoint, body, outbound = true).string("file_info")
            ?: throw QqApiException("media.upload", 200, null, null, "QQ media response did not contain file_info")
    }

    private suspend fun executeAuthorizedGet(url: String): JsonObject =
        executeAuthorizedJson(Request.Builder().url(url).get(), operation = "GET $url", outbound = false)

    private suspend fun executeAuthorizedJsonPost(
        url: String,
        body: String,
        outbound: Boolean,
    ): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        return executeAuthorizedJson(request, operation = "POST $url", outbound = outbound)
    }

    private suspend fun executeAuthorizedJson(
        builder: Request.Builder,
        operation: String,
        outbound: Boolean,
    ): JsonObject {
        val token = tokenManager.accessToken()
        val first = executeJson(builder.header("Authorization", "QQBot $token").build(), operation)
        if (first.isAuthFailure && !outbound) {
            tokenManager.invalidate()
            val retryToken = tokenManager.accessToken()
            return executeJson(builder.header("Authorization", "QQBot $retryToken").build(), operation).body
        }
        if (first.isAuthFailure) tokenManager.invalidate()
        return first.body
    }

    private suspend fun executeJson(request: Request, operation: String): QqHttpResult =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val traceId = response.header("X-Tps-trace-ID")
                val body = parseBody(bodyText, operation)
                val errCode = body.int("err_code") ?: body.int("code")
                val bodyTraceId = body.string("trace_id") ?: traceId
                if (!response.isSuccessful || (errCode != null && errCode != 0)) {
                    val message = body.string("message")
                        ?: body.string("err_msg")
                        ?: response.message
                    logger.warn(
                        "QQ OpenAPI rejected operation={} httpStatus={} errCode={} traceId={}",
                        operation.redactedOperation(),
                        response.code,
                        errCode,
                        bodyTraceId,
                    )
                    throw QqApiException(operation, response.code, errCode, bodyTraceId, message)
                }
                QqHttpResult(
                    body = body,
                    isAuthFailure = response.code == 401 || response.code == 403,
                )
            }
        }

    private fun parseBody(bodyText: String, operation: String): JsonObject {
        if (bodyText.isBlank()) return JsonObject(emptyMap())
        val element = runCatching { json.parseToJsonElement(bodyText) }
            .getOrElse { throw QqApiException(operation, 200, null, null, "QQ response body is not JSON") }
        return element as? JsonObject
            ?: throw QqApiException(operation, 200, null, null, "QQ response body is not a JSON object")
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun String.redactedOperation(): String =
        replace(Regex("/v2/users/[^/]+/"), "/v2/users/<openid>/")
            .replace(Regex("/v2/groups/[^/]+/"), "/v2/groups/<openid>/")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal data class QqMessageResult(
    val scene: QqMessageScene,
    val messageId: String,
    val timestampEpochSeconds: Long,
)

internal class QqApiException(
    operation: String,
    val httpStatus: Int?,
    val errCode: Int?,
    val traceId: String?,
    message: String,
) : RuntimeException("$operation failed: $message")

private data class QqHttpResult(
    val body: JsonObject,
    val isAuthFailure: Boolean,
)

private class QqTokenManager(
    private val config: QqOfficialConfig,
    private val logger: Logger,
    private val clock: Clock,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var cached: TokenSnapshot? = null

    suspend fun accessToken(): String {
        cached?.takeIf { it.validAt(clock.instant().epochSecond) }?.let { return it.value }
        return mutex.withLock {
            cached?.takeIf { it.validAt(clock.instant().epochSecond) }?.let { return@withLock it.value }
            val refreshed = requestToken()
            cached = refreshed
            refreshed.value
        }
    }

    fun invalidate() {
        cached = null
    }

    private suspend fun requestToken(): TokenSnapshot =
        withContext(Dispatchers.IO) {
            val requestBody = buildJsonObject {
                put("appId", JsonPrimitive(config.appId))
                put("clientSecret", JsonPrimitive(config.clientSecret))
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${config.environment.tokenBaseUrl}/app/getAppAccessToken")
                .post(requestBody)
                .build()
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val body = runCatching {
                    json.parseToJsonElement(bodyText).jsonObject
                }.getOrElse {
                    throw QqApiException("token", response.code, null, null, "token response was not JSON")
                }
                val accessToken = body.string("access_token")
                    ?: throw QqApiException("token", response.code, body.int("err_code"), body.string("trace_id"), "missing access_token")
                val expiresIn = body.long("expires_in")
                    ?: throw QqApiException("token", response.code, body.int("err_code"), body.string("trace_id"), "missing expires_in")
                if (!response.isSuccessful) {
                    logger.warn(
                        "QQ token endpoint rejected request httpStatus={} errCode={} traceId={}",
                        response.code,
                        body.int("err_code"),
                        body.string("trace_id"),
                    )
                    throw QqApiException("token", response.code, body.int("err_code"), body.string("trace_id"), "token rejected")
                }
                TokenSnapshot(accessToken, clock.instant().epochSecond + expiresIn)
            }
        }
}

private data class TokenSnapshot(
    val value: String,
    val expiresAtEpochSeconds: Long,
) {
    fun validAt(nowEpochSeconds: Long): Boolean =
        nowEpochSeconds < expiresAtEpochSeconds - 60
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.epochSeconds(name: String): Long? {
    val primitive = this[name]?.jsonPrimitive ?: return null
    primitive.longOrNull?.let { return it }
    val text = primitive.contentOrNull ?: return null
    return runCatching { java.time.Instant.parse(text).epochSecond }.getOrNull()
}
