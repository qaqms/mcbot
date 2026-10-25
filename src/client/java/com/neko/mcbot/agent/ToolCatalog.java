package com.neko.mcbot.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import com.neko.mcbot.agentcore.prompt.SkillCatalog;
import com.neko.mcbot.agentcore.script.BoundedProgram;

import java.util.List;

/** Full schemas stay on the wire; extended instructions come from the same registry on demand. */
public final class ToolCatalog {
    private ToolCatalog() { }

    public static final List<ToolSpec> MODEL_SPECS = ClientToolDefs.SPECS.stream().map(spec ->
            new ToolSpec(spec.name(), summary(spec), spec.paramsJsonSchema())).toList();

    private static String summary(ToolSpec spec) {
        if (spec.name().equals("run_program"))
            return "用Lua变量、条件和有界循环组合依赖步骤，mc.call(name,args)返回ok/feedback/data，失败即停。"
                    + BoundedProgram.LANGUAGE_GUIDE
                    + "结果字段不清楚先tool_help。成功查询留本地，所需事实return/print；帮助始终展示，负预检保留原因。"
                    + "mc.try_call只允许读取局部移动/工作失败；继续动作前必须mc.call('status',{details=true})核对新鲜身体和背包。"
                    + "不能捕获权限/材料/工具/安全/未知失败，不自动重试。最终目标先set_goal，材料gather用scope=subtask；"
                    + "set_goal/report_blocked/ask_owner只能外层调用；不能嵌套程序。默认不限总时长/API和嵌套原子调用次数，"
                    + "无需填写max_calls/timeout_seconds，重复或空搜索交由程序判断。错误按program_error.kind/line核对，已完成动作勿重放。";
        if (spec.name().equals("tool_help")) return spec.description();
        String text = switch (spec.name()) {
            case "consume" -> "按inventory的实际slot原生食用一次，最多100用物刻/120总刻。"
                    + "ACCEPTED等真终态，consumed_count核真实消耗；抢占/取消不重吃，原目标另验。";
            case "equip" -> "按实际slot切主手；target省略/mainhand，或head/chest/legs/feet补空盔甲槽。"
                    + "不卸装或升级、不切副手；原版槽资格与真实库存共用。";
            case "build_blueprint" -> "只读查看世界内有界原版结构.nbt，按source_revision显式完整导入B4设计；"
                    + "不支持格/载荷阻止导入，实例与施工仍走原建筑入口，无直接世界写入或预算重开。";
            case "build_record" -> "保存/分页修改可复用设计，显式创建或修订隔离实例；target引用复用原差异/工地/施工/Lua。"
                    + "逐真实动作落盘，未知来源/外部修改保守冲突；不自动续已取消目标，不补预算，参数先tool_help。";
            case "explore" -> "有界换区观察；surface只行走，underground按direction/target_y/targets开阶梯，return按地下路标返程。"
                    + "默认不限总半径和移动次数，显式max_steps/radius保留，与gather共享已查点；发现不等于入包，取消不返程。";
            case "gather" -> "连续扫描、工作导航、挖掘、触碰拾取并核对实际背包。item是入包物品，targets是来源方块；挖掘从背包自动选工具。"
                    + "批量木料先按真实配方准备斧，石矿选合适镐；自动换手不自动制作或升级。"
                    + "材料阶段scope=subtask默认mode=total复用已有材料，要求再收集才用additional；默认task登记最终目标。"
                    + "任意木材等可替换材料用material_for查询真实配方来源，勿固定oak_log再挖spruce_log；不自动转换。"
                    + "完整实际掉落不匹配时SOURCE_MISMATCH交回调整；explore_steps=0只在当前工区采集，原探索账不重置。";
            case "set_goal" -> "固定最终条件：type省略/item验背包数量，building验target/expected_revision全目标，"
                    + "单独operation=query取原登记条件；已登记建筑直接run_program执行return mc.build()，mc.goal()取原图，不重拼。"
                    + "delivery验container/item/count，additional须登记后本任务净存入及同容器净增量，transfer须item过滤。"
                    + "多阶段首次lifecycle=long_term，默认不限模型轮数和阶段数；结束前新鲜核验，持续跟随结束不替代目标完成。";
            case "workflow" -> "串行执行已知参数的1-12步，整份先验证；失败或放置负预检即停，保留已完成效果。"
                    + "不支持移动/反问/嵌套，制作及装取数量须明确；依赖结果用run_program。";
            case "search_resource" -> "只读完整资源搜索，targets须1-8个方块ID/#标签，r默认/最多16（1-16）；"
                    + "本地最多5页/30秒；work=true评估工作站位与采收/干燥安全。"
                    + "候选不证明可达或入包，空结果不证明未知区域没有资源。";
            case "find_resource" -> "单页资源查询，一般用search_resource；续页按next_offset及原scan_center，移动后从0重查。"
                    + "work=true时核对harvestable_with_inventory和mining_tool（工具/单格耗时/备料建议）及dry_work_safe。";
            case "craft" -> "按真实配方合成，count为至少成品数，可能整批多产；query=true只查询，须看can_craft，"
                    + "实际成果看crafted_count和背包。material_index查询替代物品及processes；不自动补料，3×3需附近工作台。";
            case "smelt" -> "近身熔炉query只读，load按实际背包槽装料，take取出；装料不是烧制完成，wait后query/take。"
                    + "取消任务不熄炉，停产须取出原料。";
            case "break_block" -> "挖一格，按方块从真实背包选有采收资格的最快工具，同速优先不磨损；按实际进度消耗耐久。"
                    + "掉落留地，走近后核对背包。"
                    + "expected_block保护观测目标，clear_obstructions按具体安全清单且须原权限，默认无格数/总时长上限；ACCEPTED等真终态，不重发。";
            case "place_block" -> "放置到准确目标格，须真实命中可替换轮廓或邻接面；face默认up。"
                    + "query只读，须看preflight_passed；执行重验，失败核对实际消耗/变化，不自动重试。";
            case "build_nearby" -> "按build_diff同一target/expected_revision，默认查全目标并施工手边可做格；显式offset才限定16格页，max_actions默认/最多12；"
                    + "逐格共用原版放置及真实扣料，失败不重放，取消停手；不移动/挖掘/找料。"
                    + "ACCEPTED等终态，局部批次不代表全目标满足；Lua按max_actions预扣原子预算，详情tool_help。";
            case "build_site" -> "任务内登记完整目标，进出通道和范围可选，共用施工保护及累计账。"
                    + "多站位用run_program的mc.build(target)，无需手写通道；超出当前手距就换站位，草花和目标清障按原权限自动处理。"
                    + "身体挡格或出口受阻时按工地建议换位、临时开路或安全下落，之后补回原图并尝试回收临时工程；"
                    + "query只读，登记不授予许可，不自动找料，不保证全局返程。";
            case "build_diff" -> "只读建筑格集或fill/regions差异与材料，最多16384格；大图用紧凑区域或实例引用，锚点/旋转/属性固定。"
                    + "Lua的mc.build_diff(target)给全目标统计及有限明细；按next_offset与原修订续页，施工用mc.build。"
                    + "clearing_work给整图已观察清障量及工具准备建议，成片清土按真实配方先备铲。"
                    + "门与床保留两格验收，仅主格动作/一件料；支持空单箱/木桶、工位与常用建筑装饰，细节tool_help。"
                    + "未知/不支持阻止完成，ready仅当前页预检。只查不施工，不自动找料。";
            case "move_to" -> "规划行走/挖穿/搭路；默认搜索按切片续推，无整趟节点/记录停止额度；walk_only=true不挖放，exact=true指定脚格，"
                    + "arrival=work须exact及work_target并以工作对象坐标寻工作终点。"
                    + "采掘目标已为空会结束无效寻路，核对原图后继续；目标消失不算本身体挖除。"
                    + "place_item工作导航省略face时自动检查可点击面，显式face保持原要求。"
                    + "拾取用新鲜掉落UUID填pickup_target_uuid及物品取整坐标，统一搜索实际触碰落点；到达后核背包。"
                    + "搭路只用无额外组件的普通材料，改地形遵守实际授权；ACCEPTED等真终态。";
            case "follow" -> "持续只行走跟随主人或扫描所得target_uuid；distance默认3、seconds默认30最多60。"
                    + "到位休眠不结束，拉开2格才唤醒；固定32格范围交原工地、原搜索账及期限含休眠/避险不重开。"
                    + "ACCEPTED等真终态，期限结束仅行为结束，goal_satisfied=false，取消不恢复。";
            case "attack" -> "默认原地不追击；approach=true在初始64格范围只走路接近同一目标，逐次搜索有资源预算，无累计重规划次数帽。"
                    + "数字entity_id必须同时带同一扫描条目的target_uuid；"
                    + "entity_id=\"hostile_nearby\"才不带UUID，且只选择附近未命名敌对生物。"
                    + "确认模式NEED_CONFIRM后ask_owner取得具体授权，保持approach。"
                    + "默认持续至目标死亡/失效/受阻或取消，不设挥击及总时长上限；max_hits省略或0，只有主人指定次数才填正数。"
                    + "ACCEPTED等真终态，挥击次数不等于伤害或击杀。";
            case "collect" -> "兼容触碰拾取，身体会自动拾取；r只是查询范围，远处先走近。"
                    + "ok=false可能已部分拾取，按新鲜背包核对成果。";
            case "status" -> "读取新鲜身体；data.x/y/z是脚格，没有position对象。details=true有"
                    + "data.inventory.slots[{slot,item,count}]；body_defense为有界自保及实际效果，有完整观测则复用。";
            case "inventory" -> "读取data.slots[{slot,item,count}]和selected_slot；列表从1起，"
                    + "entry.slot是equip使用的0-35槽号。";
            case "scan_area" -> "只读附近摘要：data.item_targets为掉落/触碰站位，entity_targets为活物，"
                    + "entity_id/target_uuid供attack，target_uuid也供follow，不可猜身份；special_blocks为近圈坐标文字列表。"
                    + "没有blocks字段。指定方块用search_resource。";
            case "inspect_block" -> "只读6.5格内精确方块，data.block是ID字符串；容器看slots及next_offset，"
                    + "指定item给全槽item_total及container_id，供交付验收；"
                    + "区域搜索用search_resource，不逐格inspect。";
            case "transfer" -> "近身普通容器存取，机器用smelt；ok=false可能部分搬运，未搬部分留在来源，先核对背包。";
            default -> {
                int end = spec.description().indexOf('。');
                yield end < 0 ? spec.description() : spec.description().substring(0, end + 1);
            }
        };
        return text
                + " 详细规则用tool_help查询；参数以本Schema为准。";
    }

