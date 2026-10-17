package com.neko.mcbot.server;

import com.neko.mcbot.building.CellWitness;
import net.minecraft.core.BlockPos;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.UUID;

/** Body-local identities require the same native container, chunk and unbroken cell witness. */
public final class ContainerIdentity {
    public static final int LIMIT = 128;
    private record Entry(WeakReference<Object> world, WeakReference<Object> chunk,
                         WeakReference<Object> container, BlockPos pos, CellWitness witness, String id) { }
    private final ArrayList<Entry> entries = new ArrayList<>();

    public String identify(Object world, Object chunk, Object container, BlockPos pos, CellWitness witness) {
        if (world == null || chunk == null || container == null || witness == null || !witness.intact()) return null;
        entries.removeIf(e -> e.world().get() == null || e.chunk().get() == null
                || e.container().get() == null || !e.witness().intact());
        for (var e : entries)
            if (e.world().get() == world && e.chunk().get() == chunk && e.container().get() == container
                    && e.pos().equals(pos) && e.witness() == witness) return e.id();
        if (entries.size() >= LIMIT) return null;
        String id = UUID.randomUUID().toString();
        entries.add(new Entry(new WeakReference<>(world), new WeakReference<>(chunk),
                new WeakReference<>(container), pos.immutable(), witness, id));
        return id;
    }
}
