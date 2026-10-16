```json
{
  "title": "药水片段",
  "icon": "minecraft:potion",
  "ordinal": 13,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "trickster"
    ]
  },
  "category": "createmanaindustry:mana_industry"
}
```

药水片段保存一个完整的药效快照：效果类型、剩余时长、等级、环境效果与显示标志、隐藏的后备效果，以及 NeoForge 的治愈方式。其他模组注册的效果也可保存。

片段里的时间不会流逝。原实体的药效变化不会改变片段，每次施加时也会创建独立的效果实例。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:read_potion_effects|>

接受一个实体片段，返回药水片段组成的列表，按效果注册 ID 排序。每个活动效果对应一个片段，隐藏的后备效果留在所属片段内部。这项查询不消耗魔力、媒质或 Anima，也没有距离限制。

目标须能在当前维度中解析，且所在区块正在处理实体。没有药效的目标，包括非生物实体，返回空列表。

;;;;;

<|page-title@lavender:book_components|title=留存药效|>

复制、法术树和长时法术存档均保留完整快照，包括无限时长。片段显示效果名称、等级和剩余时间。

装有 Hex Casting 时，我可以用酊剂赋形将保存的药效施于生物，消耗 32 Anima。
