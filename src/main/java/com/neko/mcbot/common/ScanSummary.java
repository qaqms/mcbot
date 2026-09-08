package com.neko.mcbot.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 分层摘要装配器（R2-D）：把"某类某方块在绝对坐标 X"这一条条观测，攒成近/中/远三行回执。
 * 零 MC 依赖，所以层带归组、名额分配、体量上界都能在 JUnit 里钉死。
 *
 * <p>三层的口径差别是刻意的（越远越便宜）：
 * <ul>
 *   <li><b>近环</b>：逐格细列，每个路径给"最近的几格"，模型可以直接下 break_block；</li>
 *   <li><b>中环</b>：每路径给 计数 + 最近一格绝对坐标，模型知道"该往哪走再看"；</li>
 *   <li><b>远环</b>：只给计数，回答"这个方向到底有没有"，不给会被误当成目标的具体格子。</li>
 * </ul>
 *
 * <p><b>为什么近环按路径归组而不是"最近的 8 格原始观测"：</b>石头地上站着扫一圈，
 * 最近 8 格全是 stone，箱子和铜矿会被完全挤掉——那正是旧回执"看不见能干活的东西"的病根。
 * 归组后再把剩下的名额轮流发给各路径，保证"每种都至少给一格坐标"。
 */
public final class ScanSummary {

    /** 近环最多列几格坐标（设计卡：细列 ≤8 条）。 */
    public static final int NEAR_MAX_CELLS = 8;
    /** 近环最多分几种路径（8 个名额至少要能让 8 种各拿一格）。 */
    public static final int NEAR_MAX_GROUPS = 8;
    /** 中环最多几组（≤10）。 */
    public static final int MID_MAX_GROUPS = 10;
    /** 远环最多几组（≤12）。 */
    public static final int FAR_MAX_GROUPS = 12;
    /** 每格最多存几个"候选坐标"参与轮转（再远的近环格子不进回执）。 */
    private static final int CELLS_KEPT_PER_GROUP = 4;

    private static final int BAND_COUNT = 3;

    private final int cx;
    private final int cy;
    private final int cz;
    private final int radius;
    private final List<Group>[] bands;

    @SuppressWarnings("unchecked")
    public ScanSummary(int centerX, int centerY, int centerZ, int radius) {
        this.cx = centerX;
        this.cy = centerY;
        this.cz = centerZ;
        this.radius = Math.max(1, radius);
        this.bands = new List[BAND_COUNT];
        for (int i = 0; i < BAND_COUNT; i++) {
            this.bands[i] = new ArrayList<>();
        }
    }

    /** 一条观测。{@code note} 是可行动的小尾巴（如作物成熟度），同路径只保留第一次出现的。 */
    public void add(ScanCategory cat, String path, int x, int y, int z, String note) {
        int band = ScanPlan.bandOf(x - cx, z - cz, radius);
        if (band == ScanPlan.BAND_NONE) {
            return;   // 环带外的观测（例如实体离得比扫描半径还远）不进摘要
        }
        double d = distance(x, y, z);
        List<Group> list = bands[band];
        Group g = null;
        for (Group cand : list) {
            if (cand.path.equals(path) && cand.cat == cat) {
                g = cand;
                break;
            }
        }
        if (g == null) {
            g = new Group(cat, path);
            list.add(g);
        }
        g.count++;
        if (note != null && g.note == null) {
            g.note = note;
        }
        g.offer(x, y, z, d);
    }

    public void add(ScanCategory cat, String path, int x, int y, int z) {
        add(cat, path, x, y, z, null);
    }

    /** 命中观测总数（所有层）。用于"这一带到底看到东西没有"。 */
    public int total() {
        int n = 0;
        for (List<Group> list : bands) {
            for (Group g : list) {
                n += g.count;
            }
        }
        return n;
    }

    /** 某层有没有内容（决定这行要不要印）。 */
    public boolean isEmpty(int band) {
        return bands[band].isEmpty();
    }

    /**
     * 装配摘要行（不含首行"我在 …"）。顺序：近 → 中 → 远；空层跳过。
     * 体量由三个 *_MAX_* 常数封顶，不靠事后裁剪。
     */
    public List<String> lines() {
        List<String> out = new ArrayList<>(3);
        String near = renderNear();
        if (near != null) {
            out.add(near);
        }
        String mid = renderCoarse(ScanPlan.BAND_MID, "中圈(步长2,给最近一格)", MID_MAX_GROUPS, true);
        if (mid != null) {
            out.add(mid);
        }
        String far = renderCoarse(ScanPlan.BAND_FAR, "远圈(步长3,只计数)", FAR_MAX_GROUPS, false);
        if (far != null) {
            out.add(far);
        }
        return out;
    }

    /** 近环真正发给模型的那几格坐标（每组从最近的一格开始）：给 data.special_blocks 用。 */
    public List<String> nearCells() {
        List<String> out = new ArrayList<>();
        for (Entry e : nearEntries()) {
            out.add(e.group().path + " " + e.cell().render());
        }
        return out;
    }

