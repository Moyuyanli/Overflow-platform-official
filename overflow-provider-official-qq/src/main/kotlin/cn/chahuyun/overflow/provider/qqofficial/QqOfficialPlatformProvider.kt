package cn.chahuyun.overflow.provider.qqofficial

import kotlinx.serialization.json.JsonObject
import top.mrxiaom.overflow.provider.ConfigValidation
import top.mrxiaom.overflow.provider.PlatformProvider
import top.mrxiaom.overflow.provider.PlatformSession
import top.mrxiaom.overflow.provider.ProviderContext
import top.mrxiaom.overflow.provider.ProviderDescriptor
import top.mrxiaom.overflow.provider.ProviderInstanceConfig
import top.mrxiaom.overflow.provider.ProviderSpi

public class QqOfficialPlatformProvider : PlatformProvider {
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = PROVIDER_ID,
        displayName = "QQ Official",
        version = "0.1.0-SNAPSHOT",
        spiVersion = ProviderSpi.CURRENT,
        vendor = "Chahuyun",
    )

    override suspend fun validateConfig(config: JsonObject): ConfigValidation =
        QqOfficialConfig.validate(config)

    override suspend fun createSession(
        config: ProviderInstanceConfig,
        context: ProviderContext,
    ): PlatformSession = QqOfficialSession(
        instanceId = config.instanceId,
        config = QqOfficialConfig.parse(config.data),
        context = context,
    )

    private companion object {
        const val PROVIDER_ID = "official-qq"
    }
}
