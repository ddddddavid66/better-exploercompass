# 生物目录 v2（统一版 1.2.1）

数据包格式：Minecraft 1.20.1 的 `pack_format: 15`；资源位于 `data/<命名空间>/creature_catalog/<文件>.json`，顶层为数组。完整约束见 `tools/catalog/catalog-v2.schema.json`。旧七字段目录仍可读取，但其独立维度/群系集合不代表核实过的逐维度配对。

每条 v2 记录含 `schema_version: 2`、`entity`、`name`、`english`、`aliases`、`dimensions`、`biomes`、`note`、`appearances`。entity 必须是真实注册ID，名称和别名支持搜索；拼音由模组计算，模型来自游戏，不填写图片。

```json
[
  {
    "schema_version": 2,
    "entity": "aether:aechor_plant",
    "name": "箭毒花",
    "english": "Aechor Plant",
    "aliases": [],
    "dimensions": ["aether:the_aether"],
    "biomes": ["aether:skyroot_forest"],
    "note": "示例只列一个已核对的群系，不表示完整范围。",
    "appearances": [
      {
        "kind": "natural_spawn",
        "dimension": "aether:the_aether",
        "biomes": ["aether:skyroot_forest"],
        "description": "自然生成，实际出现还受地形与刷怪条件影响。",
        "verification": "verified",
        "sources": ["aether-1.4.2-skyroot-spawn-table"]
      }
    ]
  }
]
```

每条 appearance 仅对应一个维度、一种出现方式。kind支持 `natural_spawn/structure/spawner/summoned/conversion/breeding/event/unknown`；核实状态为 `verified/partial/unverified`。未知维度使用 `null`，未知群系使用 `[]`，不表示任意维度或群系。unverified只能保留未知范围，不把猜测交给筛选器。verified须有来源，来源索引独立保存；发布生成文件可移除来源全文及编号。

新版筛选必须在同一条有效 appearance 内匹配维度与群系，顶层 dimensions/biomes只是兼容镜像。缺少可靠维度暂缓启用；未知群系只在群系默认时显示。运行时只用实际生效出生表补缺失维度，不根据模组名推断。表中可参与生成不保证搜索范围内一定有实体；搜索和传送仍须复核真实UUID。

重复ID整条覆盖、不合并字段。同资源路径遵循数据包优先级；不同资源ID按字典序加载，后者覆盖。默认目录保持 `explorerscompass:creature_catalog/user-book.json` 路径以兼容既有覆盖。更改后重新加载数据并重开指南针。

研究以完整注册表为底册，包含Boss/NPC/召唤物，排除玩家、展示、投射物、载具、掉落物及部件；旧图鉴和翻译键仅作为线索。注册分类、生成规则核实和实际搜索结果分别统计。

```powershell
python tools/catalog/validate_catalog.py collected-v2.json --registry registry-snapshot.json --evidence evidence-index.json --audit registry-audit.json
```

原图鉴名称导入工具仍保留 `tools/catalog/import_catalog.py`，其输出只是翻译候选，不是完整注册表或已核实生成证明。
