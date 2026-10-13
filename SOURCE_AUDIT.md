# 来源审核与修改边界

维护者 David；主体许可 CC BY-NC-SA 4.0，TinyPinyin 派生数据适用 Apache-2.0。本文件说明实现来源，不构成法律上的独创性或无侵权保证。

| 模块 | 来源与处理 |
|---|---|
| 物品、资源、配方、排序、费用与耐久 | Explorer’s Compass Forge 1.20.1 1.4.0，固定提交见 UPSTREAM.json；保留署名和许可 |
| 历史异步设计 | 曾参考 xinian 的 Enhance；明确保留历史来源说明 |
| 当前遍历、距离证明、额度与调度 | 1.1.0 自行实现矩形距离下界二分，替换相近圆环条带实现；数学依据、边界和测试见 ALGORITHM.md |
| 暮色森林 | 可选适配，调用已安装模组的地标中心及放置接口，不嵌入其类和资源 |
| Dungeons Plus / Structure Gel | 可选接口和生成入口审核，官方网格 API 计算候选，真实记录独立核对 |
| Stalwart Dungeons | 已审核地物随机规则的定位适配，读取运行时小型模板证据，不放置或主动展开地牢 |
| YUNG 1.1.1 适配 | 审核 2.0.5 丛林放置规则及 YUNG’s API 4.0.5 的起始拼接行为；调用官方生成入口，主线程准备只读模板锚点快照 |
| Aquamirae 1.1.1 预筛 | 自行实现准确起始列的全 quart-Y 允许群系检查；气候列缓存经密度函数证明及逐点原版对照，未复制第三方遍历算法 |
| Alex Caves 1.1.1 适配 | 根据官方 2.0.2 生成条件实现定位快照，调用已安装模组的 Voronoi 算法；分片独占可变对象，官方规则细节明确保留 |
| 统一传送、筛选、统计、日志 | 本工程及此前配套扩展功能的整合与后续修复；Minecraft/Forge 提供运行 API |

1.1.1 不包含 Alex Caves、YUNG、Aquamirae、Dungeons Plus、暮色森林或 Enhance 的 Java 类、模板、贴图和 jar。依赖仅供运行时可选兼容及隔离测试；第三方各自许可继续适用。生成规则匹配不能通过随意更改常量实现，适配器保留必要规则，未知版本或能力进入明确标注的兼容路径。

## 本轮对照来源

- [Alex Caves 官方 2.0.2 源码](https://github.com/AlexModGuy/AlexsCaves/tree/94eeb0810c72eb6c06a7e308955ebda6da9c6f75)：ACBiomeRarity、BiomeGenerationNoiseCondition、VoronoiGenerator 和 CommonEvents。
- [YUNG 丛林神庙放置规则](https://github.com/YUNG-GANG/YUNGs-Better-Jungle-Temples/blob/1.20/Common/src/main/java/com/yungnickyoung/minecraft/betterjungletemples/world/placement/BetterJungleTemplePlacement.java)。
- [YUNG’s API](https://github.com/YUNG-GANG/YUNGs-API/tree/1.20)：YungJigsawStructure、JigsawManager，结合实际 4.0.5 jar 对照。
- [Aquamirae](https://github.com/ObscuriaLithium/Aquamirae)：6.API15 实际结构及起始池定义；发布包不复制模板资源。

这些来源用于说明规则与接口依据，不能将分支 HEAD 等同于实际 jar 构建提交。实际验证版本和 SHA-256 随交付记录提供。

## 1.2.0新增实现

生物目录、UUID索引、有限邻区查询、卡片UI与地图蓝点为本工程实现。论文只用于数据结构和更新设计，不复制其他生物指南针代码；FoolBook仅用于核对注册表、现有翻译和游戏模型调用，不复制其界面实现或图片。图鉴文字是用户提供的目录输入，翻译键仍需注册表验证，未知维度保持暂缓。

Xaero适配按已安装WorldMap1.39.2与Minimap25.0.0公开扩展接口编写，构建只依赖固定版本编译接口；不分发第三方类或资源。Maven坐标与接口版本固定，未将反编译目录导入Git。

格网设计来源为Šidlauskas等2009论文，见ALGORITHM.md；移动对象位置不是世界生成预测，真实UUID及群系须主线程复核。矩形环带继续使用本工程1.1.0核心，仅新增完整内圈排除证明。
