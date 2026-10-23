package com.neko.mcbot.agentcore.prompt;

import java.nio.file.Path;
import java.util.List;

/** Compatibility entry for callers needing only the discoverable knowledge index. */
public final class SkillLoader {

    private SkillLoader() {
    }

    public static List<String> load(Path dir) {
        String index = SkillCatalog.load(dir).promptIndex();
        return index.isEmpty() ? List.of() : List.of(index);
    }
}
