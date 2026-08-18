package cn.chahuyun.overflow.provider.qqofficial

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import top.mrxiaom.overflow.provider.ConfigValidation
import top.mrxiaom.overflow.provider.ConfigViolation

internal data class QqOfficialConfig(
    val appId: String,
    val clientSecret: String,
    val environment: QqEnvironment,
    val intents: Set<String>,
    val eventQueueCapacity: Int,
    val reconnect: QqReconnectConfig,
    val outbound: QqOutboundConfig,
) {
    val intentsMask: Int
        get() = intents.fold(0) { mask, intent -> mask or requireNotNull(INTENT_VALUES[intent]) }

    companion object {
        const val REQUIRED_INTENT = "GROUP_AND_C2C_EVENT"

        private val INTENT_VALUES = mapOf(REQUIRED_INTENT to (1 shl 25))

        fun validate(json: JsonObject): ConfigValidation {
            return ConfigValidation(parseViolations(json))
        }

        fun parse(json: JsonObject): QqOfficialConfig {
            val violations = parseViolations(json)
            require(violations.isEmpty()) {
                "Invalid official-qq config: ${violations.joinToString { "${it.path}:${it.code}" }}"
            }
            val reconnect = json.objectOrNull("reconnect") ?: JsonObject(emptyMap())
            val outbound = json.objectOrNull("outbound") ?: JsonObject(emptyMap())
            return QqOfficialConfig(
                appId = requireNotNull(json.stringOrNull("appId")).trim(),
                clientSecret = requireNotNull(json.stringOrNull("clientSecret")),
                environment = QqEnvironment.from(json.stringOrNull("environment") ?: "production"),
                intents = json.stringArray("intents").toSet(),
                eventQueueCapacity = json.intOrNull("eventQueueCapacity") ?: 256,
                reconnect = QqReconnectConfig(
                    initialDelayMillis = reconnect.longOrNull("initialDelayMillis") ?: 1000L,
                    maxDelayMillis = reconnect.longOrNull("maxDelayMillis") ?: 30000L,
                    maxAttempts = reconnect.intOrNull("maxAttempts") ?: 20,
                ),
                outbound = QqOutboundConfig(
                    certification = QqOutboundCertification.from(
                        outbound.stringOrNull("certification") ?: "unverified",
                    ),
                    queueCapacity = outbound.intOrNull("queueCapacity") ?: 100,
                    enqueueTimeoutMillis = outbound.longOrNull("enqueueTimeoutMillis") ?: 5000L,
                ),
            )
        }

        private fun parseViolations(json: JsonObject): List<ConfigViolation> {
            val violations = mutableListOf<ConfigViolation>()
            val appId = json.stringOrNull("appId")?.trim()
            if (appId.isNullOrBlank()) {
                violations += ConfigViolation("appId", "required", "must not be blank")
            } else if (!appId.matches(Regex("[0-9A-Za-z_-]{3,64}"))) {
                violations += ConfigViolation("appId", "invalid", "must be a QQ AppID string")
            }

            if (json.stringOrNull("clientSecret").isNullOrBlank()) {
                violations += ConfigViolation("clientSecret", "required", "must not be blank")
            }

            val environment = json.stringOrNull("environment") ?: "production"
            if (QqEnvironment.values().none { it.wireName == environment }) {
                violations += ConfigViolation("environment", "invalid", "must be production or sandbox")
            }

            val intents = json.stringArrayOrNull("intents")
            if (intents == null || REQUIRED_INTENT !in intents) {
                violations += ConfigViolation("intents", "missing-required", "must include $REQUIRED_INTENT")
            }
            intents?.filter { it !in INTENT_VALUES }?.forEach {
                violations += ConfigViolation("intents", "invalid", "unsupported intent $it")
            }

            val capacity = json.intOrNull("eventQueueCapacity") ?: 256
            if (capacity !in 1..4096) {
                violations += ConfigViolation("eventQueueCapacity", "out-of-range", "must be between 1 and 4096")
            }

            val reconnect = json.objectOrNull("reconnect") ?: JsonObject(emptyMap())
            val initialDelay = reconnect.longOrNull("initialDelayMillis") ?: 1000L
            val maxDelay = reconnect.longOrNull("maxDelayMillis") ?: 30000L
            val maxAttempts = reconnect.intOrNull("maxAttempts") ?: 20
            if (initialDelay <= 0) {
                violations += ConfigViolation("reconnect.initialDelayMillis", "invalid", "must be positive")
            }
            if (maxDelay < initialDelay) {
                violations += ConfigViolation("reconnect.maxDelayMillis", "invalid", "must be >= initialDelayMillis")
            }
            if (maxAttempts < 1) {
                violations += ConfigViolation("reconnect.maxAttempts", "invalid", "must be >= 1")
            }

            val outbound = json.objectOrNull("outbound") ?: JsonObject(emptyMap())
            val certification = outbound.stringOrNull("certification") ?: "unverified"
            if (QqOutboundCertification.values().none { it.wireName == certification }) {
                violations += ConfigViolation(
                    "outbound.certification",
                    "invalid",
                    "must be unverified or certified",
                )
            }
            val queueCapacity = outbound.intOrNull("queueCapacity") ?: 100
            if (queueCapacity !in 1..10000) {
                violations += ConfigViolation("outbound.queueCapacity", "invalid", "must be between 1 and 10000")
            }
            val enqueueTimeout = outbound.longOrNull("enqueueTimeoutMillis") ?: 5000L
            if (enqueueTimeout <= 0) {
                violations += ConfigViolation("outbound.enqueueTimeoutMillis", "invalid", "must be positive")
            }
            return violations
        }
    }
}

internal data class QqReconnectConfig(
    val initialDelayMillis: Long,
    val maxDelayMillis: Long,
    val maxAttempts: Int,
)

internal data class QqOutboundConfig(
    val certification: QqOutboundCertification,
    val queueCapacity: Int,
    val enqueueTimeoutMillis: Long,
)

internal enum class QqOutboundCertification(val wireName: String) {
    UNVERIFIED("unverified"),
    CERTIFIED("certified");

    companion object {
        fun from(value: String): QqOutboundCertification =
            values().first { it.wireName == value }
    }
}

internal enum class QqEnvironment(
    val wireName: String,
    val openApiBaseUrl: String,
    val tokenBaseUrl: String,
) {
    PRODUCTION(
        wireName = "production",
        openApiBaseUrl = "https://api.sgroup.qq.com",
        tokenBaseUrl = "https://bots.qq.com",
    ),
    SANDBOX(
        wireName = "sandbox",
        openApiBaseUrl = "https://sandbox.api.sgroup.qq.com",
        tokenBaseUrl = "https://bots.qq.com",
    );

    companion object {
        fun from(value: String): QqEnvironment =
            values().first { it.wireName == value }
    }
}

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOrNull(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.longOrNull(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    this[name] as? JsonObject

private fun JsonObject.stringArray(name: String): List<String> =
    requireNotNull(stringArrayOrNull(name))

private fun JsonObject.stringArrayOrNull(name: String): List<String>? {
    val value = this[name] ?: return null
    val array = value as? JsonArray ?: return null
    return array.mapNotNull { it.jsonPrimitive.contentOrNull }
}
