package cn.chahuyun.overflow.provider.qqofficial

import cn.chahuyun.overflow.provider.qqofficial.api.QqOpenApiClient
import kotlinx.coroutines.flow.StateFlow
import top.mrxiaom.overflow.bridge.BridgeMessage
import top.mrxiaom.overflow.bridge.BridgeMessageElement
import top.mrxiaom.overflow.bridge.RemoteConversationRef
import top.mrxiaom.overflow.bridge.RemoteMessageReceipt
import top.mrxiaom.overflow.bridge.RemoteMessageRef
import top.mrxiaom.overflow.operation.MessageOperations
import top.mrxiaom.overflow.provider.SessionState
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicInteger

internal class QqMessageOperations(
    private val state: StateFlow<SessionState>,
    private val keys: QqStableKeys,
    private val api: QqOpenApiClient,
    private val logger: Logger,
) : MessageOperations {
    private val msgSeq = AtomicInteger(1)

    override suspend fun send(
        target: RemoteConversationRef,
        message: BridgeMessage,
    ): RemoteMessageReceipt {
        ensureOnline()
        val qqTarget = keys.parseTarget(target.key.stableKey)
            ?: throw UnsupportedOperationException("official-qq can only send to its own C2C/group conversations")
        val targetType = qqTarget.javaClass.simpleName
        val elementTypes = message.elements.joinToString(",") { it.javaClass.simpleName }
        val payload = try {
            toQqText(message)
        } catch (error: Throwable) {
            logger.warn("QQ send payload encoding failed elementTypes={}", elementTypes, error)
            throw error
        }
        val result = try {
            api.sendMessage(
                target = qqTarget,
                content = payload.content,
                passiveMessageId = payload.replyMessageId,
                msgSeq = msgSeq.getAndUpdate { current ->
                    if (current == Int.MAX_VALUE) 1 else current + 1
                },
                imageFileInfo = payload.imageFileInfo,
            )
        } catch (error: Throwable) {
            logger.warn(
                "QQ send failed targetType={} elementTypes={} passiveReply={}",
                targetType,
                elementTypes,
                payload.replyMessageId != null,
                error,
            )
            throw error
        }
        return RemoteMessageReceipt(
            message = keys.message(result.scene, result.messageId),
            sentAtEpochSeconds = result.timestampEpochSeconds,
        )
    }

    override suspend fun recall(target: RemoteConversationRef, message: RemoteMessageRef) {
        throw UnsupportedOperationException("official-qq message.recall has not been audited for this Provider build")
    }

    private fun ensureOnline() {
        if (state.value != SessionState.ONLINE) {
            throw IllegalStateException("official-qq session is ${state.value}; message send requires ONLINE")
        }
    }

    private fun toQqText(message: BridgeMessage): QqTextPayload {
        var replyMessageId: String? = null
        var imageFileInfo: String? = null
        val text = StringBuilder()
        for (element in message.elements) {
            when (element) {
                is BridgeMessageElement.Text -> text.append(element.text)
                is BridgeMessageElement.Image -> {
                    check(imageFileInfo == null) { "official-qq supports one image per message" }
                    imageFileInfo = keys.parseMediaFileInfo(element.resource)
                        ?: throw UnsupportedOperationException("official-qq image must be uploaded by this session")
                }
                is BridgeMessageElement.Reply -> {
                    replyMessageId = keys.parseMessageId(element.target.key.stableKey)
                        ?: throw UnsupportedOperationException("official-qq cannot reply to a non-QQ message")
                }
                else -> throw UnsupportedOperationException(
                    "official-qq currently supports text, image and Reply; unsupported element ${element.javaClass.simpleName}",
                )
            }
        }
        val content = text.toString()
        require(content.isNotBlank() || imageFileInfo != null) { "official-qq message must contain text or an image" }
        return QqTextPayload(content, replyMessageId, imageFileInfo)
    }
}

private data class QqTextPayload(
    val content: String,
    val replyMessageId: String?,
    val imageFileInfo: String?,
)
