# 第三方来源、署名与许可

## 发布 jar 内

1. **Explorer’s Compass**：ChaosTheDude / MattCzyr。上游 https://github.com/MattCzyr/ExplorersCompass ，分支 forge-1.20.1，提交 e7f31c400bca0ced222d3086524da35e9bcc50de，原版 1.4.0。物品、配方、资源、五种排序及费用/耐久基础来自该项目，统一版作了搜索、目录、界面、HUD、网络与迁移重构。主体采用 CC BY-NC-SA 4.0，全文见 LICENSE.md 和 jar 内 META-INF/licenses/ExplorersCompass-CC-BY-NC-SA-4.0.txt。
2. **TinyPinyin 2.0.3**：promeG 及贡献者，https://github.com/promeG/TinyPinyin 。Apache License 2.0。`assets/explorerscompass/pinyin.bin` 是由该版本单字字音生成的修改格式，使用音节字典＋UTF-16 索引；CompactPinyin 读取器由本项目编写。发布 jar 不包含 TinyPinyin 的 Java 实现、词典引擎或 Aho-Corasick。许可全文见 META-INF/licenses/TinyPinyin-Apache-2.0.txt，来源与转换说明见 NOTICE.txt。

维护者：**David**。本次整合、异步搜索、联合目录、拼音索引、传送及迁移实现为此前扩展功能的重构与延续，主体贡献按 CC BY-NC-SA 4.0 分享。作者栏保留 ChaosTheDude、David，不表示 ChaosTheDude 为本版本背书。

## 参考与可选依赖

- **MCMostWolf / ExplorersCompassEdited**：https://github.com/MCMostWolf/ExplorersCompassEdited ，此前界面及兼容行为的参考。没有将 Edited jar 或其反编译目录导入统一版。
- **xinian / Explorer’s Compass Enhance**：此前异步搜索设计参考。
- **TeamTwilight / Twilight Forest**：https://github.com/TeamTwilight/twilightforest/tree/1.20.1 。适配器在安装时调用该模组的官方地标中心和放置 API；未嵌入其类或资源。测试版本 twilightforest-1.20.1-4.3.2508-universal。暮色森林仍为可选模组，遵循其自身许可，不重新许可它。

- **Modding Legacy / Dungeons Plus 与 Structure Gel API**：可选兼容对象和 API 行为对照来源，https://gitlab.com/modding-legacy/dungeons-plus 和 https://gitlab.com/modding-legacy/structure-gel-api 。核对本地版本 Dungeons Plus 1.5.0、Structure Gel 2.16.2；对照源码分支为 1.20.1，对照提交分别为 `86bd756cfc747b7fc7e292b0880ba85edabb8d95`、`dcd8de2d668f4c2f3467cf34456ee9ccc3f53004`。这些提交用于对照，不宣称本地 jar 必定由相同提交构建。适配器读取运行时注册规则，调用现有公开候选 API；发布包不嵌入其 Java 实现、模板或其他资源。两模组保持自身许可与作者署名，不重新许可第三方模组。

- **Stalwart Dungeons / Furti**：可选适配对象，作者介绍 <https://www.curseforge.com/minecraft/mc-mods/stalwart-dungeons>。本地 1.20.1-1.2.8 行为对照；本工程仅复现已审核的随机候选规则并读取运行时注册设置与几何证据，不嵌入其类、完整模板、贴图或 jar。第三方模组保留自身许可，交付包不重新分发它。

## 由运行环境提供

Minecraft、Forge、Mixin、Gson、Guava、Log4j 等由 Minecraft/Forge 运行环境提供，不在此 jar 重打包。保留必要 Minecraft 区块读取与模板快照防护 Mixin，不使用针对指南针自身类的 Mixin。

## 仅源码开发工具

Gradle Wrapper 随源码提供，构建工具许可 Apache-2.0；JUnit Jupiter 5.11.0 / Platform 1.11.0 通过 Maven 引入，仅用于测试，主体 EPL-2.0。TinyPinyin 完整库仅为生成与测试依赖。验收代码在 src/smoke，比较工具在 src/benchmarkHarness，JMH 测试在 src/benchmark；均不打入发布 jar。OpenJDK JMH 1.37 仅用于开发测试，遵循 GPL-2.0-with-Classpath-Exception，参见其官方仓库许可。

