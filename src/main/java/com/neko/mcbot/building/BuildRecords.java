package com.neko.mcbot.building;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** World-local data, never executable work. Loaded provenance deliberately has no live witness. */
public final class BuildRecords {
    public static final int ENTRY_LIMIT = 32, ACTION_LIMIT = 256, FILE_LIMIT = 32 * 1024 * 1024;
    private final Path file;
    private UUID worldId = UUID.randomUUID();
    private boolean healthy = true;
    private int serializedBytes;
    private final Map<String, Design> designs = new LinkedHashMap<>();
    private final Map<String, Instance> instances = new LinkedHashMap<>();

    private record Design(UUID owner, String id, int revision, BuildTarget target) { }
    private static final class Placed {
        final BlockPos pos;
        final BlockState state;
        final Object chunk;
        final CellWitness witness;
        Placed(BlockPos pos, BlockState state, Object chunk, CellWitness witness) {
            this.pos = pos.immutable(); this.state = state; this.chunk = chunk; this.witness = witness;
        }
        boolean intact(BuildSite.Access access) {
            return witness != null && witness.intact() && chunk == access.chunk(pos) && state == access.state(pos);
        }
    }
    public static final class Instance {
        private final String id, designId, dimension;
        private final UUID owner, builder;
        private int revision, designRevision;
        private BuildTarget target;
        private final Map<BlockPos, Placed> placed = new LinkedHashMap<>();
        private final JsonArray actions = new JsonArray();
        private Instance(String id, UUID owner, UUID builder, String dimension, String designId,
                         int revision, int designRevision, BuildTarget target) {
            this.id=id; this.owner=owner; this.builder=builder; this.dimension=dimension; this.designId=designId;
            this.revision=revision; this.designRevision=designRevision; this.target=target;
        }
        public String id() { return id; }
        public int revision() { return revision; }
        public BuildTarget target() { return target; }
    }

