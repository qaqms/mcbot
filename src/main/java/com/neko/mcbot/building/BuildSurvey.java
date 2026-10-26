package com.neko.mcbot.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.loop.SpatialFacts;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/** Read-only whole-target accounting, with bounded current-page native work assessment. */
public final class BuildSurvey {
    public static final int PAGE = 16, MAX_PREFLIGHTS = PAGE * 6;
    public static final long MAX_NANOS = 50_000_000L;
    public interface Access {
        BlockState state(BlockPos pos);
        boolean inBounds(BlockPos pos);
        boolean protectedCell(BlockPos pos);
        default boolean protectedPlacement(BlockPos pos) { return protectedCell(pos); }
        boolean near(BlockPos pos);
        BlockPlacement.Access placement();
        JsonObject terrain();
        default boolean clearable(BuildTarget.Cell cell) { return false; }
        default String clearReason(BuildTarget.Cell cell) { return ""; }
        default JsonObject surface(BlockPos pos) { return null; }
        default String workContext() { return ""; }
        default boolean repairPending(BlockPos pos) { return false; }
        default boolean bodyObstructsTarget() { return false; }
        default JsonObject miningTool(BlockPos pos, BlockState state) { return null; }
        default com.neko.mcbot.server.BlockAim.Anchor placementAnchor(BlockPos pos) {
            return com.neko.mcbot.server.BlockAim.Anchor.PRESENT;
        }
        default com.neko.mcbot.server.BlockAim.Anchor placementAnchor(BuildTarget.Cell cell) {
            return placementAnchor(cell.pos());
        }
    }
    private static final List<String> STATES = List.of("satisfied", "missing_material", "needs_clear",
            "needs_support", "out_of_reach", "protected", "unknown", "unsupported", "state_conflict",
            "blocked", "not_assessed", "out_of_scope", "paired_pending");

    private static final class Bill {
        final net.minecraft.world.item.Item item;
        int design, required, allocated, available, unresolved;
        Bill(net.minecraft.world.item.Item item) { this.item = item; }
    }
    private static final class Row {
        final BuildTarget.Cell cell;
        final BuildTarget.Material material;
        final BlockState actual;
        final int index;
        String state;
        JsonObject data;
        Row(BuildTarget.Cell cell, BuildTarget.Material material, BlockState actual, int index, String state) {
            this.cell=cell; this.material=material; this.actual=actual; this.index=index; this.state=state;
        }
    }
    private record MaterialKey(BlockState state, java.util.Set<String> properties) { }

    private BuildSurvey() { }

    public static ServerTool.Result query(BuildTarget target, Access access, int offset, int materialOffset,
                                           LongSupplier clock) {
        return query(target, access, offset, materialOffset, clock, true, true, false, false);
    }

    public static ServerTool.Result queryForWork(BuildTarget target, Access access, int offset, LongSupplier clock) {
        return query(target, access, offset, 0, clock, true, false, false, false);
    }
    public static ServerTool.Result queryForWork(BuildTarget target, Access access, int offset, LongSupplier clock,
                                                boolean includeRepairs) {
        return query(target, access, offset, 0, clock, true, false, false, false, includeRepairs);
    }

    /** Cursor is an index in the canonical target, not a page number. Satisfied cells need no detail transport. */
    public static ServerTool.Result workScan(BuildTarget target, Access access, int cursor, LongSupplier clock) {
        return query(target, access, cursor, 0, clock, true, false, true, false);
    }

    public static ServerTool.Result nearby(BuildTarget target, Access access, int cursor, LongSupplier clock) {
        return query(target, access, cursor, 0, clock, true, false, true, true);
    }
    public static ServerTool.Result nearby(BuildTarget target, Access access, int cursor, LongSupplier clock,
                                           boolean includeRepairs) {
        return query(target, access, cursor, 0, clock, true, false, true, true, includeRepairs);
    }

    public static ServerTool.Result repairWork(BuildTarget target, Access access, int cursor,
                                               LongSupplier clock, boolean wholeTarget) {
        return query(target, access, cursor, 0, clock, true, false, wholeTarget, wholeTarget, true, true);
    }

    public static ServerTool.Result verify(BuildTarget target, Access access, int offset, LongSupplier clock) {
        return query(target, access, offset, 0, clock, false, false, false, false);
    }

