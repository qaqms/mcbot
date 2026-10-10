package com.neko.mcbot.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLocksTest {
    private final ResourceLocks locks = new ResourceLocks();
    private final UUID one = UUID.randomUUID(), two = UUID.randomUUID();
    private final ResourceLocks.Region area = new ResourceLocks.Region("overworld", 0, 80, 0, 2, 82, 2);

    @Test void sameBodyAndOverlappingWorldAreasAreExclusiveUntilClose() {
        var lease = locks.acquire(one, List.of(area));
        assertNotNull(lease);
        assertNull(locks.acquire(one, List.of()));
        assertNull(locks.acquire(two, List.of(area)));
        lease.close();
        lease.close();
        assertEquals(0, locks.size());
        assertNotNull(locks.acquire(two, List.of(area)));
    }

    @Test void disjointDimensionsAndRegionsCanProceed() {
        assertNotNull(locks.acquire(one, List.of(area)));
        assertNotNull(locks.acquire(two, List.of(new ResourceLocks.Region("nether", 0, 80, 0, 2, 82, 2))));
        assertNotNull(locks.acquire(UUID.randomUUID(),
                List.of(new ResourceLocks.Region("overworld", 3, 80, 0, 5, 82, 2))));
    }

    @Test void replanningExtensionIsAtomicAndRejectsOccupiedTargets() {
        var first = locks.acquire(one, List.of());
        var second = locks.acquire(two, List.of(area));
        var free = new ResourceLocks.Region("overworld", 10, 80, 0, 12, 82, 2);
        assertFalse(first.extend(List.of(free, area)));
        assertNotNull(locks.acquire(UUID.randomUUID(), List.of(free)));
        second.close();
        assertTrue(first.extend(List.of(area)));
        assertNull(locks.acquire(two, List.of(area)));
        first.close();
        assertFalse(first.extend(List.of(area)));
    }

    @Test void regionCountIsBounded() {
        assertNull(locks.acquire(one, java.util.Collections.nCopies(257, area)));
        assertEquals(0, locks.size());
    }

    @Test void sameEntityRemainsExclusiveEvenWhenItMovesOutOfTheOriginalRegion() {
        var first = locks.acquire(one, List.of(area));
        var second = locks.acquire(two, List.of());
        var target = new ResourceLocks.Target("overworld", UUID.randomUUID());
        assertTrue(first.claim(target));
        assertTrue(first.claim(target));
        assertFalse(second.claim(target));
        assertTrue(first.extend(List.of(new ResourceLocks.Region("overworld", 20, 80, 0, 22, 82, 2))));
        assertFalse(second.claim(target));
        first.close();
        assertTrue(second.claim(target));
        assertFalse(first.claim(target));
    }

    @Test void entityClaimsRespectDimensionAndCountLimit() {
        var first = locks.acquire(one, List.of());
        var second = locks.acquire(two, List.of());
        UUID entity = UUID.randomUUID();
        assertTrue(first.claim(new ResourceLocks.Target("overworld", entity)));
        assertTrue(second.claim(new ResourceLocks.Target("nether", entity)));
        for (int i = 1; i < 256; i++) assertTrue(first.claim(new ResourceLocks.Target("overworld", UUID.randomUUID())));
        assertFalse(first.claim(new ResourceLocks.Target("overworld", UUID.randomUUID())));
        first.close();
        assertTrue(second.claim(new ResourceLocks.Target("overworld", entity)));
    }
}