    public BuildRecords(Path worldRoot) { file = worldRoot.resolve("mcbot").resolve("buildings.json"); }
    public boolean healthy() { return healthy; }
    public UUID worldId() { return worldId; }
    public void initializeIdentity() {
        requireHealthy();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) save();
    }

    public void load() {
        designs.clear(); instances.clear();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (Files.isSymbolicLink(file) || Files.size(file) > FILE_LIMIT) throw new IOException("record_size");
            // JSON nesting is bounded before constructing a tree.
            String text = Files.readString(file, StandardCharsets.UTF_8);
            serializedBytes=text.getBytes(StandardCharsets.UTF_8).length;
            int depth=0; boolean quoted=false, escaped=false;
            for (char ch : text.toCharArray()) {
                if (quoted) {
                    if (escaped) escaped=false; else if (ch=='\\') escaped=true; else if (ch=='"') quoted=false;
                } else if (ch=='"') quoted=true;
                else if (ch=='{' || ch=='[') { if (++depth > 24) throw new IOException("record_depth"); }
                else if (ch=='}' || ch==']') depth--;
            }
            decode(JsonParser.parseString(text).getAsJsonObject());
        } catch (IOException | RuntimeException invalid) {
            healthy=false; designs.clear(); instances.clear();
        }
    }

    private void requireHealthy() {
        if (!healthy) throw new IllegalStateException("RECORD_UNAVAILABLE");
    }
    private void save() {
        requireHealthy();
        try {
            byte[] bytes = encode().toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > FILE_LIMIT || Files.isSymbolicLink(file)
                    || Files.isSymbolicLink(file.getParent())) throw new IOException("record_store");
            Files.createDirectories(file.getParent());
            Path pending = Files.createTempFile(file.getParent(), "buildings-", ".pending");
            try {
                Files.write(pending, bytes);
                Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                serializedBytes=bytes.length;
            } finally { Files.deleteIfExists(pending); }
        } catch (IOException | RuntimeException failure) {
            healthy=false;
            throw new IllegalStateException("RECORD_UNAVAILABLE");
        }
    }

    private static String key(UUID owner, String id) { return owner + ":" + id; }
    public static String name(JsonElement value) {
        String id=BuildTarget.string(value,48);
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,47}")) throw new IllegalArgumentException("identifier");
        return id;
    }
    public static BuildTarget definition(JsonObject input) {
        BuildTarget.keys(input, Set.of("cells","fill","regions"));
        var target=input.deepCopy(); target.add("anchor", BuildTarget.coordinates(BlockPos.ZERO));
        return BuildTarget.parse(target);
    }
    public String createDesign(UUID owner, String id, JsonObject input) {
        requireHealthy();
        var target=definition(input);
        if (designs.containsKey(key(owner,id)) || designs.size()>=ENTRY_LIMIT) throw new IllegalArgumentException("design_exists_or_limit");
        designs.put(key(owner,id), new Design(owner,id,1,target)); save(); return id;
    }
    private Design design(UUID owner, String id, int revision) {
        requireHealthy();
        var design=designs.get(key(owner,id));
        if (design==null || design.revision!=revision) throw new IllegalArgumentException("design_revision");
        return design;
    }
    public int designRevision(UUID owner, String id) {
        requireHealthy();
        return Objects.requireNonNull(designs.get(key(owner,id))).revision;
    }
    public void patchDesign(UUID owner, String id, int expected, JsonArray edits) {
        var original=design(owner,id,expected);
        if (expected>=1024 || edits.isEmpty() || edits.size()>16) throw new IllegalArgumentException("edit_limit");
        var values=new LinkedHashMap<BlockPos,JsonObject>();
        for (var cell:original.target.expandedInput().getAsJsonArray("cells")) {
            var item=cell.getAsJsonObject(); values.put(position(item),item);
        }
        var seen=new HashSet<BlockPos>();
        for (var edit:edits) {
            var item=edit.getAsJsonObject(); var pos=position(item);
            if (!seen.add(pos)) throw new IllegalArgumentException("duplicate_edit");
            if (item.has("remove")) {
                BuildTarget.keys(item,Set.of("x","y","z","remove"));
                if (!item.get("remove").isJsonPrimitive() || !item.getAsJsonPrimitive("remove").isBoolean()
                        || !item.get("remove").getAsBoolean() || values.remove(pos)==null)
                    throw new IllegalArgumentException("remove");
            } else values.put(pos,item);
        }
        var cells=new JsonArray(); values.values().forEach(cells::add);
        var input=new JsonObject(); input.add("cells",cells);
        var target=definition(input);
        if (!target.revision().equals(original.target.revision())) {
            designs.put(key(owner,id),new Design(owner,id,expected+1,target)); save();
        }
    }
    public static BlockPos position(JsonObject item) {
        return new BlockPos(BuildTarget.integer(item.get("x"),-29999984,29999984),
                BuildTarget.integer(item.get("y"),-2048,2047),BuildTarget.integer(item.get("z"),-29999984,29999984));
    }
    private static BuildTarget locate(Design design, BlockPos anchor, int rotation) {
        var input=design.target.input(); input.add("anchor",BuildTarget.coordinates(anchor));
        input.addProperty("rotation",rotation); return BuildTarget.parse(input);
    }
    public Instance createInstance(UUID owner, UUID builder, String dimension, String designId,
                                   int designRevision, BlockPos anchor, int rotation) {
        var design=design(owner,designId,designRevision);
        var target=locate(design,anchor,rotation);
        if (instances.size()>=ENTRY_LIMIT) throw new IllegalArgumentException("instance_limit");
        if (instances.values().stream().anyMatch(i -> i.dimension.equals(dimension) && i.target.cells().stream()
                .anyMatch(c -> target.index().containsKey(c.pos()))))
            throw new IllegalArgumentException("overlapping_instance");
        var instance=new Instance(UUID.randomUUID().toString(),owner,builder,dimension,designId,1,designRevision,target);
        instances.put(instance.id,instance); save(); return instance;
    }
    public Instance instance(UUID owner, UUID builder, String dimension, String id, int revision) {
        requireHealthy();
        var instance=instances.get(id);
        if (instance==null || !instance.owner.equals(owner) || !instance.builder.equals(builder)
                || !instance.dimension.equals(dimension) || instance.revision!=revision)
            throw new IllegalArgumentException("instance_identity_or_revision");
        return instance;
    }
    public void reviseInstance(Instance instance, int nextDesignRevision) {
        if (instance.designRevision==nextDesignRevision) return;
        var design=design(instance.owner,instance.designId,nextDesignRevision);
        if (instance.revision>=1024) throw new IllegalArgumentException("revision_limit");
        var next=locate(design,instance.target.anchor(),instance.target.rotation());
        // Old owned cells stay explicit air requirements; neither history nor conflicts disappear on revision.
        var input=next.expandedInput(); var entries=input.getAsJsonArray("cells");
        var cells=new HashSet<BlockPos>(); next.cells().forEach(c -> cells.add(c.pos()));
        var inverse=switch (next.rotation()) {
            case 90 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            case 180 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 270 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };
        for (var pos:instance.placed.keySet()) if (!cells.contains(pos)) {
            var item=BuildTarget.coordinates(pos.subtract(next.anchor()).rotate(inverse));
            item.addProperty("block","minecraft:air"); entries.add(item);
        }
        var target=BuildTarget.parse(input);
        if (instances.values().stream().anyMatch(i -> i!=instance && i.dimension.equals(instance.dimension)
                && i.target.cells().stream().anyMatch(c -> target.index().containsKey(c.pos()))))
            throw new IllegalArgumentException("overlapping_instance");
        instance.designRevision=nextDesignRevision; instance.revision++; instance.target=target; save();
    }
    public boolean protects(String dimension, BlockPos pos) {
        if (!healthy) return true;
        return instances.values().stream().filter(i -> i.dimension.equals(dimension)).anyMatch(i ->
                i.placed.containsKey(pos) || i.placed.containsKey(pos.above()));
    }
    public boolean belongs(Instance instance, String dimension, BlockPos pos) {
        if (instance==null || !instance.dimension.equals(dimension)) return false;
        return instance.target.index().containsKey(pos);
    }
    public boolean mayChange(Instance instance, BlockPos pos, BuildSite.Access access) {
        if (!healthy || instance==null) return false;
        var actual=access.state(pos);
        if (actual==null) return false;
        if (actual.isAir()) return true;
        var record=instance.placed.get(pos);
        return record!=null && record.intact(access);
    }
    public boolean hasCapacity(Instance instance, BlockPos pos) {
        return healthy && instance.actions.size()<actionLimit(instance)
                && (instance.placed.containsKey(pos) || instance.placed.size()<BuildTarget.LIMIT)
                && serializedBytes < FILE_LIMIT-8192;
    }
    public boolean hasCapacity(Instance instance, Collection<BlockPos> positions) {
        var combined = new HashSet<>(instance.placed.keySet());
        combined.addAll(positions);
        return healthy && instance.actions.size() + positions.size() <= actionLimit(instance)
                && combined.size() <= BuildTarget.LIMIT && serializedBytes < FILE_LIMIT - 8192;
    }
    private static int actionLimit(Instance instance) {
        return Math.max(Math.max(ACTION_LIMIT, instance.target.cells().size() * 4),instance.actions.size());
    }
    public void placedObject(Instance instance, BlockPos primary, Map<BlockPos, BlockState> before,
                             Map<BlockPos, BlockState> after, int consumed, BuildSite.Access access) {
        boolean changed=false;
        for (var pos : before.keySet()) {
            var previous = before.get(pos); var actual = after.get(pos);
            if (previous == null || actual == null || previous == actual) continue;
            changed=true;
            action(instance,pos,"place",previous,actual,pos.equals(primary) ? consumed : 0);
            // One consumed item can own both native cells, but never an existing or substituted block.
            var expected = BuildObjects.at(instance.target,pos);
            if (consumed == 1 && previous.isAir() && !actual.isAir()
                    && expected != null && expected.matches(actual))
                instance.placed.put(pos.immutable(),new Placed(pos,actual,access.chunk(pos),access.watch(pos)));
        }
        if (changed) persistEffect();
    }
    public void removedObject(Instance instance, Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> after) {
        boolean changed=false;
        for (var pos : before.keySet()) {
            var previous = before.get(pos); var actual = after.get(pos);
            if (previous == null || actual == null || previous == actual) continue;
            changed=true;
            action(instance,pos,"break",previous,actual,0);
            var record = instance.placed.get(pos);
            if (record != null && record.witness != null) record.witness.invalidate();
        }
        if (changed) persistEffect();
    }
    public void placed(Instance instance, BlockPos pos, BlockState before, BlockState after,
                       int consumed, BuildSite.Access access) {
        if (before==null || after==null || before==after) return;
        action(instance,pos,"place",before,after,consumed);
        if (before.isAir() && !after.isAir() && consumed==1)
            instance.placed.put(pos.immutable(),new Placed(pos,after,access.chunk(pos),access.watch(pos)));
        persistEffect();
    }
    public void removed(Instance instance, BlockPos pos, BlockState before, BlockState after) {
        if (before==null || after==null || before==after) return;
        action(instance,pos,"break",before,after,0);
        // Retain an invalid tombstone so placement cannot recycle provenance capacity.
        var record=instance.placed.get(pos);
        if (record!=null && record.witness!=null) record.witness.invalidate();
        persistEffect();
    }
    private void action(Instance instance, BlockPos pos, String kind, BlockState before, BlockState after, int consumed) {
        var event=BuildTarget.coordinates(pos); event.addProperty("sequence",instance.actions.size()+1);
        event.addProperty("operation",kind); event.addProperty("instance_revision",instance.revision);
        event.add("before",BuildTarget.exactState(before)); event.add("after",BuildTarget.exactState(after));
        event.addProperty("consumed",consumed); instance.actions.add(event);
    }
    /** Only a source proven before native use may receive a refreshed witness afterward. */
    public void interacted(Instance instance,Map<BlockPos,BlockState> before,Map<BlockPos,BlockState> after,
                           Set<BlockPos> owned,BuildSite.Access access) {
        boolean changed=false;
        for (var pos:before.keySet()) {
            var previous=before.get(pos); var actual=after.get(pos);
            if (previous==null || actual==null || previous==actual) continue;
            action(instance,pos,"interact",previous,actual,0);
            changed=true;
            if (owned.contains(pos) && actual.is(previous.getBlock()))
                instance.placed.put(pos.immutable(),new Placed(pos,actual,access.chunk(pos),access.watch(pos)));
        }
        if (changed) persistEffect();
    }
    private void persistEffect() {
        try { save(); } catch (IllegalStateException failure) { healthy=false; }
    }
    public JsonObject queryDesign(UUID owner, String id, int expected, int offset, Integer layer) {
        var design=design(owner,id,expected);
        var result=new JsonObject(); result.addProperty("design_id",id); result.addProperty("design_revision",design.revision);
        result.addProperty("content_revision",design.target.revision()); result.addProperty("cell_count",design.target.cells().size());
        var layers=new TreeSet<Integer>(); design.target.cells().forEach(c -> layers.add(c.pos().getY()));
        var ys=new JsonArray(); layers.forEach(ys::add); result.add("layers",ys);
        var filtered=design.target.cells().stream().filter(c->layer==null || c.pos().getY()==layer).toList();
        if (offset%16!=0 || offset<0 || offset>=filtered.size() && offset!=0) throw new IllegalArgumentException("page");
        var page=new JsonArray(); filtered.stream().skip(offset).limit(16).map(design.target::relativeCell).forEach(page::add);
        result.add("cells",page); result.addProperty("next_offset",offset+page.size()<filtered.size()?offset+page.size():-1);
        return result;
    }
    public JsonObject queryInstance(Instance instance, BuildSite.Access access, int offset) {
        var result=new JsonObject(); result.addProperty("world_id",worldId.toString());
        result.addProperty("instance_id",instance.id); result.addProperty("instance_revision",instance.revision);
        result.addProperty("design_id",instance.designId); result.addProperty("design_revision",instance.designRevision);
        result.addProperty("target_revision",instance.target.revision()); result.addProperty("dimension",instance.dimension);
        result.add("anchor",BuildTarget.coordinates(instance.target.anchor())); result.addProperty("rotation",instance.target.rotation());
        result.addProperty("recording_known",healthy); result.addProperty("action_count",instance.actions.size());
        result.addProperty("automatic_resume",false);
        var reference=new JsonObject(); reference.addProperty("instance_id",instance.id);
        reference.addProperty("instance_revision",instance.revision); result.add("target",reference);
        var records=new ArrayList<>(instance.placed.values());
        if (offset%16!=0 || offset<0 || offset>=records.size() && offset!=0) throw new IllegalArgumentException("page");
        var page=new JsonArray(); int conflicts=0, unknown=0;
        for (int index=0;index<records.size();index++) {
            var record=records.get(index); var actual=access.state(record.pos);
            String status=actual==null ? "unknown" : actual.isAir() ? "absent"
                    : record.intact(access) ? "intact" : "conflict";
            if (status.equals("conflict")) conflicts++; if (status.equals("unknown")) unknown++;
            if (index<offset || index>=offset+16) continue;
            var row=BuildTarget.coordinates(record.pos); row.addProperty("status",status);
            row.add("recorded_state",BuildTarget.exactState(record.state)); page.add(row);
        }
        result.add("records",page); result.addProperty("record_count",records.size());
        result.addProperty("conflicts",conflicts); result.addProperty("unknown",unknown);
        result.addProperty("next_offset",offset+page.size()<records.size()?offset+page.size():-1);
        return result;
    }
    public JsonObject list(UUID owner, UUID builder, String dimension, boolean design, int offset) {
        requireHealthy();
        var values=new ArrayList<JsonObject>();
        if (design) for (var d:designs.values()) { if (!d.owner.equals(owner)) continue;
            var row=new JsonObject(); row.addProperty("design_id",d.id); row.addProperty("design_revision",d.revision);
            row.addProperty("cell_count",d.target.cells().size()); values.add(row); }
        else for (var i:instances.values()) { if (!i.owner.equals(owner) || !i.builder.equals(builder)
                || !i.dimension.equals(dimension)) continue;
            var row=new JsonObject(); row.addProperty("instance_id",i.id); row.addProperty("instance_revision",i.revision);
            row.addProperty("design_id",i.designId); row.addProperty("design_revision",i.designRevision); values.add(row); }
        if (offset%16!=0 || offset<0 || offset>=values.size() && offset!=0) throw new IllegalArgumentException("page");
        var page=new JsonArray(); values.stream().skip(offset).limit(16).forEach(page::add);
        var result=new JsonObject(); result.add("entries",page);
        result.addProperty("next_offset",offset+page.size()<values.size()?offset+page.size():-1); return result;
    }
    private JsonObject encode() {
        var root=new JsonObject(); root.addProperty("format",1); root.addProperty("world_id",worldId.toString());
        var ds=new JsonArray();
        for (var d:designs.values()) {
            var row=new JsonObject(); row.addProperty("owner",d.owner.toString()); row.addProperty("id",d.id);
            row.addProperty("revision",d.revision); row.add("target",d.target.input()); ds.add(row);
        }
        root.add("designs",ds); var buildings=new JsonArray();
        for (var i:instances.values()) {
            var row=new JsonObject(); row.addProperty("id",i.id); row.addProperty("owner",i.owner.toString());
            row.addProperty("builder",i.builder.toString()); row.addProperty("dimension",i.dimension);
            row.addProperty("revision",i.revision); row.addProperty("design_id",i.designId);
            row.addProperty("design_revision",i.designRevision); row.add("target",i.target.input());
            var records=new JsonArray();
            for (var p:i.placed.values()) { var entry=BuildTarget.coordinates(p.pos);
                entry.add("state",BuildTarget.exactState(p.state)); records.add(entry); }
            row.add("placed",records); row.add("actions",i.actions); buildings.add(row);
        }
        root.add("instances",buildings); return root;
    }
    private void decode(JsonObject root) {
        BuildTarget.keys(root,Set.of("format","world_id","designs","instances"));
        if (BuildTarget.integer(root.get("format"),1,1)!=1) throw new IllegalArgumentException();
        worldId=UUID.fromString(root.get("world_id").getAsString());
        var ds=root.getAsJsonArray("designs"); var buildings=root.getAsJsonArray("instances");
        if (ds.size()>ENTRY_LIMIT || buildings.size()>ENTRY_LIMIT) throw new IllegalArgumentException();
        for (var value:ds) {
            var row=value.getAsJsonObject(); var owner=UUID.fromString(row.get("owner").getAsString()); String id=name(row.get("id"));
            BuildTarget.keys(row,Set.of("owner","id","revision","target"));
            var target=BuildTarget.parse(row.getAsJsonObject("target"));
            if (!target.anchor().equals(BlockPos.ZERO) || target.rotation()!=0) throw new IllegalArgumentException();
            var design=new Design(owner,id,BuildTarget.integer(row.get("revision"),1,1024),target);
            if (designs.put(key(owner,id),design)!=null) throw new IllegalArgumentException();
        }
        for (var value:buildings) {
            var row=value.getAsJsonObject(); String id=UUID.fromString(row.get("id").getAsString()).toString();
            BuildTarget.keys(row,Set.of("id","owner","builder","dimension","revision","design_id","design_revision","target","placed","actions"));
            var owner=UUID.fromString(row.get("owner").getAsString()); String designId=name(row.get("design_id"));
            if (!designs.containsKey(key(owner,designId))) throw new IllegalArgumentException();
            var instance=new Instance(id,owner,UUID.fromString(row.get("builder").getAsString()),
                    BuildTarget.string(row.get("dimension"),128),designId,BuildTarget.integer(row.get("revision"),1,1024),
                    BuildTarget.integer(row.get("design_revision"),1,1024),BuildTarget.parse(row.getAsJsonObject("target")));
            if (instances.put(id,instance)!=null) throw new IllegalArgumentException();
            var records=row.getAsJsonArray("placed"); var actions=row.getAsJsonArray("actions");
            if (records.size()>BuildTarget.LIMIT || actions.size()>BuildTarget.LIMIT*4) throw new IllegalArgumentException();
            for (var record:records) {
                var entry=record.getAsJsonObject(); var pos=position(entry);
                BuildTarget.keys(entry,Set.of("x","y","z","state"));
                if (!belongs(instance,instance.dimension,pos)) throw new IllegalArgumentException();
                var cell=entry.getAsJsonObject("state").deepCopy(); cell.add("x",new JsonPrimitive(0));
                cell.addProperty("y",0); cell.addProperty("z",0); var cells=new JsonArray(); cells.add(cell);
                var target=new JsonObject(); target.add("cells",cells);
                var state=definition(target).cells().getFirst().authored();
                if (instance.placed.put(pos,new Placed(pos,state,null,null))!=null) throw new IllegalArgumentException();
            }
            int sequence=0;
            for (var action:actions) {
                var event=action.getAsJsonObject();
                BuildTarget.keys(event,Set.of("x","y","z","sequence","operation","instance_revision","before","after","consumed"));
                position(event);
                if (BuildTarget.integer(event.get("sequence"),1,BuildTarget.LIMIT*4)!=++sequence
                        || !Set.of("place","break","interact").contains(BuildTarget.string(event.get("operation"),8)))
                    throw new IllegalArgumentException("action_history");
                BuildTarget.integer(event.get("instance_revision"),1,instance.revision);
                BuildTarget.integer(event.get("consumed"),0,64);
                for (String side:List.of("before","after")) {
                    var state=event.getAsJsonObject(side).deepCopy();
                    state.addProperty("x",0); state.addProperty("y",0); state.addProperty("z",0);
                    var entries=new JsonArray(); entries.add(state); var input=new JsonObject(); input.add("cells",entries);
                    definition(input);
                }
            }
            instance.actions.addAll(actions.deepCopy());
        }
    }
}
