package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.BuildTarget;
import com.neko.mcbot.path.NavigationGeometry;
import com.neko.mcbot.path.NavigationGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.function.Function;

/** One route compares strict work intents from a single frozen drawing layer. */
public final class PlacementWorkSet {
    public static final int LIMIT = 16;
    public record Member(BlockPos target, PlacementWork work) {
        public Member { target = target.immutable(); }
        JsonObject describe() {
            var data = BuildTarget.coordinates(target);
            data.addProperty("item", BuiltInRegistries.ITEM.getKey(work.item()).toString());
            if (work.face() != null) data.addProperty("face", work.face().getName());
            return data;
        }
    }
    private final List<Member> members;
    private final String revision, id;
    private final ServerTool.Result[] failures;
    private int generation;

    public PlacementWorkSet(List<Member> members, String revision) {
        this.members = List.copyOf(members);
        if (members.isEmpty() || members.size() > LIMIT || revision == null || !revision.matches("[a-f0-9]{64}")
                || members.stream().map(Member::target).distinct().count() != members.size()
                || members.stream().map(m -> m.target().getY()).distinct().count() != 1)
            throw new IllegalArgumentException("work members");
        this.revision = revision;
        failures = new ServerTool.Result[members.size()];
        try {
            id = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((revision + describe()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public List<Member> members() { return members; }
    public int generation() { return generation; }

    public ServerTool.Result refresh(CompanionPlayer body) {
        if (!body.buildSite().active() || !revision.equals(body.buildSite().target().revision()))
            return new ServerTool.Result(false, "TARGET_REVISION_CHANGED:多目标工作须保持原登记工地修订。", null);
        return refresh(member -> member.work().targetFailure(body, member.target()));
    }

    public ServerTool.Result refresh(Function<Member, ServerTool.Result> check) {
        for (int i = 0; i < members.size(); i++) {
            if (failures[i] != null) continue;
            var failure = check.apply(members.get(i));
            if (failure != null) {
                if (!(failure.feedback().startsWith("TARGET_BLOCKED:") || failure.feedback().startsWith("TARGET_LOST:")
                        || failure.feedback().startsWith("NO_ITEM:"))) return failure;
                failures[i] = failure;
                generation++;
            }
        }
        for (var failure : failures) if (failure == null) return null;
        var data = new JsonObject();
        addIdentity(data);
        var reasons = new JsonArray();
        boolean unknown = false, missing = false;
        for (int i = 0; i < failures.length; i++) {
            var reason = members.get(i).describe();
            String feedback = failures[i].feedback();
            String kind = feedback.split(":", 2)[0];
            reason.addProperty("failure_kind", kind);
            if (failures[i].data() != null && failures[i].data().has("placement_failure"))
                reason.add("placement_failure", failures[i].data().get("placement_failure"));
            unknown |= kind.equals("TARGET_LOST");
            missing |= kind.equals("NO_ITEM");
            reasons.add(reason);
        }
        data.add("work_member_failures", reasons);
        return new ServerTool.Result(false, (unknown ? "TARGET_LOST:" : missing ? "NO_ITEM:" : "TARGET_BLOCKED:")
                + "本次同层候选已无可用放置前提，重新核对原图；已有路线效果保留。", data);
    }

    public NavigationGoal goal(CompanionPlayer body, NavigationGeometry geometry) {
        return goal(member -> member.work().goal(body, member.target(), geometry));
    }

    public NavigationGoal goal(Function<Member, NavigationGoal> create) {
        var goals = new java.util.ArrayList<NavigationGoal>();
        for (int i = 0; i < members.size(); i++)
            if (failures[i] == null) goals.add(create.apply(members.get(i)));
        return NavigationGoal.anyOf(goals);
    }

    public ServerTool.Result finish(ServerTool.Result moved, CompanionPlayer body) {
        return finish(moved, member -> {
            var failure = member.work().targetFailure(body, member.target());
            return failure != null ? failure : member.work().assess(body, member.target());
        });
    }

    public ServerTool.Result finish(ServerTool.Result moved, Function<Member, ServerTool.Result> assess) {
        var data = moved.data() == null ? new JsonObject() : moved.data().deepCopy();
        addIdentity(data);
        if (!moved.ok()) return new ServerTool.Result(false, moved.feedback(), data);
        ServerTool.Result missing = null;
        for (int i = 0; i < members.size(); i++) {
            if (failures[i] != null) continue;
            var member = members.get(i);
            var current = assess.apply(member);
            if (!current.ok()) {
                if (current.feedback().startsWith("NO_ITEM:")) missing = current;
                else if (!(current.feedback().startsWith("TARGET_BLOCKED:") || current.feedback().startsWith("OUT_OF_REACH:"))) {
                    data.addProperty("can_work_here", false);
                    return new ServerTool.Result(false, current.feedback(), data);
                }
                continue;
            }
            var finished = PlacementWork.finish(new ServerTool.Result(true, moved.feedback(), data), () -> current);
            finished.data().add("selected_work_target", BuildTarget.coordinates(member.target()));
            finished.data().addProperty("selected_place_item", BuiltInRegistries.ITEM.getKey(member.work().item()).toString());
            return finished;
        }
        data.addProperty("can_work_here", false);
        if (missing != null) return new ServerTool.Result(false, missing.feedback(), data);
        return new ServerTool.Result(false, "TARGET_BLOCKED:实际落点没有通过完整放置预检的候选；"
                + "已有移动和挖放效果保留，重新核对原图。", data);
    }

    private JsonArray describe() {
        var data = new JsonArray();
        members.forEach(member -> data.add(member.describe()));
        return data;
    }
    public void addIdentity(JsonObject data) {
        data.addProperty("work_set_id", id);
        data.addProperty("target_revision", revision);
        data.addProperty("work_target_count", members.size());
        data.add("work_targets", describe());
    }
}
