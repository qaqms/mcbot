# mcbot 0.1.0 测试包说明

内含两个 jar：
- `mcbot-0.1.0.jar` —— 本体（客户端+服务端同包，`environment:*`）
- `fabric-api-0.141.6+1.21.11.jar` —— 依赖（Fabric API）

## 本地端到端（推荐先走这条）

1. **起服务器**（已含 mcbot）：
   ```
   cd D:/ai/mcbot && ./gradlew runServer
   ```
2. **起客户端**（你自己的启动器）：Prism / HMCL / MultiMC 建 **Fabric 1.21.11** 实例，
   把上面两个 jar 放进该实例的 `mods/` 文件夹，正常登录启动。
3. 进服（多人游戏 → `localhost:25565`），然后：
   - 按 **G** 打开 mcbot 面板 → 填 base_url（如 `https://api.deepseek.com`）、
     model（如 `deepseek-chat`）、api_key → **保存并应用**；
   - 面板里输名字点 **召唤**（或聊天框 `/mcbot summon 名字`）；
   - 面板聊天框或游戏内 `@bot 看看附近有什么` → 等 `[同伴]` 蓝条回答；
   - 面板"查状态"按钮 = 让模型调 status 工具的快捷指令。
4. key 保存在**该客户端实例**的 `mcbot/client.json`（首次"保存并应用"自动生成）。

## 真实联机服部署

服务器侧装同一个 Fabric：`mods/` 放同样两个 jar 即可；玩家客户端**不装 mcbot 也能进服**，
但只有装了 mcbot + 自己填了 key 的玩家能召唤/指挥同伴（大脑在客户端）。

## 已知边界（本包 = M0–M4.6 + M6 桥 + M8 可挖寻路 + 闸①，构建于 09-08 11:43）

- **同伴仍无敌**（免死），战斗/死亡→复活链路未做。
- **工具共 9 个**：服务端 8（`status` `scan_area` `break_block` `collect` `place_block`
  `move_to` `transfer` `wait`）+ 本地 1（`ask_owner`）。
  **未做**：`craft`、`smelt`、`inspect_block`（随 M5）。
- **寻路**（M8）：可挖 A* 已可用，但**复杂山地的短距目标仍可能撞 8000 节点搜索帽**（真机实锤），
  会回 `BUDGET_EXCEEDED` 教学让它分短段或先靠近；不会自行换工具。
- **每主人一个同伴**（v1 约束）；桥**一台机一个**（端口固定 127.0.0.1:57121，多开会撞）。
- 假玩家的**加入/退出聊天消息未隐藏**（装饰性遗留）。
- LLM key 只存在**你客户端实例**的 `mcbot/client.json`，不上传、不入仓、服务器看不到模型。
- 面板 key 输入框不掩码（本版本 EditBox 无掩码 API），肩窥风险自担。
- `@bot` 前缀指令与面板输入共用同一大脑回路；断开服务器会重建大脑（对话清零）。
