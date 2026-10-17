package com.neko.mcbot.server;

/** Implemented on actual loaded chunks; a new chunk incarnation gets a different stamp. */
public interface TerrainVersion {
    String mcbot$terrainStamp();
    void mcbot$invalidateTerrain();
}