    private static ServerTool.Result query(BuildTarget target, Access access, int offset, int materialOffset,
                                            LongSupplier clock, boolean assessWork, boolean observeSurface,
                                            boolean workScan, boolean nearbyOnly) {
        return query(target,access,offset,materialOffset,clock,assessWork,observeSurface,workScan,nearbyOnly,true);
    }
    private static ServerTool.Result query(BuildTarget target, Access access, int offset, int materialOffset,
                                            LongSupplier clock, boolean assessWork, boolean observeSurface,
                                            boolean workScan, boolean nearbyOnly, boolean includeRepairs) {
        return query(target, access, offset, materialOffset, clock, assessWork, observeSurface,
                workScan, nearbyOnly, includeRepairs, false);
    }
    private static ServerTool.Result query(BuildTarget target, Access access, int offset, int materialOffset,
                                            LongSupplier clock, boolean assessWork, boolean observeSurface,
                                            boolean workScan, boolean nearbyOnly, boolean includeRepairs,
                                            boolean repairsOnly) {
        if (offset < 0 || offset >= target.cells().size() || !workScan && offset % PAGE != 0
                || materialOffset < 0 || materialOffset > BuildTarget.LIMIT || materialOffset % PAGE != 0)
            throw new IllegalArgumentException("page");
        long start = clock.getAsLong();
        var before = access.terrain();
        var rows = new ArrayList<Row>();
        var observation = new StringBuilder(target.revision());
        var goalObservation = new StringBuilder(target.revision());
        var bills = new TreeMap<String, Bill>();
        var actuals = new java.util.HashMap<BlockPos, BlockState>();
        var materialsByState = new java.util.HashMap<MaterialKey, BuildTarget.Material>();
        var stateDescriptions = new java.util.HashMap<BlockState,String>();
        boolean observationComplete = true, billComplete = true, budgetExhausted = false;
        int lowestSolidY = target.cells().stream().filter(c -> !c.authored().isAir())
                .mapToInt(c -> c.pos().getY()).min().orElse(Integer.MIN_VALUE);
        for (var cell : target.cells()) {
            boolean inTime = clock.getAsLong() - start < MAX_NANOS;
            budgetExhausted |= !inTime;
            actuals.put(cell.pos(), inTime && access.inBounds(cell.pos()) ? access.state(cell.pos()) : null);
        }
        int rowIndex=0;
        for (var cell : target.cells()) {
            var material = materialsByState.computeIfAbsent(new MaterialKey(cell.authored(),cell.properties()),
                    ignored -> cell.material());
            String problem=BuildObjects.problem(target,cell);
            if (!problem.isEmpty()) material=new BuildTarget.Material(material.item(),material.units(),false,problem);
            var materialItem=material.item();
            Bill bill = materialItem == null ? null : bills.computeIfAbsent(
                    BuiltInRegistries.ITEM.getKey(materialItem).toString(), ignored -> new Bill(materialItem));
            if (bill != null) bill.design += material.units();
            BlockState actual = actuals.get(cell.pos());
            boolean objectMatches = BuildObjects.matches(target, cell, actuals::get);
            observation.append('|').append(actual==null ? "unknown"
                    : stateDescriptions.computeIfAbsent(actual,BlockState::toString));
            cell.appendObservation(goalObservation,actual);
            if (actual == null) {
                observationComplete = false;
                billComplete = false;
                if (bill != null) bill.unresolved++;
            }
            if (bill != null && actual != null && !objectMatches) bill.required += material.units();
            if (BuildObjects.paired(cell.authored())) {
                var partner = BuildObjects.at(target, BuildObjects.other(cell.pos(), cell.desired()));
                if (partner == null || actuals.get(partner.pos()) == null) {
                    billComplete = false;
                    if (bill != null) bill.unresolved++;
                }
            }
            String state = cellState(target, cell, access, actual, actuals::get, material.supported(),
                    assessWork, lowestSolidY);
            if (!material.supported()) billComplete = false;
            rows.add(new Row(cell, material, actual, rowIndex++, state));
        }
        String goalKey = hash(goalObservation.toString());
        var clearingWork = clearingWork(rows, access, clock, start, observationComplete);
        for (var bill : bills.values()) {
            bill.available = BuildInventory.available(access.placement().inventory(), bill.item);
            observation.append('|').append(BuiltInRegistries.ITEM.getKey(bill.item)).append(':').append(bill.available);
        }
        String progressKey = hash(observation.toString());
        var selected=new ArrayList<Row>();
        int nextCursor=-1;
        int selectionEnd=workScan ? rows.size() : Math.min(rows.size(),offset+PAGE);
        for (int index=offset;index<selectionEnd;index++) {
            var row=rows.get(index);
            if (repairsOnly && !access.repairPending(row.cell.pos())) continue;
            if (!includeRepairs && access.repairPending(row.cell.pos())) continue;
            if (workScan && (row.state.equals("satisfied") || nearbyOnly && !row.state.equals("not_assessed"))) continue;
            if (workScan && !nearbyOnly && !List.of("not_assessed","needs_clear","out_of_reach").contains(row.state)) continue;
            if (workScan && row.material.item()!=null && bills.get(
                    BuiltInRegistries.ITEM.getKey(row.material.item()).toString()).available<row.material.units()) continue;
            if (selected.size()==PAGE) { nextCursor=index; break; }
            selected.add(row);
            if (!workScan && selected.size()==PAGE) break;
        }
        if (workScan && !nearbyOnly && selected.isEmpty()) {
            for (var row:rows) if (!row.state.equals("satisfied")) {
                selected.add(row);
                if (selected.size()==PAGE) break;
            }
        }
        for (var row:selected) {
            var data=row.cell.describe();
            row.data=data;
            data.addProperty("source_offset",row.index/PAGE*PAGE);
            data.addProperty("repair_pending", access.repairPending(row.cell.pos()));
            data.addProperty("material_rule_supported",row.material.supported());
            data.addProperty("material_units",row.material.units());
            data.addProperty("matches_actual",row.cell.matches(row.actual));
            if (row.actual!=null) {
                data.addProperty("actual_block",BuiltInRegistries.BLOCK.getKey(row.actual.getBlock()).toString());
                data.add("actual_properties",row.cell.actualProperties(row.actual));
            }
            if (BuildObjects.paired(row.cell.authored())) {
                var partner=BuildObjects.at(target,BuildObjects.other(row.cell.pos(),row.cell.desired()));
                data.addProperty("placement_primary",!BuildObjects.secondary(row.cell.authored()));
                if (partner!=null) data.add("paired_cell",BuildTarget.coordinates(partner.pos()));
            }
            if (!row.material.supported()) data.addProperty("unsupported_reason",row.material.reason());
            data.addProperty("state",row.state);
            if (row.state.equals("needs_support")) data.addProperty("placement_failure","no_placement_anchor");
            data.addProperty("clear_allowed",row.state.equals("needs_clear") && access.clearable(row.cell));
            if (row.state.equals("needs_clear") && !data.get("clear_allowed").getAsBoolean())
                data.addProperty("clear_reason",access.clearReason(row.cell));
            if (row.material.item()!=null)
                data.addProperty("item",BuiltInRegistries.ITEM.getKey(row.material.item()).toString());
            data.addProperty("material_available",row.material.item()==null ? row.material.supported()
                    : bills.get(BuiltInRegistries.ITEM.getKey(row.material.item()).toString()).available>=row.material.units());
        }
        int checks = 0;
        for (var row:selected) {
            if (!assessWork) break;
            if (observeSurface && clock.getAsLong() - start < MAX_NANOS) {
                var surface = access.surface(row.cell.pos());
                if (surface != null) row.data.add("surface", surface);
            }
            if (clock.getAsLong() - start >= MAX_NANOS) {
                budgetExhausted = true;
                break;
            }
            if (!row.state.equals("not_assessed")) continue;
            var bill = bills.get(BuiltInRegistries.ITEM.getKey(row.material.item()).toString());
            if (bill.available - bill.allocated < row.material.units()) {
                row.state="missing_material";
                row.data.addProperty("state", row.state);
                continue;
            }
            int slot = BuildInventory.slot(access.placement().inventory(), row.material.item());
            ServerTool.Result best = null;
            for (Direction face : List.of(Direction.UP, Direction.NORTH, Direction.SOUTH,
                    Direction.WEST, Direction.EAST, Direction.DOWN)) {
                if (checks >= MAX_PREFLIGHTS || clock.getAsLong() - start >= MAX_NANOS) {
                    budgetExhausted = true;
                    break;
                }
                checks++;
                var failure = BlockPlacement.assessDesired(access.placement(), row.cell.pos(), slot, face, target, row.cell);
                if (clock.getAsLong() - start >= MAX_NANOS) {
                    budgetExhausted = true;
                    break;
                }
                if (failure == null) {
                    row.state="ready";
                    row.data.addProperty("state", row.state);
                    row.data.addProperty("face", face.getName());
                    row.data.addProperty("slot", slot);
                    bill.allocated += row.material.units();
                    break;
                }
                if (best == null || priority(failure) > priority(best)) best = failure;
            }
            if (!row.state.equals("ready")) {
                if (budgetExhausted) continue;
                String classification = classify(best);
                row.state=classification;
                row.data.addProperty("state", classification);
                if (best != null && best.data() != null && best.data().has("placement_failure"))
                    row.data.add("placement_failure", best.data().get("placement_failure"));
            }
        }
        var designReview=BuildDesignReview.describe(target);
        if (observeSurface) designReview.add("foundation_surface",foundationSurface(rows,access,clock,start));
        var after = access.terrain();
        budgetExhausted |= clock.getAsLong()-start>=MAX_NANOS;
        boolean stable = SpatialFacts.retained(before, after) && before.equals(after);
        var counts = new JsonObject();
        for (String state : STATES) counts.addProperty(state, 0);
        counts.addProperty("ready", 0);
        for (var row : rows) {
            String state = row.state;
            counts.addProperty(state, counts.get(state).getAsInt() + 1);
        }
        int satisfied = counts.get("satisfied").getAsInt();
        var details = new JsonArray();
        selected.forEach(row -> details.add(row.data));
        var pageCounts = new JsonObject();
        for (var entry : counts.entrySet()) pageCounts.addProperty(entry.getKey(), 0);
        for (var entry : details) {
            String state = entry.getAsJsonObject().get("state").getAsString();
            pageCounts.addProperty(state, pageCounts.get(state).getAsInt() + 1);
        }
        var materials = new JsonArray();
        var entries = new ArrayList<>(bills.entrySet());
        if (materialOffset >= entries.size() && materialOffset != 0) throw new IllegalArgumentException("material_page");
        for (int i = materialOffset; i < Math.min(entries.size(), materialOffset + PAGE); i++) {
            var entry = entries.get(i);
            var bill = entry.getValue();
            var item = new JsonObject();
            item.addProperty("item", entry.getKey());
            item.addProperty("design_required", bill.design);
            item.addProperty("required", bill.required);
            item.addProperty("available", bill.available);
            item.addProperty("shortfall", Math.max(0, bill.required - bill.available));
            item.addProperty("allocated", bill.allocated);
            item.addProperty("consumed", 0);
            item.addProperty("unresolved_cells", bill.unresolved);
            materials.add(item);
        }
        var data = new JsonObject();
        data.addProperty("query", true);
        data.addProperty("target_revision", target.revision());
        data.addProperty("body_obstructs_target", access.bodyObstructsTarget());
        var anchor = new JsonObject();
        anchor.addProperty("x", target.anchor().getX()); anchor.addProperty("y", target.anchor().getY());
        anchor.addProperty("z", target.anchor().getZ());
        data.add("anchor", anchor);
        data.addProperty("rotation", target.rotation());
        data.addProperty("total", rows.size());
        data.addProperty("remaining", rows.size() - satisfied);
        data.add("counts", counts);
        data.add("cells", details);
        data.add("page_counts", pageCounts);
        data.addProperty("page_remaining", details.size() - pageCounts.get("satisfied").getAsInt());
        data.addProperty("page_satisfied", stable && observationComplete
                && pageCounts.get("satisfied").getAsInt() == details.size());
        data.addProperty("offset", workScan ? 0 : offset);
        data.addProperty("next_offset", !workScan && offset + PAGE < rows.size() ? offset + PAGE : -1);
        if (workScan) {
            data.addProperty("work_scan",true);
            data.addProperty("work_cursor",offset);
            data.addProperty("work_next_cursor",nextCursor);
            data.addProperty("details_scope",nearbyOnly ? "nearby_candidates" : "remaining_candidates");
        }
        data.addProperty("details_complete", details.size() == rows.size());
        data.add("materials", materials);
        data.addProperty("material_offset", materialOffset);
        data.addProperty("material_next_offset", materialOffset + PAGE < entries.size() ? materialOffset + PAGE : -1);
        data.addProperty("material_types", entries.size());
        data.addProperty("material_bill_complete", billComplete && stable
                && counts.get("unsupported").getAsInt() == 0 && counts.get("unknown").getAsInt() == 0);
        data.addProperty("material_scope", "whole_target_remaining_known_cells");
        data.addProperty("allocation_scope", "current_details_page_only_not_reserved");
        data.addProperty("observation_complete", observationComplete && stable);
        data.addProperty("spatial_stable", stable);
        data.addProperty("goal_observation_key", goalKey);
        data.addProperty("progress_observation_key", progressKey);
        data.addProperty("work_observation_key",hash(observation + "|" + access.workContext()));
        data.add("design_components",designComponents(target));
        var objectValidation=BuildObjects.validation(target);
        data.add("object_validation",objectValidation);
        data.add("design_review", designReview);
        data.add("clearing_work", clearingWork);
        if (after != null) data.add("spatial_facts", after);
        observation.append('|').append(after);
        data.addProperty("observation_key", hash(observation.toString()));
        data.addProperty("work_assessment_complete", !budgetExhausted && counts.get("not_assessed").getAsInt() == 0
                && counts.get("unknown").getAsInt() == 0 && stable);
        data.addProperty("budget_exhausted", budgetExhausted);
        data.addProperty("work_preflights", checks);
        data.addProperty("goal_satisfied", !budgetExhausted && observationComplete && stable && satisfied == rows.size());
        data.addProperty("native_validation_pending", satisfied != rows.size());
        data.addProperty("changed", false);
        data.addProperty("consumed_count", 0);
        return new ServerTool.Result(true, "建筑只读核对：已满足" + satisfied + "/" + rows.size()
                + "；分类=" + counts + "。查询未施工，材料分配只针对当前页且未预留；"
                + "out_of_reach只是当前站位手够不着，使用工作导航换站位；surface为实际支撑/脚高，不是路线可达证明。"
                + "needs_support表示尚缺放置前提，先完成相邻有支撑的格，现场改变后再核对，不靠反复走近解决。"
                + "page_counts仅本页，counts含全目标及页外未评估格；本页完成不等于整栋完成。"
                + "明细和料单分别按next_offset/material_next_offset及同一expected_revision续页，Lua可用mc.build_diff(target)。"
                + (!objectValidation.get("valid").getAsBoolean()
                ? " " + BuildObjects.invalidFeedback(objectValidation) : "")
                + (clearingWork.get("preparation_recommended").getAsBoolean()
                ? " 清障工具准备见clearing_work：批量清理前按真实配方考虑补做合适工具，材料和工具不由施工程序自动制作。" : ""), data);
    }

