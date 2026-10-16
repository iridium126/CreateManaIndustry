```json
{
  "title": "显示链接与参数",
  "icon": "create:display_link",
  "ordinal": 5,
  "associated_items": [
    "create:display_link"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

显示链接可以把机器读数送进[法术组构台](^trickster:items/infrastructure/spell_construct)，作为法术的参数。

先将组构台选为显示目标，再把链接放在提供读数的方块上。选择来源内容后，指定要写入的参数位置。普通组构台最多接收八个参数。

;;;;;

<|page-title@lavender:book_components|title=读到的是文字|>

显示链接写入的是**字符串片段**。物品数量显示为“32”时，法术收到的也是字符串“32”，不是数片段；需要计算时，应先把文字解析为数。

每个写入位置可包含多行文字。未写入的位置保留原参数；为较后位置补齐的空位则为 void。

;;;;;

<|page-title@lavender:book_components|title=持续读取|>

读数更新后，正在运行的法术也会得到新的参数。因此，长时法术可以反复读取同一参数，根据库存或其他机器读数行动。

[模块式组构台](^trickster:items/infrastructure/modular_spell_construct)为四枚核心分别提供八个参数位置。设置链接时，要同时选对核心和参数。
