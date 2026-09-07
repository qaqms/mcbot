package com.neko.mcbot.agentcore.prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** 技能笔记：目录下每个 *.md 原文拼进 system prompt（按需加载在 M5 精修）。 */
public final class SkillLoader {

    private SkillLoader() {
    }

    public static List<String> load(Path dir) {
        List<String> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            out.add("## " + p.getFileName() + "\n" + Files.readString(p));
                        } catch (IOException e) {
                            // 单篇读失败不影响其余技能
                        }
                    });
        } catch (IOException e) {
            return out;
        }
        return out;
    }
}
