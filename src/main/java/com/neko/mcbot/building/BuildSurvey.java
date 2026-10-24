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
        boolean near(BlockPos pos);
        BlockPlacement.Access placement();
        JsonObject terrain();
        default boolean clearable(BuildTarget.Cell cell) { return false; }
        default JsonObject surface(BlockPos pos) { return null; }
    }
    private static final List<String> STATES = List.of("satisfied", "missing_material", "needs_clear",
            "needs_support", "out_of_reach", "protected", "unknown", "unsupported", "state_conflict",
            "blocked", "not_assessed", "out_of_scope", "paired_pending");

    private static final class Bill {
        final net.minecraft.world.item.Item item;
        int design, required, allocated, available, unresolved;
        Bill(net.minecraft.world.item.Item item) { this.item = item; }
    }
    private record Row(BuildTarget.Cell cell, BuildTarget.Material material, JsonObject data) { }

    private BuildSurvey() { }

    public static ServerTool.Result query(BuildTarget target, Access access, int offset, int materialOffset,
                                           LongSupplier clock) {
        return query(target, access, offset, materialOffset, clock, true, true);
    }

    public static ServerTool.Result queryForWork(BuildTarget target, Access access, int offset, LongSupplier clock) {
        return query(target, access, offset, 0, clock, true, false);
    }

    public static ServerTool.Result verify(BuildTarget target, Access access, int offset, LongSupplier clock) {
        return query(target, access, offset, 0, clock, false, false);
    }

    private static ServerTool.Result query(BuildTarget target, Access access, int offset, int materialOffset,
                                            LongSupplier clock, boolean assessWork, boolean observeSurface) {
        if (offset < 0 || offset >= target.cells().size() || offset % PAGE != 0
                || materialOffset < 0 || materialOffset > BuildTarget.LIMIT || materialOffset % PAGE != 0)
            throw new IllegalArgumentException("page");
        long start = clock.getAsLong();
        var before = access.terrain();
        var rows = new ArrayList<Row>();
        var observation = new StringBuilder(target.revision());
        var goalObservation = new StringBuilder(target.revision());
        var bills = new TreeMap<String, Bill>();
        var actuals = new java.util.HashMap<BlockPos, BlockState>();
        boolean observationComplete = true, billComplete = true, budgetExhausted = false;
        for (var cell : target.cells()) {
            boolean inTime = clock.getAsLong() - start < MAX_NANOS;
            budgetExhausted |= !inTime;
            actuals.put(cell.pos(), inTime && access.inBounds(cell.pos()) ? access.state(cell.pos()) : null);
        }
        for (var cell : target.cells()) {
            var material = target.material(cell);
            var data = cell.describe();
            data.addProperty("material_rule_supported", material.supported());
            data.addProperty("material_units", material.units());
            Bill bill = material.item() == null ? null : bills.computeIfAbsent(
                    BuiltInRegistries.ITEM.getKey(material.item()).toString(), ignored -> new Bill(material.item()));
            if (bill != null) bill.design += material.units();
            BlockState actual = actuals.get(cell.pos());
            boolean matches = cell.matches(actual);
            boolean objectMatches = BuildObjects.matches(target, cell, actuals::get);
            observation.append('|').append(actual);
            goalObservation.append('|').append(actual == null ? "unknown"
                    : BuiltInRegistries.BLOCK.getKey(actual.getBlock()) + "|" + cell.actualProperties(actual));
            data.addProperty("matches_actual", matches);
            if (actual != null) {
                data.addProperty("actual_block", BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString());
                data.add("actual_properties", cell.actualProperties(actual));
            }
            else {
                observationComplete = false;
                billComplete = false;
                if (bill != null) bill.unresolved++;
            }
            if (bill != null && actual != null && !objectMatches) bill.required += material.units();
            if (BuildObjects.door(cell.authored())) {
                var partner = BuildObjects.at(target, BuildObjects.other(cell.pos(), cell.desired()));
                data.addProperty("placement_primary", !BuildObjects.upper(cell.authored()));
                if (partner != null) data.add("paired_cell", BuildTarget.coordinates(partner.pos()));
                if (partner == null || actuals.get(partner.pos()) == null) {
                    billComplete = false;
                    if (bill != null) bill.unresolved++;
                }
            }
            String state = cellState(target, cell, access, actual, actuals::get);
            if (!material.supported()) {
                billComplete = false;
                data.addProperty("unsupported_reason", material.reason());
            }
            data.addProperty("state", state);
            data.addProperty("clear_allowed", state.equals("needs_clear") && access.clearable(cell));
            if (material.item() != null)
                data.addProperty("item", BuiltInRegistries.ITEM.getKey(material.item()).toString());
            rows.add(new Row(cell, material, data));
        }
        String goalKey = hash(goalObservation.toString());
        for (var bill : bills.values()) {
            bill.available = BuildInventory.available(access.placement().inventory(), bill.item);
            observation.append('|').append(BuiltInRegistries.ITEM.getKey(bill.item)).append(':').append(bill.available);
        }
        for (var row : rows) row.data().addProperty("material_available", row.material().item() == null
                ? row.material().supported() : bills.get(BuiltInRegistries.ITEM.getKey(row.material().item()).toString()).available
                >= row.material().units());
        int checks = 0;
        for (int index = offset; index < Math.min(rows.size(), offset + PAGE); index++) {
            if (!assessWork) break;
            Row row = rows.get(index);
            if (observeSurface && clock.getAsLong() - start < MAX_NANOS) {
                var surface = access.surface(row.cell().pos());
                if (surface != null) row.data().add("surface", surface);
            }
            if (clock.getAsLong() - start >= MAX_NANOS) {
                budgetExhausted = true;
                break;
            }
            if (!row.data().get("state").getAsString().equals("not_assessed")) continue;
            var bill = bills.get(BuiltInRegistries.ITEM.getKey(row.material().item()).toString());
            if (bill.available - bill.allocated < row.material().units()) {
                row.data().addProperty("state", "missing_material");
                continue;
            }
            int slot = BuildInventory.slot(access.placement().inventory(), row.material().item());
            ServerTool.Result best = null;
            for (Direction face : List.of(Direction.UP, Direction.NORTH, Direction.SOUTH,
                    Direction.WEST, Direction.EAST, Direction.DOWN)) {
                if (checks >= MAX_PREFLIGHTS || clock.getAsLong() - start >= MAX_NANOS) {
                    budgetExhausted = true;
                    break;
                }
                checks++;
                var failure = BlockPlacement.assessDesired(access.placement(), row.cell().pos(), slot, face, target, row.cell());
                if (clock.getAsLong() - start >= MAX_NANOS) {
                    budgetExhausted = true;
                    break;
                }
                if (failure == null) {
                    row.data().addProperty("state", "ready");
                    row.data().addProperty("face", face.getName());
                    row.data().addProperty("slot", slot);
                    bill.allocated += row.material().units();
                    break;
                }
                if (best == null || priority(failure) > priority(best)) best = failure;
            }
            if (!row.data().get("state").getAsString().equals("ready")) {
                if (budgetExhausted) continue;
                String classification = classify(best);
                row.data().addProperty("state", classification);
                if (best != null && best.data() != null && best.data().has("placement_failure"))
                    row.data().add("placement_failure", best.data().get("placement_failure"));
            }
        }
        var after = access.terrain();
        boolean stable = SpatialFacts.retained(before, after) && before.equals(after);
        var counts = new JsonObject();
        for (String state : STATES) counts.addProperty(state, 0);
        counts.addProperty("ready", 0);
        for (var row : rows) {
            String state = row.data().get("state").getAsString();
            counts.addProperty(state, counts.get(state).getAsInt() + 1);
        }
        int satisfied = counts.get("satisfied").getAsInt();
        var details = new JsonArray();
        rows.subList(offset, Math.min(rows.size(), offset + PAGE)).forEach(row -> details.add(row.data()));
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
        var anchor = new JsonObject();
        anchor.addProperty("x", target.anchor().getX()); anchor.addProperty("y", target.anchor().getY());
        anchor.addProperty("z", target.anchor().getZ());
        data.add("anchor", anchor);
        data.addProperty("rotation", target.rotation());
        data.addProperty("total", rows.size());
        data.addProperty("remaining", rows.size() - satisfied);
        data.add("counts", counts);
        data.add("cells", details);
        data.addProperty("offset", offset);
        data.addProperty("next_offset", offset + PAGE < rows.size() ? offset + PAGE : -1);
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
        if (after != null) data.add("spatial_facts", after);
        observation.append('|').append(after);
        data.addProperty("observation_key", hash(observation.toString()));
        data.addProperty("work_assessment_complete", !budgetExhausted && counts.get("not_assessed").getAsInt() == 0
                && counts.get("unknown").getAsInt() == 0 && stable);
        data.addProperty("budget_exhausted", budgetExhausted);
        data.addProperty("work_preflights", checks);
        data.addProperty("goal_satisfied", observationComplete && stable && satisfied == rows.size());
        data.addProperty("native_validation_pending", satisfied != rows.size());
        data.addProperty("changed", false);
        data.addProperty("consumed_count", 0);
        return new ServerTool.Result(true, "建筑只读核对：已满足" + satisfied + "/" + rows.size()
                + "；分类=" + counts + "。查询未施工，材料分配只针对当前页且未预留；"
                + "out_of_reach只是当前站位手够不着，使用工作导航换站位；surface为实际支撑/脚高，不是路线可达证明。"
                + "未知、不支持、状态冲突及不稳定观察不能认定整项目标满足。明细和料单分别按next_offset/material_next_offset续页。", data);
    }

    private static String hash(String facts) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** The same fresh cell rules precede advisory work and every actual building action. */
    public static String cellState(BuildTarget.Cell cell, Access access, BlockState actual) {
        if (!access.inBounds(cell.pos())) return "out_of_scope";
        if (actual == null) return "unknown";
        if (!cell.material().supported()) return "unsupported";
        if (cell.matches(actual)) return "satisfied";
        if (access.protectedCell(cell.pos())) return "protected";
        if (actual.getBlock() == cell.authored().getBlock()) return access.clearable(cell) ? "needs_clear" : "state_conflict";
        if (cell.authored().isAir() || !actual.canBeReplaced()) return "needs_clear";
        if (!access.near(cell.pos())) return "out_of_reach";
        return "not_assessed";
    }

    public static String cellState(BuildTarget target, BuildTarget.Cell cell, Access access, BlockState actual) {
        return cellState(target, cell, access, actual, access::state);
    }

    private static String cellState(BuildTarget target, BuildTarget.Cell cell, Access access, BlockState actual,
                                    java.util.function.Function<BlockPos, BlockState> states) {
        if (!target.material(cell).supported()) return "unsupported";
        if (!BuildObjects.door(cell.authored())) return cellState(cell, access, actual);
        var partner = BuildObjects.at(target, BuildObjects.other(cell.pos(), cell.desired()));
        if (!access.inBounds(cell.pos()) || !access.inBounds(partner.pos())) return "out_of_scope";
        var other = states.apply(partner.pos());
        if (actual == null || other == null) return "unknown";
        if (BuildObjects.matches(target, cell, states)) return "satisfied";
        if (access.protectedCell(cell.pos()) || access.protectedCell(partner.pos())) return "protected";
        if (!actual.isAir() || !other.isAir())
            return access.clearable(cell) ? "needs_clear" : "state_conflict";
        if (BuildObjects.upper(cell.authored())) return "paired_pending";
        return access.near(cell.pos()) ? "not_assessed" : "out_of_reach";
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