    static ToolOutcome read(String json) {
        return read(json, SkillCatalog.empty());
    }

    static ToolOutcome read(String json, SkillCatalog skills) {
        try {
            if (json == null || json.length() > 1024) throw new IllegalArgumentException();
            var args = JsonParser.parseString(json).getAsJsonObject();
            var schema = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals("tool_help"))
                    .findFirst().orElseThrow();
            BoundedWorkflow.validate(args, JsonParser.parseString(schema.paramsJsonSchema()).getAsJsonObject());
            if (args.has("names") && args.getAsJsonArray("names").asList().stream()
                    .anyMatch(value -> value.getAsString().startsWith("skill:"))) {
                if (args.getAsJsonArray("names").size() != 1) return new ToolOutcome(false,
                        "SKILL_SINGLE_READ:技能正文每次只读一页，请单独使用目录name或返回的next_name。");
                return skills.read(args.getAsJsonArray("names").get(0).getAsString());
            }
            var data = new JsonObject();
            if (!args.has("names")) {
                var index = new JsonArray();
                for (ToolSpec spec : ClientToolDefs.SPECS) {
                    var entry = new JsonObject();
                    entry.addProperty("name", spec.name());
                    entry.addProperty("summary", summary(spec));
                    index.add(entry);
                }
                data.add("tools", index);
                var knowledge = skills.index();
                data.add("skills", knowledge.get("skills"));
                data.add("skill_diagnostics", knowledge.get("skill_diagnostics"));
            } else {
                var definitions = new JsonArray();
                for (var name : args.getAsJsonArray("names")) {
                    ToolSpec spec = ClientToolDefs.SPECS.stream()
                            .filter(s -> s.name().equals(name.getAsString())).findFirst().orElse(null);
                    if (spec == null) return new ToolOutcome(false,
                            "DENIED:工具名不在已注册目录中；用tool_help {}查看目录，未执行游戏动作。");
                    var definition = new JsonObject();
                    definition.addProperty("name", spec.name());
                    definition.addProperty("description", spec.description());
                    definition.add("parameters", JsonParser.parseString(spec.paramsJsonSchema()));
                    definition.addProperty("returns", ClientToolDefs.resultGuide(spec.name()));
                    definitions.add(definition);
                }
                data.add("definitions", definitions);
            }
            return new ToolOutcome(true, "只读工具说明；不表示已经执行任何动作。\n" + data, data);
        } catch (RuntimeException invalid) {
            return new ToolOutcome(false, "DENIED:tool_help只接受可选names，须为1-3个已注册工具名，未执行游戏动作。");
        }
    }
}
