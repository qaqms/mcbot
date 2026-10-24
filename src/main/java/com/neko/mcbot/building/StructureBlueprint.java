package com.neko.mcbot.building;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

/** A single bounded interchange format. It produces ordinary B1 data and never edits the world. */
public final class StructureBlueprint {
    public static final int COMPRESSED_LIMIT=65536, EXPANDED_LIMIT=131072, HEAP_LIMIT=262144, DEPTH_LIMIT=16;
    public static final int PALETTE_LIMIT=64, DIRECTORY_LIMIT=128, VOLUME_LIMIT=4096, CELL_LIMIT=128;
    public static final long MAX_NANOS=200_000_000L;
    private final Path directory;
    private final LongSupplier clock;
    public StructureBlueprint(Path worldRoot) { this(worldRoot,System::nanoTime); }
    public StructureBlueprint(Path worldRoot,LongSupplier clock) {
        directory=worldRoot.resolve("mcbot").resolve("blueprints"); this.clock=clock;
    }
    private void check(long started) {
        if (clock.getAsLong()-started>=MAX_NANOS) throw new IllegalArgumentException("parse_time_limit");
    }
    private boolean directoryAvailable() throws IOException {
        if (Files.isSymbolicLink(directory.getParent()) || Files.isSymbolicLink(directory))
            throw new IOException("blueprint_directory");
        if (!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) throw new IOException("blueprint_directory");
        return true;
    }
    public JsonObject list(int offset) throws IOException {
        long started=clock.getAsLong();
        var result=new JsonObject(); var names=new ArrayList<String>(); int scanned=0, ignored=0;
        boolean available=directoryAvailable();
        if (available) try (var files=Files.newDirectoryStream(directory)) {
            for (var file:files) {
                check(started);
                if (++scanned>DIRECTORY_LIMIT) throw new IllegalArgumentException("directory_limit");
                String filename=file.getFileName().toString();
                if (!filename.endsWith(".nbt") || Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) { ignored++; continue; }
                String name=filename.substring(0,filename.length()-4);
                try { BuildRecords.name(new JsonPrimitive(name)); names.add(name); }
                catch (RuntimeException invalid) { ignored++; }
            }
        }
        names.sort(String::compareTo);
        if (offset<0 || offset%16!=0 || offset>=names.size() && offset!=0) throw new IllegalArgumentException("page");
        var entries=new JsonArray(); names.stream().skip(offset).limit(16).forEach(entries::add);
        result.add("names",entries); result.addProperty("directory_available",available);
        result.addProperty("ignored_files",ignored); result.addProperty("next_offset",offset+entries.size()<names.size()?offset+entries.size():-1);
        result.addProperty("format","vanilla_structure_gzip_nbt"); return result;
    }

