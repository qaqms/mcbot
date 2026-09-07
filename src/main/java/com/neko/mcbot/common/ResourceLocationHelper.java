package com.neko.mcbot.common;

import net.minecraft.resources.Identifier;

/** 本映射层标识类真名是 Identifier（1.21.11 实测），单点收敛防漂移。 */
public final class ResourceLocationHelper {

    private ResourceLocationHelper() {
    }

    public static Identifier of(String namespaced) {
        return Identifier.parse(namespaced);
    }
}
