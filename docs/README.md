# Overflow Gateway Provider Development Kit

这个目录可单独发送给第三方消息平台适配器开发者。目标是实现一个可放入
Overflow Gateway `adapters/` 目录的 Provider Fat JAR。

## 当前兼容性

- Gateway API 坐标：`cn.chahuyun:overflow-provider-api:0.1.0-gateway-SNAPSHOT`
- 仓库：`https://central.sonatype.com/repository/maven-snapshots/`
- Provider SPI：v1（Experimental）
- 编译环境：JDK 11 或 17；输出字节码必须为 Java 8。

SPI v1 尚未承诺跨版本二进制兼容。请固定开发和验收所用的 Gateway
snapshot 版本；升级前重新编译并完成下方验收清单。

## 交付包结构

```text
provider-development-kit/
  README.md              # 本文件：从创建到部署的步骤
  PROVIDER_API.md        # API、模型、生命周期与行为契约
  template/              # 可独立构建的 Kotlin Provider 起始工程
```

`template/` 只实现必需的身份能力。它不连接任何真实平台，开发者应替换
`ExamplePlatformProvider` 与 `ExampleSession`，并按实际平台增加 Operations。

## 快速开始

1. 复制 `template/` 到新的 Git 工程，例如 `my-platform-overflow-provider`。
2. 修改 `settings.gradle.kts` 的工程名，以及 `build.gradle.kts` 的 `group`、`version`。
3. 修改实现类的包名、Provider ID、平台类型和 SPI 服务文件中的完整类名。
4. 实现认证、消息/联系人/群组等所需 Operations；详细契约见
   [PROVIDER_API.md](PROVIDER_API.md)。
5. 模板不附带 Gradle Wrapper。使用已安装的 Gradle 执行 `gradle wrapper`，或将项目
   现有的 Gradle Wrapper 复制到模板根目录；然后运行：

   ```powershell
   .\gradlew.bat clean build shadowJar
   ```

   Gateway 源码仓库内的维护者可先发布到本地 staging，再验证本包模板：

   ```powershell
   .\gradlew.bat publishAllPublicationsToMavenStageRepository
   .\gradlew.bat -p docs/provider-development-kit/template clean build `
     -PoverflowRepository="file:///D:/path/to/Overflow-Gateway/build/maven-publishing-stage/"
   ```

6. 将 `build/libs/` 中生成的 JAR 放到 Gateway 工作目录的 `adapters/`。
7. 将 `template/overflow.example.json` 合并到 Gateway 的 `overflow.json`，并填入
   平台配置。
8. 正常启动或重启 Mirai Console。Provider JAR 不支持热替换。

## Provider 配置格式

Gateway 只解析外层字段，并将 `config` 中的 JSON 原样交给 Provider：

```json
{
  "providers": [{
    "enabled": true,
    "provider": "example",
    "instance": "main",
    "config": {
      "account": "example-account",
      "token": "put-secret-in-local-config-only"
    }
  }]
}
```

- `provider` 必须等于 `ProviderDescriptor.id`，格式为
  `[a-z][a-z0-9.-]{1,63}`。
- `instance` 在同一 Provider ID 内必须唯一；一个实例对应一个独立账号/连接。
- `enabled` 缺省为 `true`。
- Provider 必须在 `validateConfig` 中返回结构化错误，不能将配置错误推迟到
  不可诊断的连接异常。

不要提交真实 token、账号密码或私钥。对方只应收到示例配置。

## 打包要求

Provider 需要是 Fat JAR，包含平台 SDK 等私有运行依赖；但不得打入以下由
Gateway 统一提供的类：

- `overflow-provider-api`
- Kotlin 标准库
- `kotlinx-coroutines`
- `kotlinx-serialization`
- `slf4j-api`

必须存在以下 SPI 文件，内容为 Provider 实现类的完整名称：

```text
src/main/resources/META-INF/services/top.mrxiaom.overflow.provider.PlatformProvider
```

构建后检查：

```powershell
jar tf build/libs/<provider>.jar | Select-String "PlatformProvider.class|META-INF/services"
```

产物必须有实现类和 SPI 文件，但不得包含
`top/mrxiaom/overflow/provider/PlatformProvider.class`。若平台 SDK 与宿主有
同包名但不同版本的第三方依赖，应在 Shadow 任务中 relocation。

## 验收清单

- Provider 有公开无参构造函数，构造阶段不建立网络连接。
- `start()` 鉴权成功后返回 `RemoteSelf`，并转为 `ONLINE` 或 `RECONNECTING`。
- 启动期间收到的事件被有界队列缓存；不会因 Gateway 在 `start()` 返回后订阅而丢失。
- `stop()` 幂等，关闭 SDK 的 socket、线程池、文件等独立资源。
- StableKey 在进程重启和断线重连后不变；不使用昵称、随机值或列表下标。
- 每个声明的 Capability 都有对应的非空 Operations 实现。
- 至少验证登录、收一条消息、发一条消息、断线重连和正常关闭。
- 交付 JAR SHA-256、JDK/Gradle/Kotlin 版本、有效配置的脱敏副本和启动/关闭日志。

## 宿主侧要求

Gateway 运行包与这个 Provider 的 API 版本必须匹配。Gateway 加载外部 Provider 的
默认目录是 `adapters/`，可通过 JVM 参数 `-Doverflow.adapters=<path>` 修改。
配置文件默认是工作目录的 `overflow.json`，可通过 `-Doverflow.config=<path>` 修改。

Provider JAR 是受信任的进程内代码，不提供沙箱；只应加载审核过的 JAR。