    private static JsonObject foundationSurface(List<Row> rows, Access access, LongSupplier clock, long start) {
        int lowest=rows.stream().filter(row->!row.cell.authored().isAir()).mapToInt(row->row.cell.pos().getY())
                .min().orElse(rows.getFirst().cell.pos().getY());
        int observed=0, replace=0, above=0, exposedAbove=0;
        boolean complete=true;
        var samples=new JsonArray();
        for (var row:rows) {
            if (row.cell.pos().getY()!=lowest || row.cell.authored().isAir()) continue;
            if (clock.getAsLong()-start>=MAX_NANOS) { complete=false; break; }
            var below=access.state(row.cell.pos().below());
            if (row.actual==null || below==null) { complete=false; continue; }
            observed++;
            if (soil(row.actual)) replace++;
            if (soil(below)) {
                above++;
                if (row.actual.isAir() || row.actual.canBeReplaced()) exposedAbove++;
            }
            if (samples.size()<4 && (soil(row.actual) || soil(below))) {
                var sample=BuildTarget.coordinates(row.cell.pos());
                sample.addProperty("actual_block",BuiltInRegistries.BLOCK.getKey(row.actual.getBlock()).toString());
                sample.addProperty("below_block",BuiltInRegistries.BLOCK.getKey(below.getBlock()).toString());
                samples.add(sample);
            }
        }
        var data=new JsonObject();
        data.addProperty("lowest_design_y",lowest);
        data.addProperty("observed_cells",observed);
        data.addProperty("topsoil_in_layer",replace);
        data.addProperty("topsoil_below_layer",above);
        data.addProperty("assessment_complete",complete);
        data.add("samples",samples);
        if (exposedAbove>0) data.addProperty("advice","最低设计层有待放格位于草地或泥土上方，核对是否有意抬高地基。"
                + "普通平地住宅默认替换表土，按support的方块y与feet_y分别定高；"
                + "这是当前地表样本，不是原始地形或坡地错误的证明，不自动改变已登记锚点。");
        return data;
    }

