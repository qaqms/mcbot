package com.neko.mcbot.path;

import com.neko.mcbot.server.PathMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * 服务器世界 → DigSampler 的翻译层：这里承载全部"不许踩的线"——
 * 神圣方块（容器/工作台/床/机关类，含其正下方支撑）、岩浆邻接否决（HAZARD）、
 * 起点脚下不挖（防自塌）、生存不可破坏集=不可挖、当前手持挖不动=不可挖。
 * 挖掘耗时按"同伴当前手持"的真实速率折算成代价秒，搜索因此天然偏好
 * "绕开硬石头、走向用镐挖得动的软块"。
 */
public final class LevelDigSampler implements DigSampler {

    /** 搜索盒：以起点计的水平/垂直半径（超出判不可达，逼模型分段）。 */
    public static final int RADIUS_XZ = 64;
    public static final int RADIUS_Y = 32;
    public static final double MAX_DIG_SECONDS = 20.0; // 单格超过这个秒数视为"不值得挖"

    /** 注册路径名命中即神圣（BlockEntity 检查兜不住的装饰性/机关方块在这里补）。 */
    private static final Set<String> SACRED_NAMES = Set.of(
            "chest", "trapped_chest", "barrel", "ender_chest", "shulker_box",
            "anvil", "chipped_anvil", "damaged_anvil", "grindstone", "stonecutter",
            "jukebox", "bell", "beacon", "enchanting_table", "brewing_stand",
            "cauldron", "lectern", "composter", "cake", "observer", "target",
            "turtle_egg", "sniffer_egg");

    private final ServerLevel level;
    private final ServerPlayer companion;
    private final BlockPos start;
    private final int minY;
    private final int maxY;
    private final int placeStock;

    public LevelDigSampler(ServerLevel level, ServerPlayer companion, BlockPos start,
                           int placeStock) {
        this.level = level;
        this.companion = companion;
        this.start = start;
        this.placeStock = placeStock;
        var dt = level.dimensionType();
        this.minY = dt.minY();
        this.maxY = dt.minY() + dt.height();
    }