    public record Inspection(String sourceRevision, BuildTarget target, JsonObject report) {
        public boolean importable() { return report.get("importable").getAsBoolean(); }
    }
    public Inspection inspect(String name) throws IOException {
        BuildRecords.name(new JsonPrimitive(name));
        long started=clock.getAsLong();
        if (!directoryAvailable()) throw new FileNotFoundException("blueprint_directory");
        Path file=directory.resolve(name+".nbt");
        var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.size()>COMPRESSED_LIMIT)
            throw new IOException("blueprint_file");
        byte[] bytes;
        try (var input=new Limited(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),COMPRESSED_LIMIT,started)) {
            bytes=input.readAllBytes();
        }
        var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(before.fileKey(),after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || before.size()!=after.size() || after.isSymbolicLink() || !directoryAvailable())
            throw new IOException("blueprint_changed");
        return decode(bytes,started);
    }

    private Inspection decode(byte[] bytes,long started) throws IOException {
        if (bytes.length>COMPRESSED_LIMIT) throw new IOException("compressed_limit");
        CompoundTag root;
        int expanded;
        try (var gzip=new GZIPInputStream(new ByteArrayInputStream(bytes));
             var limited=new Limited(gzip,EXPANDED_LIMIT,started);
             var input=new DataInputStream(limited)) {
            root=NbtIo.read(input,new NbtAccounter(HEAP_LIMIT,DEPTH_LIMIT));
            if (input.read()!=-1) throw new IOException("trailing_nbt");
            expanded=limited.count;
        }
        check(started);
        keys(root,Set.of("size","palette","blocks","entities","DataVersion","author"));
        if (root.contains("DataVersion") && !(root.get("DataVersion") instanceof IntTag))
            throw new IllegalArgumentException("data_version_type");
        if (root.contains("author") && (!(root.get("author") instanceof StringTag text) || text.value().length()>128))
            throw new IllegalArgumentException("author_type");
        var size=vector(root.get("size"));
        if (size.getX()<1 || size.getY()<1 || size.getZ()<1 || size.getX()>33 || size.getY()>33 || size.getZ()>33
                || (long)size.getX()*size.getY()*size.getZ()>VOLUME_LIMIT) throw new IllegalArgumentException("dimensions");
        var palette=list(root.get("palette")); var blocks=list(root.get("blocks"));
        var entities=root.contains("entities") ? list(root.get("entities")) : new ListTag();
        if (palette.isEmpty() || palette.size()>PALETTE_LIMIT || blocks.isEmpty() || blocks.size()>CELL_LIMIT
                || entities.size()>CELL_LIMIT) throw new IllegalArgumentException("collection_limit");
        var entries=new ArrayList<JsonObject>();
        var unknownPalette=new HashSet<Integer>();
        for (int index=0;index<palette.size();index++) {
            check(started);
            var value=compound(palette.get(index)); keys(value,Set.of("Name","Properties"));
            if (!(value.get("Name") instanceof StringTag id) || id.value().length()>128)
                throw new IllegalArgumentException("palette_name");
            var cell=BuildTarget.coordinates(BlockPos.ZERO); cell.addProperty("block",id.value());
            var properties=new JsonArray();
            if (value.contains("Properties")) {
                var state=compound(value.get("Properties"));
                if (state.size()>8) throw new IllegalArgumentException("properties_limit");
                for (var pair:state.entrySet()) {
                    if (pair.getKey().length()>32 || !(pair.getValue() instanceof StringTag text) || text.value().length()>32)
                        throw new IllegalArgumentException("property_type");
                    var property=new JsonObject(); property.addProperty("name",pair.getKey());
                    property.addProperty("value",text.value()); properties.add(property);
                }
            }
            cell.add("properties",properties);
            // Resolve all palette entries through the existing registry/state parser, even unused entries.
            try { BuildRecords.definition(definition(cell)); }
            catch (RuntimeException invalid) { unknownPalette.add(index); }
            entries.add(cell);
        }
        var cells=new JsonArray(); var seen=new HashSet<BlockPos>();
        int invalidCells=0, unsupported=0, payloadCells=0;
        var diagnostics=new JsonArray();
        for (var entry:blocks) {
            check(started);
            var block=compound(entry); keys(block,Set.of("pos","state","nbt"));
            var pos=vector(block.get("pos"));
            if (pos.getX()<0 || pos.getY()<0 || pos.getZ()<0
                    || pos.getX()>=size.getX() || pos.getY()>=size.getY() || pos.getZ()>=size.getZ()
                    || !seen.add(pos)) throw new IllegalArgumentException("cell_coordinate_or_duplicate");
            if (!(block.get("state") instanceof IntTag index)) throw new IllegalArgumentException("palette_index_type");
            int paletteIndex=index.value();
            if (block.contains("nbt")) { compound(block.get("nbt")); payloadCells++; }
            if (paletteIndex<0 || paletteIndex>=entries.size() || unknownPalette.contains(paletteIndex)) {
                invalidCells++; diagnostic(diagnostics,pos,"invalid_palette_or_state"); continue;
            }
            var cell=entries.get(paletteIndex).deepCopy();
            cell.addProperty("x",pos.getX()); cell.addProperty("y",pos.getY()); cell.addProperty("z",pos.getZ());
            cells.add(cell);
        }
        BuildTarget target=null;
        if (!cells.isEmpty()) { var definition=new JsonObject(); definition.add("cells",cells); target=BuildRecords.definition(definition); }
        if (target!=null) for (var cell:target.cells()) {
            check(started);
            var material=target.material(cell);
            if (!material.supported()) { unsupported++; diagnostic(diagnostics,cell.pos(),material.reason()); }
        }
        String revision;
        try { revision=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var report=new JsonObject(); report.addProperty("format","vanilla_structure_gzip_nbt");
        report.addProperty("source_revision",revision); report.add("size",BuildTarget.coordinates(size));
        report.addProperty("compressed_bytes",bytes.length); report.addProperty("expanded_bytes",expanded);
        report.addProperty("cells",blocks.size()); report.addProperty("palette_entries",palette.size());
        report.addProperty("invalid_cells",invalidCells); report.addProperty("invalid_palette_entries",unknownPalette.size());
        report.addProperty("unsupported_cells",unsupported); report.addProperty("payload_cells",payloadCells);
        report.addProperty("entity_count",entities.size()); report.add("diagnostics",diagnostics);
        report.addProperty("diagnostics_truncated",invalidCells+unsupported>diagnostics.size());
        boolean importable=target!=null && invalidCells==0 && unknownPalette.isEmpty() && unsupported==0
                && payloadCells==0 && entities.isEmpty();
        report.addProperty("importable",importable); report.addProperty("world_changed",false);
        report.addProperty("goal_satisfied",false);
        report.addProperty("air_semantics","explicit_air_is_clear_requirement_unspecified_cells_untouched");
        if (target!=null) {
            report.addProperty("content_revision",target.revision());
            var bill=new TreeMap<String,Integer>();
            for (var cell:target.cells()) if (cell.material().item()!=null)
                bill.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(cell.material().item()).toString(),
                        cell.material().units(),Integer::sum);
            var materials=new JsonArray();
            for (var item:bill.entrySet()) { if (materials.size()>=16) break; var row=new JsonObject();
                row.addProperty("item",item.getKey()); row.addProperty("design_required",item.getValue()); materials.add(row); }
            report.add("materials",materials); report.addProperty("material_types",bill.size());
            report.addProperty("materials_truncated",bill.size()>16);
        }
        check(started);
        return new Inspection(revision,target,report);
    }
    private static JsonObject definition(JsonObject cell) {
        var cells=new JsonArray(); cells.add(cell); var definition=new JsonObject(); definition.add("cells",cells); return definition;
    }
    private static void diagnostic(JsonArray entries,BlockPos pos,String cause) {
        if (entries.size()>=8) return;
        var entry=BuildTarget.coordinates(pos); entry.addProperty("cause",cause); entries.add(entry);
    }
    private static void keys(CompoundTag tag,Set<String> allowed) {
        if (!allowed.containsAll(tag.keySet())) throw new IllegalArgumentException("nbt_fields");
    }
    private static CompoundTag compound(Tag value) {
        if (!(value instanceof CompoundTag compound)) throw new IllegalArgumentException("compound_type");
        return compound;
    }
    private static ListTag list(Tag value) {
        if (!(value instanceof ListTag list)) throw new IllegalArgumentException("list_type");
        return list;
    }
    private static BlockPos vector(Tag value) {
        var list=list(value);
        if (list.size()!=3 || !(list.get(0) instanceof IntTag x) || !(list.get(1) instanceof IntTag y)
                || !(list.get(2) instanceof IntTag z)) throw new IllegalArgumentException("vector_type");
        return new BlockPos(x.value(),y.value(),z.value());
    }
    private final class Limited extends FilterInputStream {
        private final int limit; private final long started; private int count;
        Limited(InputStream input,int limit,long started) { super(input); this.limit=limit; this.started=started; }
        private int accounted(int amount) throws IOException {
            check(started); if (amount>0 && (count+=amount)>limit) throw new IOException("byte_limit"); return amount;
        }
        @Override public int read() throws IOException {
            check(started); int value=in.read(); accounted(value<0 ? 0 : 1); return value;
        }
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            check(started);
            return accounted(in.read(bytes,offset,Math.min(length,Math.max(1,limit-count+1))));
        }
        @Override public long skip(long amount) throws IOException {
            byte[] bytes=new byte[512]; long skipped=0;
            while (skipped<amount) { int got=read(bytes,0,(int)Math.min(bytes.length,amount-skipped)); if (got<0) break; skipped+=got; }
            return skipped;
        }
    }
}
