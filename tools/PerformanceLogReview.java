import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Read-only, allowlisted aggregate metrics. Does not print log bodies, model text or configuration. */
class PerformanceLogReview {
    private static final Pattern PREFIX = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2}] \\[[^]]+/INFO]: ");
    private static final Pattern FIELD = Pattern.compile("(?:^|\\s)([a-z_]+)=(-?\\d+)(?:ms)?(?=\\s|$)");
    private static final Pattern TOOL = Pattern.compile("^\\[brain] tool ([a-z_]+) (\\d+)ms chars=\\d+$");
    private static final Pattern FAILURE = Pattern.compile("^tool ([a-z_]+) -> [^A-Z]*([A-Z][A-Z_]+):");
    private static final Set<String> TOOLS = Set.of("status", "inventory", "scan_area", "inspect_block",
            "find_resource", "search_resource", "craft", "smelt", "break_block", "place_block",
            "move_to", "attack", "collect", "transfer", "equip", "consume", "wait", "follow",
            "build_diff", "build_site", "build_nearby", "build_record", "build_blueprint",
            "run_program", "workflow", "gather", "explore", "set_goal", "tool_help", "ask_owner", "report_blocked");

    private static final class Samples {
        final List<Long> values = new ArrayList<>();
        void add(long value) { if (value >= 0) values.add(value); }
        String report() {
            if (values.isEmpty()) return "n=0";
            var ordered = values.stream().sorted().toList();
            return "n=" + ordered.size() + " sum=" + ordered.stream().mapToLong(Long::longValue).sum()
                    + " p50=" + percentile(ordered, .5) + " p95=" + percentile(ordered, .95)
                    + " max=" + ordered.getLast();
        }
        private static long percentile(List<Long> values, double fraction) {
            return values.get(Math.max(0, (int) Math.ceil(values.size() * fraction) - 1));
        }
    }

    private static final class Review {
        final Map<String, Samples> metrics = new TreeMap<>();
        final Map<String, Integer> failures = new TreeMap<>();
        final Set<String> requests = new java.util.HashSet<>(), responses = new java.util.HashSet<>();
        int pathPerf;

        void sample(String name, Map<String, Long> fields, String key) {
            if (fields.containsKey(key)) metrics.computeIfAbsent(name, ignored -> new Samples()).add(fields.get(key));
        }
        void read(String line) {
            var prefix = PREFIX.matcher(line);
            if (!prefix.find()) return;
            String text = line.substring(prefix.end());
            var fields = new HashMap<String, Long>();
            var match = FIELD.matcher(text);
            while (match.find()) {
                try { fields.put(match.group(1), Long.parseLong(match.group(2))); }
                catch (NumberFormatException overflow) { /* Ignore invalid diagnostic numbers. */ }
            }
            if (text.startsWith("[brain] llm request ")) {
                if (fields.containsKey("request") && fields.containsKey("attempt"))
                    requests.add(fields.get("request") + ":" + fields.get("attempt"));
                sample("request_bytes", fields, "bytes");
            } else if (text.startsWith("[brain] llm response ")) {
                if (fields.containsKey("request") && fields.containsKey("attempt"))
                    responses.add(fields.get("request") + ":" + fields.get("attempt"));
            } else if (text.startsWith("[brain] llm stream ")) {
                // Old stream records have no request ID. Aggregate them independently, never join by adjacency.
                for (String key : List.of("ttfb", "first_tool", "first_dispatch", "stream"))
                    sample("stream_" + key + "_ms", fields, key);
            } else if (text.startsWith("[brain] step tokens ")) {
                for (String key : List.of("prompt", "completion", "cached"))
                    sample("tokens_" + key, fields, key);
            } else if (text.startsWith("[path-perf] ")) {
                pathPerf++;
            }
            var tool = TOOL.matcher(text);
            if (tool.matches() && TOOLS.contains(tool.group(1)))
                metrics.computeIfAbsent("server_tool_" + tool.group(1) + "_ms", ignored -> new Samples())
                        .add(Long.parseLong(tool.group(2)));
            var failure = FAILURE.matcher(text);
            if (failure.find() && TOOLS.contains(failure.group(1))) {
                String code = failure.group(2);
                if (Set.of("DENIED", "SCOPE_LIMIT", "ACTION_LIMIT", "PARTIAL", "NO_PROGRESS", "NO_PATH",
                        "TARGET_LOST", "TARGET_BLOCKED", "OUT_OF_REACH", "OCCLUDED", "TIMEOUT",
                        "CANCELLED", "BODY_DIED", "WRONG_TOOL", "NEED_CONFIRM", "ATTACK_FAILED").contains(code))
                    failures.merge(failure.group(1) + ":" + code, 1, Integer::sum);
            }
        }
        void print() {
            var pending = new java.util.HashSet<>(requests);
            pending.removeAll(responses);
            var orphan = new java.util.HashSet<>(responses);
            orphan.removeAll(requests);
            System.out.printf("requests=%d response_records=%d requests_without_response=%d orphan_responses=%d"
                    + " path_perf_records=%d%n", requests.size(), responses.size(), pending.size(), orphan.size(), pathPerf);
            metrics.forEach((name, samples) -> System.out.println(name + " " + samples.report()));
            failures.forEach((code, count) -> System.out.println("failure " + code + " count=" + count));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length == 0) throw new IllegalArgumentException("Supply explicit .log or .log.gz paths");
        for (String argument : args) {
            Path path = Path.of(argument);
            String name = path.getFileName().toString();
            if (!name.endsWith(".log") && !name.endsWith(".log.gz"))
                throw new IllegalArgumentException("Only explicit .log or .log.gz inputs are supported");
            var digest = MessageDigest.getInstance("SHA-256");
            var review = new Review();
            try (var raw = new DigestInputStream(Files.newInputStream(path), digest);
                 InputStream decoded = name.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                 var reader = new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) review.read(line);
            }
            System.out.println("source=" + name + " sha256=" + HexFormat.of().formatHex(digest.digest()));
            review.print();
        }
        System.out.println("scope=aggregate_only streams_unpaired=true sums_may_overlap=true native_ab=false");
    }

    private static void selfTest() throws Exception {
        var review = new Review();
        for (String text : List.of(
                "[brain] llm request request=1 attempt=1 bytes=100",
                "[brain] llm request request=1 attempt=2 bytes=200",
                "[brain] llm response request=1 attempt=2 http=200",
                "[brain] llm request request=2 attempt=1 bytes=300",
                "[brain] llm stream ttfb=-1ms first_dispatch=17ms stream=20ms",
                "[brain] llm stream ttfb=4ms first_dispatch=-1ms stream=30ms",
                "tool attack -> \u2718 DENIED:do-not-print",
                "tool unknown_private -> \u2718 DENIED:do-not-print",
                "[brain] tool attack 6ms chars=8",
                "[model-test] llm request request=3 attempt=1 bytes=999",
                "[brain] llm response request=99 attempt=1 http=0"))
            review.read("[00:00:01] [Render thread/INFO]: " + text);
        review.read("private [brain] llm request request=80 attempt=1 bytes=9999");
        if (review.requests.size() != 3 || review.responses.size() != 2
                || review.metrics.get("stream_first_dispatch_ms").values.size() != 1
                || review.metrics.get("stream_ttfb_ms").values.size() != 1
                || review.metrics.get("request_bytes").values.size() != 3
                || review.failures.size() != 1 || review.failures.get("attack:DENIED") != 1
                || !review.metrics.get("stream_stream_ms").report().contains("p50=20"))
            throw new AssertionError("Diagnostic aggregation failed");
        byte[] plain = "[00:00:00] [Server thread/INFO]: [brain] tool status 3ms chars=4\n"
                .getBytes(StandardCharsets.UTF_8);
        var compressed = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(plain); }
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()));
             var reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8))) {
            review.read(reader.readLine());
        }
        if (review.metrics.get("server_tool_status_ms").values.getFirst() != 3)
            throw new AssertionError("Compressed log decoding failed");
        System.out.println("self_test=passed private_content_emitted=false");
    }
}