    @Override
    public boolean passable(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return false;
        }
        BlockPos p = new BlockPos(x, y, z);
        // R1-S2 修洞（javap 实证）：Level.getBlockState → getChunk(II) 默认
        // ChunkStatus.FULL + create=true —— 对未加载格会**在主线程同步加载/生成区块**。
        // 分帧搜索每节点问上百格，这是比节点帽更大的尖峰源。未加载一律按不可通行（墙）：
        // 目标在未加载区会得到 NO_PATH 而非 BUDGET，语义变化已在设计卡 §开放4 备案。
        if (!level.isLoaded(p)) {
            return false;
        }
        var st = level.getBlockState(p);
        return !st.blocksMotion() && st.getFluidState().isEmpty();
    }

    @Override
    public double digSeconds(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        if (!inBounds(x, y, z) || !level.isLoaded(p)) {
            return INFEASIBLE;
        }
        // 起点脚下不挖：不从自己站的地方开始掘坟
        if (x == start.getX() && z == start.getZ() && y == start.getY() - 1) {
            return INFEASIBLE;
        }
        var st = level.getBlockState(p);
        if (!st.getFluidState().isEmpty()) {
            return INFEASIBLE; // 流体不能挖（要排水得用放置，v1 不提供）
        }
        if (st.isAir()) {
            return 0; // 已经清了
        }
        // 本格不许挖：方块实体/神圣名册/床；它的正上方是神圣方块的支撑也不许挖
        if (level.getBlockEntity(p) != null || isSacred(st) || sacredAbove(p)) {
            return INFEASIBLE;
        }
        if (lavaAdjacent(p)) {
            return INFEASIBLE; // HAZARD：旁边是岩浆，不送
        }
        float hardness = st.getDestroySpeed(level, p);
        if (hardness < 0) {
            return INFEASIBLE; // UNBREAKABLE（基岩/末地石核心等生存不可破）
        }
        if (!companion.hasCorrectToolForDrops(st)
                || !companion.getMainHandItem().canDestroyBlock(st, level, p, companion)) return INFEASIBLE;
        float gain = st.getDestroyProgress(companion, level, p);
        if (Float.isNaN(gain) || gain <= 0) return INFEASIBLE;
        double sec = 1.0 / (gain * 20.0);
        return sec > MAX_DIG_SECONDS ? INFEASIBLE : sec;
    }

    @Override
    public boolean support(int x, int y, int z) {
        if (!inBounds(x, y, z) || !level.isLoaded(new BlockPos(x, y, z))) {
            return false; // 同 passable：不拿支撑查询去触发同步区块生成
        }
        var st = level.getBlockState(new BlockPos(x, y, z));
        // 支撑允许是神圣方块——我们只站上去，不挖它
        return st.blocksMotion() && st.getFluidState().isEmpty();
    }

    @Override
    public boolean placeable(int x, int y, int z) {
        if (!inBounds(x, y, z) || placeStock <= 0) {
            return false;
        }
        BlockPos p = new BlockPos(x, y, z);
        if (!level.isLoaded(p)) {
            return false; // 同上：不在未加载区动放置规划
        }
        var st = level.getBlockState(p);
        if (!st.isAir() || !st.getFluidState().isEmpty()) {
            return false; // 只往干净空气格里放
        }
        // 不在同伴自己身体所占的格子里放（起点=当前身位时防卡模）
        BlockPos mine = companion.blockPosition();
        if (x == mine.getX() && z == mine.getZ()
                && (y == mine.getY() || y == mine.getY() + 1)) {
            return false;
        }
        return !lavaAdjacent(p);
    }

    @Override
    public double placeCost(int x, int y, int z) {
        // 材料越稀缺越贵：剩 1~3 块时基本只在"不搭就无路"时才用
        double scarcity = placeStock <= 3 ? 2.0 : placeStock <= 8 ? 0.6 : 0.2;
        return 0.8 + scarcity;
    }

    @Override
    public int maxPlaces() {
        return placeStock;
    }

    @Override
    public boolean inBounds(int x, int y, int z) {
        if (y < minY + 1 || y > maxY - 4) {
            return false; // 头格之上再留 1 格余量
        }
        return Math.abs(x - start.getX()) <= RADIUS_XZ
                && Math.abs(z - start.getZ()) <= RADIUS_XZ
                && Math.abs(y - start.getY()) <= RADIUS_Y;
    }

    // ---- 供执行器/确认流使用的工具方法 ----

    /** 这格现在是否已清开（执行期复核用）。 */
    public boolean cleared(int x, int y, int z) {
        return passable(x, y, z);
    }

    /** 这格现在的方块名（回执可读性）。 */
    public String blockName(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) return "unloaded";
        var st = level.getBlockState(pos);
        return st.isAir() ? "air" : st.getBlock().getName().getString();
    }

    /** 背包里能当"垫脚石"的方块存量（place 动作的弹药计数）。 */
    public static int countPlaceables(net.minecraft.world.entity.player.Inventory inv) {
        return PathMaterials.count(inv);
    }

    private boolean sacredAbove(BlockPos p) {
        if (!level.isInWorldBounds(p.above()) || !level.isLoaded(p.above())) return true;
        var up = level.getBlockState(p.above());
        if (up.isAir()) {
            return false;
        }
        return level.getBlockEntity(p.above()) != null || isSacred(up);
    }

    /** 注册表反查每 Block 只算一次（javap：BuiltInRegistries.BLOCK.getKey 是哈希查表，
     *  但 memo 命中前的首次+重复邻居仍省掉大量路径字符串分配）。身份键安全：Block 单例。 */
    private static final java.util.Map<Block, Boolean> SACRED_CACHE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private boolean isSacred(BlockState st) {
        return SACRED_CACHE.computeIfAbsent(st.getBlock(), b -> {
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            return path.endsWith("_bed")
                    || path.endsWith("_shulker_box")
                    || SACRED_NAMES.contains(path);
        });
    }

    private boolean lavaAdjacent(BlockPos p) {
        for (int i = 0; i < 6; i++) {
            BlockPos q = p.offset(
                    i == 0 ? 1 : i == 1 ? -1 : 0,
                    i == 2 ? 1 : i == 3 ? -1 : 0,
                    i == 4 ? 1 : i == 5 ? -1 : 0);
            if (!level.isInWorldBounds(q) || !level.isLoaded(q) || level.getBlockState(q).is(Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
