package com.neko.mcbot.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neko.mcbot.common.ResourceLocationHelper;
import com.neko.mcbot.server.BlockPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Bounded target data only: unspecified cells and unspecified state properties impose no requirement. */
public record BuildTarget(BlockPos anchor, int rotation, List<Cell> cells, String revision) {
    public static final int LIMIT = 128;

    public record Material(Item item, int units, boolean supported, String reason) { }

    public record Cell(BlockPos pos, BlockState authored, Set<String> properties, Rotation turn) {
        public Cell {
            pos = pos.immutable();
            properties = Set.copyOf(properties);
        }

        public BlockState desired() { return authored.rotate(turn); }

        public boolean matches(BlockState actual) {
            if (actual == null || actual.getBlock() != authored.getBlock()) return false;
            var local = unrotate(actual);
            for (String name : properties) {
                var property = authored.getBlock().getStateDefinition().getProperty(name);
                if (!authored.getValue(property).equals(local.getValue(property))) return false;
            }
            return true;
        }

        private BlockState unrotate(BlockState actual) {
            Rotation inverse = switch (turn) {
                case NONE -> Rotation.NONE;
                case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
                case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
                case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            };
            return actual.rotate(inverse);
        }

        public JsonObject actualProperties(BlockState actual) {
            var data = new JsonObject();
            var local = unrotate(actual);
            for (String name : new java.util.TreeSet<>(properties)) {
                var property = local.getBlock().getStateDefinition().getProperty(name);
                if (property != null) data.addProperty(name, value(local, property));
            }
            return data;
        }

        /** This rule is deliberately restricted to the same single-cell native placement families as navigation. */
        public Material material() {
            if (authored.isAir()) return new Material(null, 0, true, "");
            Item item = authored.getBlock().asItem();
            if (!(item instanceof BlockItem blockItem) || blockItem.getBlock() != authored.getBlock()
                    || !BlockPlacement.supported(blockItem)
                    || !BlockPlacement.projectable(blockItem, new ItemStack(item)))
                return new Material(null, 0, false, "special_or_multicell_block");
            if (authored.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && authored.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE)
                return new Material(item, 2, false, "multiple_native_placements_required");
            if (properties.contains("waterlogged") && authored.getValue(BlockStateProperties.WATERLOGGED))
                return new Material(null, 0, false, "fluid_requirement_not_supported");
            return new Material(item, 1, true, "");
        }

        public JsonObject describe() {
            var data = new JsonObject();
            data.addProperty("x", pos.getX()); data.addProperty("y", pos.getY()); data.addProperty("z", pos.getZ());
            data.addProperty("block", BuiltInRegistries.BLOCK.getKey(authored.getBlock()).toString());
            var requirements = new JsonObject();
            for (String name : new java.util.TreeSet<>(properties)) {
                var property = authored.getBlock().getStateDefinition().getProperty(name);
                requirements.addProperty(name, value(authored, property));
            }
            data.add("authored_properties", requirements);
            data.addProperty("properties_frame", "before_rotation");
            return data;
        }
    }

    public BuildTarget {
        anchor = anchor.immutable();
        cells = List.copyOf(cells);
    }

    public JsonObject input() {
        var result = new JsonObject();
        result.add("anchor", coordinates(anchor));
        result.addProperty("rotation", rotation);
        var entries = new com.google.gson.JsonArray();
        Rotation inverse = switch (rotation) {
            case 90 -> Rotation.COUNTERCLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };
        for (var cell : cells) {
            var entry = coordinates(cell.pos().subtract(anchor).rotate(inverse));
            entry.addProperty("block", BuiltInRegistries.BLOCK.getKey(cell.authored().getBlock()).toString());
            var properties = new com.google.gson.JsonArray();
            for (String name : new java.util.TreeSet<>(cell.properties())) {
                var pair = new JsonObject();
                pair.addProperty("name", name);
                pair.addProperty("value", value(cell.authored(), cell.authored().getBlock().getStateDefinition().getProperty(name)));
                properties.add(pair);
            }
            entry.add("properties", properties);
            entries.add(entry);
        }
        result.add("cells", entries);
        return result;
    }

    public static JsonObject coordinates(BlockPos pos) {
        var out = new JsonObject();
        out.addProperty("x", pos.getX()); out.addProperty("y", pos.getY()); out.addProperty("z", pos.getZ());
        return out;
    }

    public static JsonObject exactState(BlockState state) {
        var out = new JsonObject();
        out.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var properties = new com.google.gson.JsonArray();
        for (var property : state.getProperties()) {
            var pair = new JsonObject();
            pair.addProperty("name", property.getName());
            pair.addProperty("value", value(state, property));
            properties.add(pair);
        }
        out.add("properties", properties);
        return out;
    }

