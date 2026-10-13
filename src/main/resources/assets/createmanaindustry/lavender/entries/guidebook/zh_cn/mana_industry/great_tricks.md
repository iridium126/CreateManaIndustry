```json
{
  "title": "卓越戏法",
  "icon": "trickster:mirror_of_evaluation",
  "ordinal": 9,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "additional_search_terms": [
    "卓越戏法",
    "great_trick"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

卓越戏法使用另一种点阵。主手持[执行手镜](^trickster:items/writing_casting/mirror_of_evaluation)，副手持咒法学法杖，再打开手镜，即可进入卓越戏法的绘制界面。圆内的点排成正六边形，笔迹为蓝色。

它们消耗[Anima](^createmanaindustry:mana_industry/anima)，且只能由玩家施放。

;;;;;

<|page-title@lavender:book_components|title=连接与撤回|>

可以直接连接远处的点；若途中经过其他点，会依次连上它们。同一条边不能重复使用，沿刚画的线返回则会撤销笔迹。

识别依据是连线本身，不依赖起点或方向。移除副手法杖后重新打开手镜，可回到普通戏法的绘制界面。

;;;;;

<|page-title@lavender:book_components|title=放进法术树|>

卓越戏法可与普通戏法组成同一棵法术树。卓越戏法界面保留普通节点的显示与执行；要重绘普通图案，应先回到普通界面。

执行已保存的卓越戏法不要求持续双持，但施法者仍须是玩家。当前可用的卓越戏法为[灵质反转](^createmanaindustry:mana_industry/ectoplasmic_inversion)。
