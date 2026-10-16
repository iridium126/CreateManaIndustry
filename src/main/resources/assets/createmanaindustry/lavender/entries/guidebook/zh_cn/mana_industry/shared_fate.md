```json
{
  "title": "同命",
  "icon": "hexcasting:battery",
  "ordinal": 12,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": ["hexcasting", "trickster"]
  },
  "additional_search_terms": ["Shared Fate", "卓越戏法", "Anima", "事故"],
  "category": "createmanaindustry:mana_industry"
}
```

一种[卓越戏法](^createmanaindustry:mana_industry/great_tricks)。接受一个实体片段，返回传入的同一个片段。每次成功绑定消耗 **32 Anima**。

将我的命运系于目标：我遭受咒术事故时，目标也将承受相同的事故。事故以目标自身的生命、物品等属性为准；使我损失一半生命的事故，同样会使目标损失其当前生命的一半。

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:shared_fate|>

;;;;;

<|page-title@lavender:book_components|title=系结命运|>

目标必须存在于我的维度内，且处于当前咒术空间施法范围之内。哨卫扩展和本施法链的[灵质反转](^createmanaindustry:mana_industry/ectoplasmic_inversion)均计入范围，玩家真名的距离豁免则不适用。

我不能绑定自己，不能在有效的施法链中重复绑定同一实体，也不能使绑定形成有向回路。失败不消耗 Anima。没有生命的实体也可成为目标，但它没有的属性不会受到影响。

;;;;;

<|page-title@lavender:book_components|title=沿丝线而行|>

我可以绑定多个实体。同维度的观察者能看见连接双方的浅蓝丝线。绑定在所属施法链结束，或目标离开范围、死亡、移除、跨越维度时解除。

目标重返范围也不会自动恢复绑定。长时法术保存其目标，实际恢复执行时重新检查；恢复有效绑定无需再次付费。

若只从执行手镜运行一次“同命”，法术返回后施法链便已结束，绑定也随之解除。要维持丝线，我须让这道法术延时或继续运行；经法杖执行戏法 iota 时，则可在尚未清空或重置的法杖会话中维持绑定。

;;;;;

<|page-title@lavender:book_components|title=命运的方向|>

事故由施法者传向目标。若甲绑定乙、乙绑定丙，甲的事故会传至乙和丙；乙的事故只传至丙，不会反向传回甲。因此乙也不能再绑定甲。

即使多条路径通向同一实体，每次原始事故仍只对它重放一次。致死的最后一次事故也会继续传出。接收者使用自己的施法状态，灵质反转不会随丝线传递。