    private static boolean soil(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.PODZOL)
                || state.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.MYCELIUM);
    }

    private static JsonObject clearingWork(List<Row> rows, Access access, LongSupplier clock, long start,
                                           boolean observationComplete) {
        var states = new java.util.LinkedHashMap<BlockState, List<Row>>();
        int cells = 0;
        for (var row : rows) if (row.state.equals("needs_clear") && row.actual != null && !row.actual.isAir()) {
            cells++;
            states.computeIfAbsent(row.actual, ignored -> new ArrayList<>()).add(row);
        }
        var kinds = new TreeMap<String, JsonObject>();
        boolean complete = observationComplete, recommended = false;
        for (var entry : states.entrySet()) {
            if (clock.getAsLong() - start >= MAX_NANOS) { complete = false; break; }
            var tool = access.miningTool(entry.getValue().getFirst().cell.pos(), entry.getKey());
            if (tool == null) { complete = false; continue; }
            if (!tool.has("preferred_kind")) continue;
            String kind = tool.get("preferred_kind").getAsString();
            if (!List.of("axe", "pickaxe", "shovel", "hoe").contains(kind)) continue;
            var group = kinds.computeIfAbsent(kind, ignored -> {
                var data = new JsonObject();
                data.addProperty("preferred_kind", kind);
                data.addProperty("cells", 0);
                data.addProperty("estimated_seconds", 0.0);
                data.addProperty("estimate_complete", true);
                data.addProperty("preparation_recommended", false);
                data.addProperty("sample_block", BuiltInRegistries.BLOCK.getKey(entry.getKey().getBlock()).toString());
                data.add("sample_tool", tool.deepCopy());
                return data;
            });
            int count = entry.getValue().size();
            group.addProperty("cells", group.get("cells").getAsInt() + count);
            if (tool.has("estimated_seconds"))
                group.addProperty("estimated_seconds", group.get("estimated_seconds").getAsDouble()
                        + tool.get("estimated_seconds").getAsDouble() * count);
            else group.addProperty("estimate_complete", false);
            boolean prepare = tool.has("preparation_recommended") && tool.get("preparation_recommended").getAsBoolean();
            if (prepare) group.add("sample_tool", tool.deepCopy());
            group.addProperty("preparation_recommended", group.get("preparation_recommended").getAsBoolean() || prepare);
            recommended |= prepare;
        }
        var groups = new JsonArray();
        kinds.values().forEach(groups::add);
        var data = new JsonObject();
        data.addProperty("observed_cells", cells);
        data.addProperty("assessment_complete", complete);
        data.addProperty("preparation_recommended", recommended);
        data.add("tools", groups);
        return data;
    }

    private static JsonObject designComponents(BuildTarget target) {
        var data=new JsonObject();
        for (String key:List.of("doors","beds","crafting_tables","containers","lights","stairs","slabs"))
            data.addProperty(key,0);
        for (var cell:target.cells()) {
            var state=cell.authored();
            var block=state.getBlock();
            String key=null;
            if (block instanceof net.minecraft.world.level.block.DoorBlock && !BuildObjects.secondary(state)) key="doors";
            else if (BuildObjects.bed(state) && !BuildObjects.secondary(state)) key="beds";
            else if (block==net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) key="crafting_tables";
            else if (block instanceof net.minecraft.world.level.block.ChestBlock
                    || block instanceof net.minecraft.world.level.block.BarrelBlock) key="containers";
            else if (state.getLightEmission()>0) key="lights";
            else if (block instanceof net.minecraft.world.level.block.StairBlock) key="stairs";
            else if (block instanceof net.minecraft.world.level.block.SlabBlock) key="slabs";
            if (key!=null) data.addProperty(key,data.get(key).getAsInt()+1);
        }
        return data;
    }

    private static String hash(String facts) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** The same fresh cell rules precede advisory work and every actual building action. */
    public static String cellState(BuildTarget.Cell cell, Access access, BlockState actual) {
        return cellState(cell,access,actual,cell.material().supported(),true);
    }
    private static String cellState(BuildTarget.Cell cell, Access access, BlockState actual, boolean supported,
                                    boolean assessWork) {
        if (!access.inBounds(cell.pos())) return "out_of_scope";
        if (actual == null) return "unknown";
        if (!supported) return "unsupported";
        if (cell.matches(actual)) return "satisfied";
        if (actual.isAir() ? access.protectedPlacement(cell.pos()) : access.protectedCell(cell.pos()))
            return "protected";
        if (actual.getBlock() == cell.authored().getBlock()) return access.clearable(cell) ? "needs_clear" : "state_conflict";
        if (cell.authored().isAir() || !actual.canBeReplaced()) return "needs_clear";
        String prerequisite = assessWork ? placementPrerequisite(access, cell) : null;
        if (prerequisite != null) return prerequisite;
        if (!access.near(cell.pos())) return "out_of_reach";
        return "not_assessed";
    }

    public static String cellState(BuildTarget target, BuildTarget.Cell cell, Access access, BlockState actual) {
        int lowestSolidY = target.cells().stream().filter(c -> !c.authored().isAir())
                .mapToInt(c -> c.pos().getY()).min().orElse(Integer.MIN_VALUE);
        return cellState(target, cell, access, actual, access::state,target.material(cell).supported(),true,
                lowestSolidY);
    }

    private static String cellState(BuildTarget target, BuildTarget.Cell cell, Access access, BlockState actual,
                                    java.util.function.Function<BlockPos, BlockState> states, boolean supported,
                                    boolean assessWork, int lowestSolidY) {
        if (!supported) return "unsupported";
        if (!BuildObjects.paired(cell.authored())) {
            if (cell.pos().getY() == lowestSolidY && foundationCover(actual)) return "needs_clear";
            return cellState(cell, access, actual, true,assessWork);
        }
        var partner = BuildObjects.at(target, BuildObjects.other(cell.pos(), cell.desired()));
        if (!access.inBounds(cell.pos()) || !access.inBounds(partner.pos())) return "out_of_scope";
        var other = states.apply(partner.pos());
        if (actual == null || other == null) return "unknown";
        if (BuildObjects.matches(target, cell, states)) return "satisfied";
        if ((actual.isAir() ? access.protectedPlacement(cell.pos()) : access.protectedCell(cell.pos()))
                || (other.isAir() ? access.protectedPlacement(partner.pos()) : access.protectedCell(partner.pos())))
            return "protected";
        if (!actual.isAir() || !other.isAir())
            return access.clearable(cell) ? "needs_clear" : "state_conflict";
        if (BuildObjects.secondary(cell.authored())) return "paired_pending";
        if (cell.pos().getY() == lowestSolidY && foundationCover(actual))
            return "needs_clear";
        String prerequisite = assessWork ? placementPrerequisite(access, cell) : null;
        if (prerequisite != null) return prerequisite;
        return access.near(cell.pos()) ? "not_assessed" : "out_of_reach";
    }

    private static boolean foundationCover(BlockState actual) {
        return actual != null && (actual.is(net.minecraft.world.level.block.Blocks.SHORT_GRASS)
                || actual.is(net.minecraft.world.level.block.Blocks.TALL_GRASS)
                || actual.getBlock() instanceof net.minecraft.world.level.block.FlowerBlock
                || actual.getBlock() instanceof net.minecraft.world.level.block.TallFlowerBlock
                || actual.getBlock() instanceof net.minecraft.world.level.block.FlowerBedBlock);
    }

    private static String placementPrerequisite(Access access, BuildTarget.Cell cell) {
        return switch (access.placementAnchor(cell)) {
            case ABSENT -> "needs_support";
            case UNKNOWN -> "unknown";
            case PRESENT -> null;
        };
    }

    private static String classify(ServerTool.Result failure) {
        if (failure == null) return "not_assessed";
        String reason = failure.data() != null && failure.data().has("placement_failure")
                ? failure.data().get("placement_failure").getAsString() : "";
        if (reason.equals("desired_state_mismatch")) return "state_conflict";
        if (reason.equals("state_prediction_unknown")) return "unsupported";
        if (reason.equals("unsupported_state")) return "needs_support";
        if (failure.feedback().startsWith("DENIED:")) return "protected";
        if (failure.feedback().startsWith("TARGET_LOST:")) return "unknown";
        if (failure.feedback().startsWith("OUT_OF_REACH:")) return "out_of_reach";
        if (failure.feedback().startsWith("NO_ITEM:")) return "missing_material";
        return "blocked";
    }
    private static int priority(ServerTool.Result failure) {
        return switch (classify(failure)) {
            case "protected", "unknown" -> 5;
            case "state_conflict", "unsupported" -> 4;
            case "needs_support" -> 3;
            case "blocked" -> 2;
            default -> 1;
        };
    }
}
