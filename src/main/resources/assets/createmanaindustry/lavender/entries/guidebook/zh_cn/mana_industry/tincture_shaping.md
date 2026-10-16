```json
{
  "title": "酊剂赋形",
  "icon": "hexcasting:battery",
  "ordinal": 14,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "category": "createmanaindustry:mana_industry"
}
```

一种[卓越戏法](^createmanaindustry:mana_industry/great_tricks)。依次接受一个实体片段和一个[药水片段](^createmanaindustry:mana_industry/potion_fragments)。成功施放消耗 **32 Anima**，返回传入的同一个实体片段。

将保存的完整药效以独立副本施于存活的生物。我也可以以自己为目标。

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:tincture_shaping|>

;;;;;

<|page-title@lavender:book_components|title=范围与真名|>

目标须能在当前维度中解析，且所在区块正在处理实体。范围采用咒术的实体施法范围，计入哨卫扩展与本施法链的灵质反转。

玩家真名的距离豁免也按 Hex Casting 配置生效：受豁免的玩家目标可以越过普通空间限制。其他目标仍须满足空间范围检查。

;;;;;

<|page-title@lavender:book_components|title=融入药效|>

已有的同种效果按原版等级、时长和显示规则合并，实体免疫与模组效果事件继续生效。隐藏后备效果的变化也算成功。

瞬间治疗、瞬间伤害等效果也按保存的时长加入状态，类似 `/effect`，可能在效果刻中重复作用；不会改成饮用药水的一次性触发。

;;;;;

<|page-title@lavender:book_components|title=以灵魂付费|>

Anima 不足时不会施加药效。目标免疫、施加被阻止，或完整药效未发生变化时，产生失误而不收费。成功时可以从多个电池共同供给。

灵质反转的媒质／魔力交换与费用翻倍不会改变这 32 Anima 的消耗。
