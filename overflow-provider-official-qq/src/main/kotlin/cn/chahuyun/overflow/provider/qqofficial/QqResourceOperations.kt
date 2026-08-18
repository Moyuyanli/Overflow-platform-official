package cn.chahuyun.overflow.provider.qqofficial

import top.mrxiaom.overflow.bridge.RemoteConversationRef
import top.mrxiaom.overflow.bridge.ResourceKind
import top.mrxiaom.overflow.bridge.ResourceRef
import top.mrxiaom.overflow.operation.ResourceOperations

internal class QqResourceOperations(
    private val keys: QqStableKeys,
    private val api: cn.chahuyun.overflow.provider.qqofficial.api.QqOpenApiClient,
) : ResourceOperations {
    override suspend fun upload(bytes: ByteArray, name: String, mimeType: String?): ResourceRef =
        throw UnsupportedOperationException("official-qq resource upload requires a target conversation")

    override suspend fun upload(
        target: RemoteConversationRef,
        bytes: ByteArray,
        name: String,
        kind: ResourceKind,
        mimeType: String?,
    ): ResourceRef {
        val qqTarget = keys.parseTarget(target.key.stableKey)
            ?: throw UnsupportedOperationException("official-qq can only upload to its own C2C/group conversations")
        val fileInfo = api.uploadMedia(qqTarget, bytes, kind)
        return keys.mediaResource(fileInfo, mimeType, bytes.size.toLong())
    }

    override suspend fun download(resource: ResourceRef): ByteArray =
        throw UnsupportedOperationException("official-qq resource download is not implemented")
}