    public static BuildTarget parse(JsonObject input) {
        keys(input, Set.of("anchor", "rotation", "cells", "fill"));
        BlockPos anchor = position(input.getAsJsonObject("anchor"), 29_999_984, false);
        int rotation = input.has("rotation") ? integer(input.get("rotation"), 0, 270) : 0;
        Rotation turn = switch (rotation) {
            case 0 -> Rotation.NONE;
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException("rotation");
        };
        if (input.has("cells") == input.has("fill")) throw new IllegalArgumentException("cells_or_fill");
        var cells = new ArrayList<Cell>();
        if (input.has("cells")) {
            var entries = input.getAsJsonArray("cells");
            if (entries.isEmpty() || entries.size() > LIMIT) throw new IllegalArgumentException("cell_limit");
            for (var entry : entries) {
                var cell = entry.getAsJsonObject();
                keys(cell, Set.of("x", "y", "z", "block", "properties"));
                add(cells, anchor, turn, positionAxes(cell, 32), cell);
            }
        } else {
            var fill = input.getAsJsonObject("fill");
            keys(fill, Set.of("from", "to", "block", "properties"));
            var from = position(fill.getAsJsonObject("from"), 32, true);
            var to = position(fill.getAsJsonObject("to"), 32, true);
            long volume = (to.getX() - (long) from.getX() + 1) * (to.getY() - (long) from.getY() + 1)
                    * (to.getZ() - (long) from.getZ() + 1);
            if (to.getX() < from.getX() || to.getY() < from.getY() || to.getZ() < from.getZ()
                    || volume <= 0 || volume > LIMIT) throw new IllegalArgumentException("fill_limit");
            for (int x = from.getX(); x <= to.getX(); x++)
                for (int y = from.getY(); y <= to.getY(); y++)
                    for (int z = from.getZ(); z <= to.getZ(); z++)
                        add(cells, anchor, turn, new BlockPos(x, y, z), fill);
        }
        cells.sort(Comparator.comparingInt((Cell c) -> c.pos().getY())
                .thenComparingInt(c -> c.pos().getX()).thenComparingInt(c -> c.pos().getZ()));
        if (cells.stream().map(Cell::pos).distinct().count() != cells.size())
            throw new IllegalArgumentException("duplicate_cell");
        var canonical = new JsonObject();
        canonical.addProperty("anchor", anchor.toShortString());
        canonical.addProperty("rotation", rotation);
        var values = new com.google.gson.JsonArray();
        cells.forEach(c -> values.add(c.describe()));
        canonical.add("cells", values);
        try {
            String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
            return new BuildTarget(anchor, rotation, cells, revision);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void add(List<Cell> cells, BlockPos anchor, Rotation turn, BlockPos relative, JsonObject input) {
        String id = string(input.get("block"), 128);
        var holder = BuiltInRegistries.BLOCK.<Block>get(ResourceLocationHelper.of(id));
        if (holder.isEmpty()) throw new IllegalArgumentException("block");
        BlockState state = holder.get().value().defaultBlockState();
        var authored = new TreeMap<String, String>();
        if (input.has("properties")) {
            var props = input.getAsJsonArray("properties");
            if (props.size() > 8) throw new IllegalArgumentException("properties_limit");
            for (var entry : props) {
                var pair = entry.getAsJsonObject();
                keys(pair, Set.of("name", "value"));
                String name = string(pair.get("name"), 32);
                String text = string(pair.get("value"), 32);
                if (authored.containsKey(name)) throw new IllegalArgumentException("duplicate_property");
                var property = state.getBlock().getStateDefinition().getProperty(name);
                if (property == null) throw new IllegalArgumentException("property");
                state = set(state, property, text);
                authored.put(name, text);
            }
        }
        BlockPos rotated = relative.rotate(turn);
        BlockPos pos = anchor.offset(rotated);
        if (Math.abs((long) pos.getX()) > 29_999_984 || Math.abs((long) pos.getZ()) > 29_999_984
                || pos.getY() < -2048 || pos.getY() > 2047) throw new IllegalArgumentException("world_coordinate");
        cells.add(new Cell(pos, state, authored.keySet(), turn));
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> new IllegalArgumentException("value")));
    }
    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
    private static BlockPos position(JsonObject value, int limit, boolean relative) {
        keys(value, Set.of("x", "y", "z"));
        var pos = positionAxes(value, limit);
        if (!relative && (pos.getY() < -2048 || pos.getY() > 2047)) throw new IllegalArgumentException("anchor_height");
        return pos;
    }
    private static BlockPos positionAxes(JsonObject value, int limit) {
        return new BlockPos(integer(value.get("x"), -limit, limit),
                integer(value.get("y"), -limit, limit), integer(value.get("z"), -limit, limit));
    }
    public static int integer(JsonElement value, int min, int max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("integer");
        int number = value.getAsBigDecimal().intValueExact();
        if (number < min || number > max) throw new IllegalArgumentException("integer_range");
        return number;
    }
    public static String string(JsonElement value, int max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank() || value.getAsString().length() > max)
            throw new IllegalArgumentException("string");
        return value.getAsString();
    }
    public static void keys(JsonObject value, Set<String> keys) {
        if (value == null || !keys.containsAll(value.keySet())) throw new IllegalArgumentException("fields");
    }
}
