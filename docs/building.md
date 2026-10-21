# Gradle 构建与维护

Java 21、Gradle Wrapper 9.2.1。从项目根目录运行，正常构建不启动Minecraft客户端。

```powershell
.\gradlew.bat help --offline
.\gradlew.bat test check build --offline
python scripts/testing/validate_gradle.py
```

`--offline`要求依赖、命名重映射及Voxy缓存已准备好。首次准备依赖使用联网构建；不要把缓存缺失当作可以跳过的编译输入。配置缓存默认启用；使用不兼容的独立验证init脚本时按该工具说明加 `--no-configuration-cache`。

## 职责与依赖顺序

| 文件 | 职责 |
| --- | --- |
| `build.gradle` | 插件、Java与资源规则、NeoForge运行配置、元数据和发布 |
| `settings.gradle`／`gradle/remapped-libs.gradle` | 稳定项目名、完整命名缓存检查、决定是否加载Loom子项目 |
| `gradle/repositories.gradle` | 外部仓库、特殊发布源与解析顺序 |
| `gradle/dependencies.gradle` | 唯一外部坐标表、依赖范围、主／测试类路径连接 |
| `gradle/optional-apis.gradle` | 共享惰性嵌套API提取任务、精确单匹配及输出依赖 |
| `gradle/voxy.gradle` | 按需获取、准备和构建Voxy，正常命中缓存不执行源码构建 |
| `gradle/packaging.gradle` | bootstrap服务层、内嵌mod及发行JAR、对外产物 |
| `gradle/verification.gradle` | JVM测试、Sable缺席探针、Create二进制审计 |
| `loom-remap/build.gradle` | 缓存缺失时导出Mojang命名的Trickster与Fabric API |

先定义bootstrap source set，配置NeoForge运行环境后再加载依赖：`additionalRuntimeClasspath`由NeoForge运行配置创建。不要在配置阶段读取配置的 `.files`／`.singleFile`；把解析留给任务输入或Provider。任务需要API文件时消费提取任务的输出Provider，让Gradle推导生产者依赖。

## 依赖范围

- `implementation`：生产实现依赖；不等于把第三方JAR嵌入模组。
- `compileOnly`：外部提供的API及可选适配。Veil、Hexcasting、Ars Nouveau、Bits 'n' Bobs及JEI同时加入 `localRuntime`，供开发环境使用，不作为Maven消费者的强制传递依赖。
- `localRuntime`：仅开发／验证运行环境需要的模组；不进入发布依赖。
- `testImplementation`／`testRuntimeOnly`：JUnit、参考实现、字节码和API验证所需项。
- `additionalRuntimeClasspath`：Connector与bootstrap的早期加载路径，不能随意合并到普通模组类路径。

第三方坐标只在 `cmiCoordinates` 表中声明一次，共享版本继续在 `gradle.properties` 维护；Loom子项目复用同一张表。更改范围时检查编译、开发运行和测试文件集合，以及发布POM／module metadata，不以构建能通过作为唯一依据。

## 缓存与重映射

| 位置 | 用途 |
| --- | --- |
| `.gradle/cmi/optional-apis/` | Sodium、Sable、CCA、Endec嵌套API；正常clean后保留，分发变更使任务输入失效并刷新 |
| `.gradle/voxy/` | 原有Voxy源码与成品缓存；保留已有目录避免重复克隆／构建 |
| `loom-remap/remapped-libs/` | 按版本命名的Trickster与Fabric API导出 |
| `build/generated/resources/modMetadata/` | 生成的模组元数据 |
| `build/internal/` | bootstrap开发JAR与内部mod载荷 |
| `build/libs/` | 可安装的发行JAR |

settings只计算一次remap选择：两份命名JAR均为文件且没有 `-PregenerateRemap` 时复用；任一缺失或显式重建都加载子项目。`-PregenerateRemap=false`仍表示显式提供了该开关，与原有存在性语义一致。Voxy显式重建使用 `-Pvoxy_rebuild=true`，离线模式不会偷偷联网克隆，嵌套Gradle调用也继承 `--offline`。

提取任务必须恰好匹配一个嵌套API。零匹配或多匹配失败时保留上次完整输出；正常写入先暂存再替换。任务缓存可复用，旧版本的四处散落API输出不再读取。

## 验证与发行

`scripts/testing/validate_gradle.py`只在忽略的 `build/gradle-validation/` 中创建小型离线项目，不修改真实缓存。覆盖双缓存缺失矩阵、显式重建、无关help不解析依赖、提取内容、配置缓存复用、输入刷新、零／多匹配保留旧输出，以及Voxy离线缓存命中／缺失。完整业务回归及GameTest入口见[测试说明](testing.md)。

发行坐标固定为 `mod_group_id:mod_id:mod_version`，不随工作目录名改变。bootstrap服务与mod载荷必须分层，`distributionJar`包含 `META-INF/jars/createmanaindustry.jar`；`apiElements`、`runtimeElements`和Maven发布应指向同一发行产物。

检查内外层JAR的清单、服务文件、模组元数据和模板；排除验证证书、datagen缓存、离线控制点及测试夹具。真实客户端启动、画面和光影包验收由用户执行，离线构建与任务dry-run不代替这些项目。
