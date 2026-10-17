package com.neko.mcbot.building;

/** A watched native cell may change away and back; equality of the final state is insufficient. */
public final class CellWitness {
    private boolean intact = true;
    public boolean intact() { return intact; }
    public void invalidate() { intact = false; }
}