    /** 近环细列：名额轮转后按组输出（与 {@link #nearEntries()} 严格同一口径）。 */
    private String renderNear() {
        List<Entry> entries = nearEntries();
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("近圈(步长1,可直接下指令): ");
        Group prev = null;
        for (Entry e : entries) {
            Group g = e.group();
            if (g != prev) {
                if (prev != null) {
                    sb.append("; ");
                }
                sb.append('[').append(g.cat.key()).append("] ").append(g.path)
                        .append(" x").append(g.count);
                if (g.note != null) {
                    sb.append(' ').append(g.note);
                }
                prev = g;
            }
            sb.append(' ').append(e.cell().render());
        }
        return sb.toString();
    }

    /**
     * 名额分配：组按"最近距离"排序，然后轮转发格——第 k 轮给每组第 k 近的格子，发满
     * {@link #NEAR_MAX_CELLS} 为止。组数封顶 NEAR_MAX_GROUPS，所以"每种至少一格"是保证；
     * 顺序与 {@link #renderNear()} 输出严格一致（回执和 data 不能对不上）。
     */
    private List<Entry> nearEntries() {
        List<Group> groups = sorted(bands[ScanPlan.BAND_NEAR], NEAR_MAX_GROUPS);
        List<List<Cell>> picked = new ArrayList<>();
        for (Group g : groups) {
            picked.add(new ArrayList<>());
        }
        int used = 0;
        for (int round = 0; round < CELLS_KEPT_PER_GROUP && used < NEAR_MAX_CELLS; round++) {
            for (int i = 0; i < groups.size() && used < NEAR_MAX_CELLS; i++) {
                List<Cell> cells = groups.get(i).nearest();
                if (round < cells.size()) {
                    picked.get(i).add(cells.get(round));
                    used++;
                }
            }
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            for (Cell c : picked.get(i)) {
                out.add(new Entry(groups.get(i), c));
            }
        }
        return out;
    }

    /** 一条被选中的近环细列（组 + 格子）；组引用相等即同一组，用于合并成一段。 */
    private record Entry(Group group, Cell cell) {
    }

    /** 中/远环：每组 计数（中环再带最近一格坐标）。 */
    private String renderCoarse(int band, String header, int maxGroups, boolean withNearest) {
        List<Group> groups = sorted(bands[band], maxGroups);
        if (groups.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(header).append(": ");
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append('[').append(g.cat.key()).append("] ").append(g.path)
                    .append(" x").append(g.count);
            if (g.note != null) {
                sb.append(' ').append(g.note);
            }
            if (withNearest) {
                List<Cell> cells = g.nearest();
                if (!cells.isEmpty()) {
                    Cell c = cells.get(0);
                    sb.append(" 最近").append(ScanFormat.pos(c.x, c.y, c.z, c.d));
                }
            }
        }
        return sb.toString();
    }

    /** 排序口径：可行动优先 = 最近的排前面；同距离按数量降序、再按路径字典序（结果必须可复现）。 */
    private List<Group> sorted(List<Group> list, int cap) {
        List<Group> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparingDouble((Group g) -> g.nearestDist)
                .thenComparing(Comparator.comparingInt((Group g) -> -g.count))
                .thenComparing(g -> g.path));
        if (copy.size() > cap) {
            copy = new ArrayList<>(copy.subList(0, cap));
        }
        return copy;
    }

    /** 组统计（供 data 字段用）：{@code [cat, path, count]}。 */
    public List<String> groupTokens() {
        List<String> out = new ArrayList<>();
        for (List<Group> list : bands) {
            for (Group g : list) {
                out.add(g.cat.key() + ":" + g.path + ":" + g.count);
            }
        }
        return out;
    }

    private double distance(int x, int y, int z) {
        double dx = x - cx;
        double dy = y - cy;
        double dz = z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 一组同类路径的观测；cells 只保留最近的 {@value #CELLS_KEPT_PER_GROUP} 格。 */
    private static final class Group {
        final ScanCategory cat;
        final String path;
        int count;
        String note;
        double nearestDist = Double.MAX_VALUE;
        private final List<Cell> cells = new ArrayList<>(CELLS_KEPT_PER_GROUP);

        Group(ScanCategory cat, String path) {
            this.cat = cat;
            this.path = path;
        }

        void offer(int x, int y, int z, double d) {
            if (d < nearestDist) {
                nearestDist = d;
            }
            if (cells.size() < CELLS_KEPT_PER_GROUP) {
                cells.add(new Cell(x, y, z, d));
                cells.sort(Comparator.comparingDouble(c -> c.d));
            } else if (d < cells.get(cells.size() - 1).d) {
                cells.remove(cells.size() - 1);
                cells.add(new Cell(x, y, z, d));
                cells.sort(Comparator.comparingDouble(c -> c.d));
            }
        }

        List<Cell> nearest() {
            return cells;
        }
    }

    /** 一个候选格子（绝对坐标 + 到同伴的欧氏距离）。 */
    public static final class Cell {
        public final int x;
        public final int y;
        public final int z;
        public final double d;

        Cell(int x, int y, int z, double d) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.d = d;
        }

        public String render() {
            return ScanFormat.pos(x, y, z, d);
        }
    }

    /** 供调用方拼体量提示用（把摘要行连起来数一下字节）。 */
    public static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
            sb.append(it.next());
            if (it.hasNext()) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
