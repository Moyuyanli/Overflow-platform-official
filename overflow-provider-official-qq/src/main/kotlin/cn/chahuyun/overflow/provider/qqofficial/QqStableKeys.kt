package cn.chahuyun.overflow.provider.qqofficial

import top.mrxiaom.overflow.bridge.DirectConversationKind
import top.mrxiaom.overflow.bridge.EntityKind
import top.mrxiaom.overflow.bridge.RemoteBotRef
import top.mrxiaom.overflow.bridge.RemoteDirectRef
import top.mrxiaom.overflow.bridge.RemoteEntityKey
import top.mrxiaom.overflow.bridge.RemoteGroupConversationRef
import top.mrxiaom.overflow.bridge.RemoteGroupRef
import top.mrxiaom.overflow.bridge.RemoteMemberRef
import top.mrxiaom.overflow.bridge.RemoteMessageRef
import top.mrxiaom.overflow.bridge.RemoteSelf
import top.mrxiaom.overflow.bridge.ResourceRef
import top.mrxiaom.overflow.bridge.RemoteUser
import top.mrxiaom.overflow.bridge.RemoteUserRef
import java.util.Base64

internal class QqStableKeys(private val appId: String) {
    private val prefix = "app:$appId:"

    fun bot(): RemoteBotRef =
        RemoteBotRef(RemoteEntityKey(EntityKind.BOT, prefix + "bot:$appId"))

    fun user(userOpenId: String): RemoteUserRef =
        RemoteUserRef(RemoteEntityKey(EntityKind.USER, prefix + "user:$userOpenId"))

    fun group(groupOpenId: String): RemoteGroupRef =
        RemoteGroupRef(RemoteEntityKey(EntityKind.GROUP, prefix + "group:$groupOpenId"))

    fun member(groupOpenId: String, memberOpenId: String): RemoteMemberRef =
        RemoteMemberRef(
            key = RemoteEntityKey(EntityKind.MEMBER, prefix + "member:$groupOpenId:$memberOpenId"),
            group = group(groupOpenId),
        )

    fun direct(userOpenId: String): RemoteDirectRef =
        RemoteDirectRef(
            key = RemoteEntityKey(EntityKind.USER, prefix + "direct:$userOpenId"),
            user = user(userOpenId),
            kind = DirectConversationKind.FRIEND,
        )

    fun groupConversation(groupOpenId: String): RemoteGroupConversationRef =
        RemoteGroupConversationRef(
            key = RemoteEntityKey(EntityKind.GROUP, prefix + "conversation:group:$groupOpenId"),
            group = group(groupOpenId),
        )

    fun message(scene: QqMessageScene, messageId: String): RemoteMessageRef =
        RemoteMessageRef(RemoteEntityKey(EntityKind.MESSAGE, prefix + "message:${scene.wireName}:$messageId"))

    fun self(displayName: String): RemoteSelf =
        RemoteSelf(
            ref = bot(),
            accountStableKey = prefix + "bot:$appId",
            displayName = displayName,
        )

    fun userEntity(userOpenId: String, displayName: String? = null): RemoteUser =
        RemoteUser(
            ref = user(userOpenId),
            displayName = displayName?.takeIf(String::isNotBlank) ?: userOpenId,
        )

    fun parseTarget(targetStableKey: String): QqSendTarget? {
        if (!targetStableKey.startsWith(prefix)) return null
        val suffix = targetStableKey.removePrefix(prefix)
        return when {
            suffix.startsWith("direct:") -> QqSendTarget.Direct(suffix.removePrefix("direct:"))
            suffix.startsWith("conversation:group:") ->
                QqSendTarget.Group(suffix.removePrefix("conversation:group:"))
            suffix.startsWith("group:") ->
                QqSendTarget.Group(suffix.removePrefix("group:"))
            else -> null
        }
    }

    fun parseMessageId(messageStableKey: String): String? {
        if (!messageStableKey.startsWith(prefix)) return null
        val suffix = messageStableKey.removePrefix(prefix)
        if (!suffix.startsWith("message:")) return null
        return suffix.substringAfterLast(':').takeIf(String::isNotBlank)
    }

    fun mediaResource(fileInfo: String, mimeType: String?, size: Long): ResourceRef {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(fileInfo.toByteArray(Charsets.UTF_8))
        return ResourceRef(prefix + "media:$encoded", "qq-media://$encoded", mimeType, size)
    }

    fun parseMediaFileInfo(resource: ResourceRef): String? {
        val encoded = resource.uri?.removePrefix("qq-media://") ?: return null
        if (encoded == resource.uri) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull()?.takeIf(String::isNotBlank)
    }
}

internal enum class QqMessageScene(val wireName: String) {
    C2C("c2c"),
    GROUP("group"),
}

internal sealed class QqSendTarget {
    abstract val openId: String

    data class Direct(override val openId: String) : QqSendTarget()
    data class Group(override val openId: String) : QqSendTarget()
}
