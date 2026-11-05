# 双指南针拆分实施记录

基线：`v1.2.8`，分支：`refactor/split-compasses`。

## 安装目标

最终交付 Explorer 1.3.0 与 Creature 1.0.0 两个独立顶层 jar，均内嵌同一个
`compass_runtime` 1.0.0 Forge 核心。核心只加载一次，不要求额外安装前置。
旧生物物品不迁移。原探险者物品 ID、历史统计和配置保留。

## 当前落地范围（尚非可安装版本）

- 建立 `runtime`、`explorer`、`creature` Gradle 子工程。
- runtime 是无产品依赖的 Java 17 契约层，暂未装配 Forge 模组入口。
- 新增不可变 `TaskKey` 与 `SearchParameters`；请求绑定产品、玩家、UUID、重载代次，采样额度使用 long。
- 新增拥有者线程约束的 `ForegroundTasks`：跨产品替换、迟到回调保护、正常完成、取消及重载失效。
- 新增 `VerificationGate`：按对象身份计数、FIFO、公平后台申请与幂等释放，默认装配时将使用四槽。
- explorer/creature 暂时仅有 base 插件，不会产出冒充产品的空 jar。
- 现有生产入口尚未改接新核心；根工程仍是原单包，不是拆分后的产品。

## 后续必须完成，才能发布

1. 抽离群系上下文、快照配置、唯一线程池与预算调度，分离结构计划缓存。
2. 拆分传送引擎和产品目标适配；原接口通过适配层迁移，保留所有保护规则。
3. 两产品接入核心任务生命周期，移除 SearchService/CreatureSearch/AdventureTravel 之间的硬编码调用。
4. 迁移物品注册、两个独立网络协议、基础目录、配置、981 图鉴资源、各自 Mixin。
5. 核心 Forge 入口及 JarJar 相同构建内嵌，检查运行时去重、类加载和缺产品行为。
6. 单装/同装的客户端、独立服务端及完整整合包副本串联验收。
7. 两产品 README、配置迁移、图鉴路径对照、体积和 SHA-256；验收前不标正式版。

## 已执行检查

命令（使用 Java 17、本机离线依赖缓存）：

```text
gradlew :runtime:test --offline
```

首批 9 项测试通过：跨产品替换、玩家隔离、重载代次、取消异常、线程约束、FIFO、
相等但身份不同的句柄、四槽上限、long 参数校验。
此结果只证明新契约层，不代表两个模组已能启动；尚未做游戏内拆分验收。

旧 settings.gradle 备份位于仓库外 `maintenance/split-backups/settings.gradle.before-split`。
本阶段不修改原实例、原存档、第三方 jar 和已交付的 1.2.8 文件。