1.1.0 的距离下界和增量最近邻理论依据为 Hjaltason / Samet（1999）及 Roussopoulos / Kelley / Vincent（1995），链接和本项目推导见 ALGORITHM.md。

本项目允许按主体 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode.en) 非商业分享与修改，保留署名、来源、修改说明与相同许可。Apache-2.0 数据单独适用其许可。没有要求额外许可才能执行该许可已经允许的分享；此次主动提供完整源码。


## 1.1.1 新增可选兼容来源

- **AlexModGuy / Alex Caves 2.0.2**：[官方源码](https://github.com/AlexModGuy/AlexsCaves/tree/94eeb0810c72eb6c06a7e308955ebda6da9c6f75)。使用运行时官方 Voronoi API，定位器根据其生成条件建立快照，并仅保护统一版定位六洞穴群系的物品限制钩子。不分发 Alex 类、资源或 jar，第三方保持自身许可。
- **YUNG-GANG / YUNG’s API 4.0.5、Better Jungle Temples 2.0.5、Better Ocean Monuments 3.0.4**：[API](https://github.com/YUNG-GANG/YUNGs-API)、[丛林神庙](https://github.com/YUNG-GANG/YUNGs-Better-Jungle-Temples)、[海底神殿](https://github.com/YUNG-GANG/YUNGs-Better-Ocean-Monuments)。审核放置与拼接起点接口；不复制完整结构生成或模板资源。
- **ObscuriaLithium / Aquamirae 6.API15**：[官方项目](https://github.com/ObscuriaLithium/Aquamirae)。运行时读取允许群系和起始池，采用本项目列预筛减少无效地形预测。未嵌入其 Java 类和资源。

## Xaero 客户端可选联动（1.1.6）

适配 Xaero’s World Map 1.39.2 与 Xaero’s Minimap 25.0.0，作者 xaero96。通过本地已安装模组的公开方法创建及保存路径点；不复制、修改或打包第三方类、资源与文件格式实现。第三方模组遵循其自身许可，不属于本项目CC BY-NC-SA授权内容。官方说明：https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map 。

## 1.2.0生物目录及地图绘制

- 生物格网设计参考Šidlauskas等2009《Trees or Grids? Indexing Moving Objects in Main Memory》，链接见ALGORITHM.md；没有分发论文或第三方算法源码。
- 用户提供图鉴文字为目录输入，运行时调用现有实体翻译与模型，不复制FoolBook代码、贴图或类。目录不能证明实际刷怪，仍进行注册与实体核对。
- Xaero WorldMap1.39.2 / Minimap25.0.0仅作可选客户端接口，固定编译期Maven坐标分别为maven.modrinth:NcUtCpym:3Wmpi0tR及maven.modrinth:1bokaNcj:c3BiB41R，不打包其类或资源。地图模组保留各自许可。


## 1.2.3 补充

- Alex’s Caves2.0.2：只在指南针搜索作用域重定向官方Voronoi对象读取，完整官方查询仍执行，不复制公式或第三方类。源码/本地字节码核对与四线程一致性记录随交付提供。
- TerraBlender3.0.1.10：核对本地Area的StampedLock保护、分层随机上下文和初始化后的只读参数树；保留3.0.1.7支持，其他版本仍兼容路径。[官方参数树](https://github.com/Glitchfiend/TerraBlender/blob/1.20.1/Common/src/main/java/terrablender/mixin/MixinParameterList.java)。
- Apache Commons Compress：Apache-2.0，由Minecraft/Forge运行库提供，构建及加载大目录时调用BZip2接口，不打包其类；源码图鉴是标准JSON，发布资源无损解码后逐字节相同。
- Chunky仅作为预生成行为与边界的研究参考，没有引入其源码或依赖。预探索的有限任务、进度界面、预算及索引复用在本工程实现。
