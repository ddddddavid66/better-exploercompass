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

## 1.2.1 生物目录来源

本轮由 gpt-6-luna（max）在独立研究目录核对，主线程审核后生成发布资源。底册为愚者隔离实例的运行时 EntityType、模组版本、维度 possibleBiomes 和生效出生表；快照 SHA-256：`16ecd013042efcfebd4db755e66243df22965ddba8bfc4f7ed4a1f844a312342`。运行时创建未加入世界的实体实例仅用于类层级分类，不召唤、不 tick。

1723 注册类型完整分类为981生物及742非生物。自然规则核实只证明出生表与维度来源的关系，不保证实际刷新；64条非自然入口通过当前安装jar的类/方法取证，未知维度不填。原图鉴、翻译键、百科和官方链接作为线索，不能替代当前版本ID与实际覆盖规则。657类仍没有已核实出现方式，另124行旧文本未匹配，均随交付记录保留。

生产资源保留配对关系、描述和短来源编号，完整证据不重复塞进jar。`tools/catalog/prepare_catalog.py` 生成确定性短编号以及独立 `production-source-index.json`，该映射连同完整 `evidence-index.json`、底册、差分和未决清单随1.2.1研究交付包提供；原始取证不进入公开Git历史。第三方模组、愚者之书和Xaero的类或图片没有打包进发布jar。


## 1.2.3 新世界搜索与预探索

本轮修复本工程的单tick单采样和64区块提前终止问题，适生集合查询、预探索服务、客户端面板与定向检查均为本工程新增实现。已有全类型保存实体索引继续复用，未另开实体区域文件或复制预生成模组代码。

Alex适配仅在搜索线程绑定官方Voronoi实例，通过可选Mixin重定向官方字段读取；保留官方群系注入、条件与来源审核。当前支持2.0.2，关闭作用域后恢复原共享对象。TerraBlender实际3.0.1.10经匹配字节码核对，不仅因接口同名就放行。768点串行/并行一致性记录与原始日志留在仓库外验证材料。

981条目录没有重新合并外部研究数据。发布构建使用运行环境已有Commons Compress无损压缩资源，标准JSON源码与解码内容SHA-256一致；原文件路径保持不变，数据包普通JSON覆盖仍有效。
