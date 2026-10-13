# 生物目录补充格式

Forge1.20.1数据包的pack_format为15。放置 `data/<你的命名空间>/creature_catalog/<名字>.json`，内容为数组：

```json
[
  {
    "entity": "minecraft:cow",
    "name": "牛",
    "english": "Cow",
    "aliases": ["奶牛"],
    "dimensions": ["minecraft:overworld"],
    "biomes": ["minecraft:plains"],
    "note": "来源：实际生成配置或人工核对。"
  }
]
```

entity须真实注册，玩家不能作为目标。重复ID整条覆盖，不合并字段。相同资源路径依数据包优先级覆盖；不同资源ID依字典序加载，后者覆盖。可覆盖内置 `data/explorerscompass/creature_catalog/user-book.json`，或采用字典序靠后的独立资源ID。

缺少群系填 `biomes: []`：仅群系默认时显示，不代表所有群系。缺少维度填 `dimensions: []`，运行时只从生效出生表或已明确群系的维度关系补充，仍无法确认则暂缓，不凭模组名猜。

name、english、aliases用于搜索，ID始终可搜。模型来自游戏实体，不填写图片；note是说明，不执行脚本。修改后重新加载数据并重开指南针。目录控制筛选和选区，搜索与传送必须有真实实体。

文字导入：

```powershell
python tools/catalog/import_catalog.py "图鉴.txt路径" "游戏实例路径" "输出目录"
```

翻译匹配不等于注册确认。歧义、数字变体和无法匹配的条目保留待补，后续须核对注册表和维度；交付包附本轮导入及运行时待补清单。
