import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;

/**
 * Pre-launch check for Toshi's Server (v3). Everything it enforces comes from the pack repo on GitHub Pages:
 *   forever-world/mmc-pack.json            the exact component list this instance must have (Minecraft, LWJGL, Fabric, ...)
 *   forever-world/instance-cfg.properties  a few enforced instance.cfg keys (see ALLOWED_CFG)
 *   forever-world/tiers.json               per-PC defaults (memory, view distance, Distant Horizons, FPS cap)
 * Per-PC defaults are written only for values the player never changed: forever-world/fw-managed.json in the instance
 * records what we wrote; a different current value means the player changed it and we never touch it again.
 * Any failure in hardware detection or tuning changes nothing and is logged to check.log; it never blocks the launch.
 * Source: src/ForeverWorldCheck.java in the pack repo (javac --release 17; must stay ONE class file, no nested classes).
 */
public class ForeverWorldCheck {
    static final String BASE = System.getenv().getOrDefault("FW_BASE", "https://toshi2d.github.io/minecraft-forever-world/");
    static final String PACK = BASE + "pack.toml";
    /** Enforced every launch from instance-cfg.properties. */
    static final Set<String> ALLOWED_CFG = Set.of("JvmArgs", "OverrideJavaArgs");
    /** Per-PC instance.cfg keys (Prism's own memory settings): written by the tier logic only while the player has not changed them. */
    static final Set<String> MANAGED_CFG = Set.of("OverrideMemory", "MaxMemAlloc", "MinMemAlloc");
    static final Set<String> WRITABLE_CFG;
    static { Set<String> s = new HashSet<>(ALLOWED_CFG); s.addAll(MANAGED_CFG); WRITABLE_CFG = Collections.unmodifiableSet(s); }
    static Path logFile;

    static void log(String s) {
        System.err.println("[forever-world] " + s);
        try { if (logFile != null) Files.writeString(logFile, Instant.now() + " " + s + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Exception ignore) { }
    }

    static String fetch(String url) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    /** uid -> version for every component in a Prism mmc-pack.json (uid and version are adjacent keys in Prism's files). */
    static Map<String, String> components(String json) {
        Map<String, String> out = new TreeMap<>();
        Matcher m = Pattern.compile("\"uid\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    static Map<String, String> props(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("[") || !l.contains("=")) continue;
            int i = l.indexOf('=');
            out.put(l.substring(0, i).strip(), l.substring(i + 1).strip());
        }
        return out;
    }

    static String applyCfg(String cfg, Map<String, String> want) {
        String out = cfg;
        if (!Pattern.compile("(?m)^\\[General\\]\\s*$").matcher(out).find()) out = "[General]\n" + out;
        for (Map.Entry<String, String> e : want.entrySet()) {
            if (!WRITABLE_CFG.contains(e.getKey())) { log("ignoring cfg key not on the allow-list: " + e.getKey()); continue; }
            Matcher m = Pattern.compile("(?m)^" + Pattern.quote(e.getKey()) + "=.*$").matcher(out);
            String line = e.getKey() + "=" + e.getValue();
            out = m.find() ? m.replaceFirst(Matcher.quoteReplacement(line)) : out.replaceFirst("(?m)^\\[General\\]\\s*$", "[General]\n" + Matcher.quoteReplacement(line));
        }
        return out;
    }

    static boolean cfgDiffers(String cfg, Map<String, String> want) {
        Map<String, String> have = props(cfg);
        for (Map.Entry<String, String> e : want.entrySet())
            if (WRITABLE_CFG.contains(e.getKey()) && !e.getValue().equals(have.get(e.getKey()))) return true;
        return false;
    }

    static void popup(String title, String msg) {
        System.err.println(msg);
        if (System.getenv("FW_TEST_NO_POPUP") != null) return;
        try { JOptionPane.showMessageDialog(null, msg, title, JOptionPane.INFORMATION_MESSAGE); } catch (Throwable headless) { }
    }

    static Instant prismStart() {
        Instant found = null;
        Optional<ProcessHandle> p = ProcessHandle.current().parent();
        while (p.isPresent()) {
            ProcessHandle h = p.get();
            String cmd = h.info().command().orElse("").toLowerCase();
            String args = String.join(" ", h.info().arguments().orElse(new String[0])).toLowerCase();
            if (cmd.contains("prism") || args.contains("prism")) found = h.info().startInstant().orElse(found);
            p = h.parent();
        }
        return found;
    }

    // ------------------------------------------------------------------ minimal JSON (no libraries in a pre-launch class)

    static Object parseJson(String s) {
        int[] pos = {0};
        Object v = jsonValue(s, pos);
        jsonWs(s, pos);
        if (pos[0] != s.length()) throw new IllegalArgumentException("trailing JSON at " + pos[0]);
        return v;
    }

    static void jsonWs(String s, int[] p) { while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++; }

    static Object jsonValue(String s, int[] p) {
        jsonWs(s, p);
        if (p[0] >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
        char c = s.charAt(p[0]);
        if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            p[0]++; jsonWs(s, p);
            if (s.charAt(p[0]) == '}') { p[0]++; return m; }
            while (true) {
                jsonWs(s, p);
                Object k = jsonValue(s, p);
                if (!(k instanceof String)) throw new IllegalArgumentException("object key must be a string at " + p[0]);
                jsonWs(s, p);
                if (s.charAt(p[0]++) != ':') throw new IllegalArgumentException("expected : at " + p[0]);
                m.put((String) k, jsonValue(s, p));
                jsonWs(s, p);
                char d = s.charAt(p[0]++);
                if (d == '}') return m;
                if (d != ',') throw new IllegalArgumentException("expected , or } at " + p[0]);
            }
        }
        if (c == '[') {
            List<Object> l = new ArrayList<>();
            p[0]++; jsonWs(s, p);
            if (s.charAt(p[0]) == ']') { p[0]++; return l; }
            while (true) {
                l.add(jsonValue(s, p));
                jsonWs(s, p);
                char d = s.charAt(p[0]++);
                if (d == ']') return l;
                if (d != ',') throw new IllegalArgumentException("expected , or ] at " + p[0]);
            }
        }
        if (c == '"') {
            StringBuilder b = new StringBuilder();
            p[0]++;
            while (true) {
                char d = s.charAt(p[0]++);
                if (d == '"') return b.toString();
                if (d == '\\') {
                    char e = s.charAt(p[0]++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
                        default: b.append(e);
                    }
                } else b.append(d);
            }
        }
        if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
        int st = p[0];
        while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
        if (st == p[0]) throw new IllegalArgumentException("bad JSON at " + st);
        return Double.parseDouble(s.substring(st, p[0]));
    }

    static String toJson(Object v, String ind) {
        if (v == null) return "null";
        if (v instanceof String) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : ((String) v).toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c == '\n') b.append("\\n");
                else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            return b.append('"').toString();
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
        }
        if (v instanceof Boolean) return v.toString();
        String in = ind + "  ";
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) return "{}";
            StringBuilder b = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet())
                b.append(in).append(toJson(String.valueOf(e.getKey()), in)).append(": ").append(toJson(e.getValue(), in)).append(++i < m.size() ? ",\n" : "\n");
            return b.append(ind).append('}').toString();
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) return "[]";
            StringBuilder b = new StringBuilder("[\n");
            for (int i = 0; i < l.size(); i++) b.append(in).append(toJson(l.get(i), in)).append(i + 1 < l.size() ? ",\n" : "\n");
            return b.append(ind).append(']').toString();
        }
        return toJson(v.toString(), ind);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }
    static double num(Object o, double dflt) { return o instanceof Number ? ((Number) o).doubleValue() : dflt; }
    static String str(Object o) { return o == null ? null : (o instanceof Number ? toJson(o, "") : o.toString()); }

    // ------------------------------------------------------------------ hardware detection (best effort, never throws)

    /** Run a command with a hard timeout; returns stdout or null. */
    static String run(int seconds, String... cmd) {
        try {
            Path out = Files.createTempFile("fw-probe", ".txt");
            try {
                Process p = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); log("probe timed out: " + cmd[0]); return null; }
                return Files.readString(out, StandardCharsets.UTF_8);
            } finally { Files.deleteIfExists(out); }
        } catch (Throwable t) { return null; }
    }

    static String readSmall(Path p) {
        try { return Files.exists(p) ? Files.readString(p).trim() : null; } catch (Throwable t) { return null; }
    }

    /** One GPU as a map: name, vendor (amd/nvidia/intel/apple/other), class (discrete/integrated/apple/unknown), vram_mb (-1 unknown). */
    static Map<String, Object> gpu(String name, String vendor, long vramMb) {
        Map<String, Object> g = new LinkedHashMap<>();
        String n = name == null ? "" : name.trim();
        String v = vendor != null ? vendor : guessVendor(n);
        g.put("name", n);
        g.put("vendor", v);
        g.put("vram_mb", (double) vramMb);
        g.put("class", gpuClass(n, v, vramMb));
        return g;
    }

    static String guessVendor(String n) {
        String l = n.toLowerCase();
        if (l.contains("nvidia") || l.contains("geforce") || l.contains("quadro")) return "nvidia";
        if (l.contains("amd") || l.contains("radeon") || l.contains("ati ")) return "amd";
        if (l.contains("intel")) return "intel";
        if (l.contains("apple")) return "apple";
        return "other";
    }

    static String gpuClass(String name, String vendor, long vramMb) {
        String l = name.toLowerCase();
        switch (vendor) {
            case "apple": return "apple";
            case "nvidia": return "discrete";
            case "amd":
                if (vramMb >= 2048) return "discrete";
                if (vramMb >= 0) return "integrated";
                return Pattern.compile("\\b(rx|r9|r7|pro w)\\s?\\d").matcher(l).find() ? "discrete" : "integrated";
            case "intel":
                return Pattern.compile("arc\\S*\\s+(?:pro\\s+)?[ab]\\d{3}").matcher(l).find() ? "discrete" : "integrated";
            default: return "unknown";
        }
    }

    static Map<String, Object> bestGpu(List<Map<String, Object>> gpus) {
        Map<String, Object> best = null;
        for (Map<String, Object> g : gpus) if (best == null || gpuScore(g) > gpuScore(best)) best = g;
        return best;
    }

    static double gpuScore(Map<String, Object> g) {
        String c = str(g.get("class"));
        double rank = "discrete".equals(c) ? 3 : "apple".equals(c) ? 2 : "integrated".equals(c) ? 1 : 0;
        return rank * 1e7 + Math.max(0, num(g.get("vram_mb"), -1));
    }

    /** Linux: lspci -mm output (slot "class" "vendor" "device" ...) -> slot -> name, display controllers only. */
    static Map<String, String> parseLspci(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("(?m)^(\\S+)\\s+\"([^\"]*)\"\\s+\"([^\"]*)\"\\s+\"([^\"]*)\"").matcher(text);
        while (m.find()) {
            String cls = m.group(2).toLowerCase();
            if (cls.contains("vga") || cls.contains("3d") || cls.contains("display")) out.put(m.group(1), m.group(3) + " " + m.group(4));
        }
        return out;
    }

    static List<Map<String, Object>> detectLinuxGpus() {
        List<Map<String, Object>> gpus = new ArrayList<>();
        Map<String, String> names = parseLspci(run(3, "lspci", "-mm"));
        try (var s = Files.list(Paths.get("/sys/class/drm"))) {
            List<Path> cards = new ArrayList<>();
            s.filter(p -> p.getFileName().toString().matches("card\\d+")).sorted().forEach(cards::add);
            for (Path card : cards) {
                Path dev = card.resolve("device");
                String vid = readSmall(dev.resolve("vendor"));
                String cls = readSmall(dev.resolve("class"));
                if (vid == null || cls == null || !cls.startsWith("0x03")) continue;
                String vendor = vid.equalsIgnoreCase("0x1002") ? "amd" : vid.equalsIgnoreCase("0x10de") ? "nvidia" : vid.equalsIgnoreCase("0x8086") ? "intel" : "other";
                long vram = -1;
                String mem = readSmall(dev.resolve("mem_info_vram_total"));
                if (mem != null) try { vram = Long.parseLong(mem) / (1024 * 1024); } catch (NumberFormatException ignore) { }
                String slot = "";
                try { slot = dev.toRealPath().getFileName().toString().replaceFirst("^0000:", ""); } catch (Exception ignore) { }
                gpus.add(gpu(names.getOrDefault(slot, vendor + " gpu " + slot), vendor, vram));
            }
        } catch (Throwable ignore) { }
        if (gpus.isEmpty()) for (String n : names.values()) gpus.add(gpu(n, null, -1));
        boolean nvidia = gpus.stream().anyMatch(g -> "nvidia".equals(g.get("vendor")) && num(g.get("vram_mb"), -1) < 0);
        if (nvidia) {
            List<Map<String, Object>> nv = parseNvidiaSmi(run(3, "nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"));
            if (!nv.isEmpty()) { gpus.removeIf(g -> "nvidia".equals(g.get("vendor"))); gpus.addAll(nv); }
        }
        return gpus;
    }

    static List<Map<String, Object>> parseNvidiaSmi(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\R")) {
            String[] f = line.split(",");
            if (f.length < 2) continue;
            try { out.add(gpu(f[0].trim(), "nvidia", Long.parseLong(f[1].trim()))); } catch (NumberFormatException ignore) { }
        }
        return out;
    }

    /** macOS: system_profiler SPDisplaysDataType -json. Apple Silicon has unified memory: VRAM = 2/3 of RAM. */
    static List<Map<String, Object>> parseMacProfiler(String json, long ramMb) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            for (Object o : arr(obj(parseJson(json)).get("SPDisplaysDataType"))) {
                Map<String, Object> g = obj(o);
                String name = str(g.getOrDefault("sppci_model", g.get("_name")));
                String vend = String.valueOf(g.getOrDefault("spdisplays_vendor", "")).toLowerCase();
                String vendor = vend.contains("apple") || (name != null && name.startsWith("Apple")) ? "apple"
                    : vend.contains("amd") || vend.contains("ati") ? "amd" : vend.contains("nvidia") ? "nvidia" : vend.contains("intel") ? "intel" : null;
                long vram = -1;
                String v = str(g.getOrDefault("spdisplays_vram", g.get("spdisplays_vram_shared")));
                if (v != null) {
                    Matcher m = Pattern.compile("(\\d+)\\s*(GB|MB)", Pattern.CASE_INSENSITIVE).matcher(v);
                    if (m.find()) vram = Long.parseLong(m.group(1)) * (m.group(2).equalsIgnoreCase("GB") ? 1024 : 1);
                }
                if ("apple".equals(vendor) && ramMb > 0) vram = ramMb * 2 / 3;
                out.add(gpu(name, vendor, vram));
            }
        } catch (Throwable t) { log("could not read system_profiler output: " + t); }
        return out;
    }

    /** Windows: registry DriverDesc + qwMemorySize per adapter key (CIM AdapterRAM is uint32 and caps at 4 GB). */
    static List<Map<String, Object>> parseWindows(String cimNames, String regDesc, String regMem) {
        Map<String, String> desc = new LinkedHashMap<>();
        Map<String, Long> mem = new LinkedHashMap<>();
        String key = null;
        for (String line : (regDesc == null ? "" : regDesc).split("\\R")) {
            if (line.startsWith("HKEY_")) key = line.trim();
            else if (key != null && line.contains("DriverDesc") && line.contains("REG_SZ")) desc.put(key, line.substring(line.indexOf("REG_SZ") + 6).trim());
        }
        key = null;
        for (String line : (regMem == null ? "" : regMem).split("\\R")) {
            if (line.startsWith("HKEY_")) key = line.trim();
            else if (key != null && line.contains("qwMemorySize")) {
                Matcher m = Pattern.compile("0x([0-9a-fA-F]+)").matcher(line);
                if (m.find()) try { mem.put(key, Long.parseUnsignedLong(m.group(1), 16) / (1024 * 1024)); } catch (NumberFormatException ignore) { }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : desc.entrySet()) out.add(gpu(e.getValue(), null, mem.getOrDefault(e.getKey(), -1L)));
        if (out.isEmpty() && cimNames != null)
            for (String line : cimNames.split("\\R")) {
                String[] f = line.split("\\|");
                if (f.length == 0 || f[0].isBlank()) continue;
                long vram = -1;
                if (f.length > 1) try { vram = Long.parseLong(f[1].trim()) / (1024 * 1024); } catch (NumberFormatException ignore) { }
                out.add(gpu(f[0], null, vram >= 4095 ? -1 : vram)); // uint32 cap: 4 GB means "4 GB or more"
            }
        return out;
    }

    static List<Map<String, Object>> detectWindowsGpus() {
        String cls = "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}";
        String desc = run(4, "reg", "query", cls, "/s", "/v", "DriverDesc");
        String mem = run(4, "reg", "query", cls, "/s", "/v", "HardwareInformation.qwMemorySize");
        String cim = null;
        if (desc == null || !desc.contains("DriverDesc"))
            cim = run(8, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_VideoController | ForEach-Object { $_.Name + '|' + $_.AdapterRAM }");
        return parseWindows(cim, desc, mem);
    }

    static int detectRefreshHz() {
        try { if (java.awt.GraphicsEnvironment.isHeadless()) return -1; } catch (Throwable t) { return -1; }
        int[] hz = {-1};
        Thread t = new Thread(() -> {
            try {
                for (java.awt.GraphicsDevice d : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    int r = d.getDisplayMode().getRefreshRate();
                    if (r > hz[0]) hz[0] = r; // multi-monitor: the fastest screen
                }
            } catch (Throwable ignore) { }
        });
        t.setDaemon(true);
        t.start();
        try { t.join(4000); } catch (InterruptedException ignore) { }
        return hz[0] > 0 ? hz[0] : -1;
    }

    static long envLong(String k, long dflt) {
        try { String v = System.getenv(k); return v == null ? dflt : Long.parseLong(v.trim()); } catch (Exception e) { return dflt; }
    }

    /** Detected hardware: os, ram_mb, threads, gpus[], gpu (best), refresh_hz. FW_FAKE_* env vars override (tests). */
    static Map<String, Object> detectHardware() {
        Map<String, Object> hw = new LinkedHashMap<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        hw.put("os", os.contains("win") ? "windows" : os.contains("mac") ? "macos" : os.contains("linux") ? "linux" : os);
        boolean noProbes = System.getenv("FW_FAKE_NO_PROBES") != null;
        long ram = -1;
        try {
            java.lang.management.OperatingSystemMXBean b = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (b instanceof com.sun.management.OperatingSystemMXBean) ram = ((com.sun.management.OperatingSystemMXBean) b).getTotalMemorySize() / (1024 * 1024);
        } catch (Throwable ignore) { }
        ram = envLong("FW_FAKE_RAM_MB", ram);
        hw.put("ram_mb", (double) ram);
        hw.put("threads", (double) envLong("FW_FAKE_THREADS", Runtime.getRuntime().availableProcessors()));
        List<Map<String, Object>> gpus = new ArrayList<>();
        String fake = System.getenv("FW_FAKE_GPU"); // "class,vramMB,name"
        if (fake != null) {
            String[] f = fake.split(",", 3);
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", f.length > 2 ? f[2] : "fake");
            g.put("vendor", "fake");
            g.put("vram_mb", (double) envLongOf(f.length > 1 ? f[1] : "-1"));
            g.put("class", f[0].trim());
            gpus.add(g);
        } else if (!noProbes) {
            try {
                switch (String.valueOf(hw.get("os"))) {
                    case "linux": gpus = detectLinuxGpus(); break;
                    case "macos": gpus = parseMacProfiler(run(8, "system_profiler", "SPDisplaysDataType", "-json"), ram); break;
                    case "windows": gpus = detectWindowsGpus(); break;
                    default: break;
                }
            } catch (Throwable t) { log("GPU detection failed: " + t); }
        }
        hw.put("gpus", new ArrayList<Object>(gpus));
        Map<String, Object> best = bestGpu(gpus);
        hw.put("gpu", best != null ? best : gpu("unknown", "other", -1));
        long hz = envLong("FW_FAKE_HZ", -2);
        hw.put("refresh_hz", (double) (hz != -2 ? hz : (noProbes ? -1 : detectRefreshHz())));
        return hw;
    }

    static long envLongOf(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; } }

    // ------------------------------------------------------------------ tiers

    static boolean tierMatches(Map<String, Object> when, Map<String, Object> hw) {
        Map<String, Object> g = obj(hw.get("gpu"));
        double ram = num(hw.get("ram_mb"), -1), threads = num(hw.get("threads"), -1), vram = num(g.get("vram_mb"), -1);
        if (when.containsKey("min_ram_mb") && !(ram >= 0 && ram >= num(when.get("min_ram_mb"), 0))) return false;
        if (when.containsKey("min_threads") && !(threads >= 0 && threads >= num(when.get("min_threads"), 0))) return false;
        if (when.containsKey("min_vram_mb") && !(vram >= 0 && vram >= num(when.get("min_vram_mb"), 0))) return false;
        if (when.containsKey("gpu_class")) {
            String c = String.valueOf(g.getOrDefault("class", "unknown"));
            boolean ok = false;
            for (Object o : arr(when.get("gpu_class"))) if (c.equals(String.valueOf(o))) ok = true;
            if (!ok) return false;
        }
        return true;
    }

    static Map<String, Object> selectTier(Map<String, Object> tiers, Map<String, Object> hw) {
        List<Object> list = arr(tiers.get("tiers"));
        for (Object o : list) if (tierMatches(obj(obj(o).get("when")), hw)) return obj(o);
        return list.isEmpty() ? null : obj(list.get(list.size() - 1));
    }

    /** MaxMemAlloc in MiB: fraction x nominal RAM (rounded up to nominal_round_mb), rounded to round_mb, clamped to [floor, cap]. */
    static long maxMemMb(Map<String, Object> mem, double ramMb) {
        long floor = (long) num(mem.get("floor_mb"), 3072), cap = (long) num(mem.get("cap_mb"), 8192), round = Math.max(1, (long) num(mem.get("round_mb"), 512));
        if (ramMb <= 0) return floor;
        double nominalRound = Math.max(1, num(mem.get("nominal_round_mb"), 1));
        double nominal = Math.ceil(ramMb / nominalRound) * nominalRound;
        long v = Math.round(nominal * num(mem.get("fraction"), 0.5) / round) * round;
        return Math.max(floor, Math.min(cap, v));
    }

    /** file -> key -> desired value (as stored in that file). */
    static Map<String, Map<String, String>> desired(Map<String, Object> tiers, Map<String, Object> tier, Map<String, Object> hw, boolean fpsSyncInstalled) {
        Map<String, Map<String, String>> d = new LinkedHashMap<>();
        Map<String, Object> mem = obj(tiers.get("memory"));
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("OverrideMemory", "true");
        cfg.put("MaxMemAlloc", Long.toString(maxMemMb(mem, num(hw.get("ram_mb"), -1))));
        cfg.put("MinMemAlloc", Long.toString((long) num(mem.get("min_mb"), 1024)));
        d.put("instance.cfg", cfg);
        Map<String, String> opt = new LinkedHashMap<>();
        if (tier.get("renderDistance") != null) opt.put("renderDistance", str(tier.get("renderDistance")));
        if (tier.get("simulationDistance") != null) opt.put("simulationDistance", str(tier.get("simulationDistance")));
        Map<String, Object> fps = obj(tiers.get("fps"));
        if (fpsSyncInstalled) opt.put("maxFps", str(fps.getOrDefault("fps_sync_value", -10.0)));
        else {
            double hz = num(hw.get("refresh_hz"), -1);
            if (hz > 0) opt.put("maxFps", Long.toString((long) Math.max(num(fps.get("min"), 30), Math.min(num(fps.get("max"), 260), hz))));
        }
        d.put("options.txt", opt);
        Map<String, String> dh = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : obj(tier.get("dh")).entrySet())
            dh.put(e.getKey(), e.getValue() instanceof String ? "\"" + e.getValue() + "\"" : str(e.getValue()));
        d.put("DistantHorizons.toml", dh);
        return d;
    }

    // ------------------------------------------------------------------ ledger: never fight the player

    /**
     * Decide which keys of one file we may write. Mutates the ledger (records ours / marks the player's) and returns
     * key -> value to write. Player-owned -> never. In the ledger as ours and current == what we wrote (or missing) -> may
     * update. Ours but different now -> the player changed it -> mark user, leave it. Not in the ledger -> take it only if
     * the key is missing or still equals a known default (adopt list) or is in adoptAny; any other value is the player's.
     * extraOurs: values that still count as ours (vanilla resetting maxFps after FPS-Sync was paused). A pending
     * instance.cfg write whose old value came back is Prism saving its stale copy, not the player.
     */
    static Map<String, String> decide(Map<String, Object> ledger, String file, Map<String, String> current, Map<String, String> want,
                                      Map<String, Object> adopt, Set<String> adoptAny, String tierName, double tiersVersion,
                                      Map<String, Set<String>> extraOurs) {
        Map<String, Object> keys = obj(ledger.computeIfAbsent("keys", k -> new LinkedHashMap<String, Object>()));
        Map<String, String> write = new LinkedHashMap<>();
        for (Map.Entry<String, String> w : want.entrySet()) {
            String id = file + ":" + w.getKey();
            Map<String, Object> e = keys.containsKey(id) ? obj(keys.get(id)) : null;
            String cur = current.get(w.getKey());
            if (e != null && "user".equals(e.get("owner"))) continue;
            boolean ours;
            if (e != null) {
                String wrote = str(e.get("value"));
                ours = cur == null || cur.equals(wrote) || extraOurs.getOrDefault(w.getKey(), Set.of()).contains(cur)
                    || (Boolean.TRUE.equals(e.get("pending")) && Objects.equals(cur, str(e.get("prev"))));
                if (!ours) log(file + " " + w.getKey() + ": the player changed it (we wrote " + wrote + ", now " + cur + "); leaving it to them");
            } else {
                Set<String> ok = new HashSet<>();
                for (Object o : arr(adopt.get(id))) ok.add(str(o));
                ours = cur == null || ok.contains(cur) || adoptAny.contains(w.getKey());
                if (!ours) log(file + " " + w.getKey() + "=" + cur + " was set by the player; leaving it to them");
            }
            Map<String, Object> rec = new LinkedHashMap<>();
            if (!ours) {
                rec.put("owner", "user");
                rec.put("value_seen", cur);
                if (e != null) rec.put("we_wrote", e.get("value"));
                rec.put("at", Instant.now().toString());
                keys.put(id, rec);
                continue;
            }
            rec.put("owner", "fw");
            rec.put("value", w.getValue());
            rec.put("tier", tierName);
            rec.put("tiers_version", tiersVersion);
            Object prev = e != null ? e.get("prev") : cur;
            if (prev != null) rec.put("prev", prev);
            if (!w.getValue().equals(cur)) {
                write.put(w.getKey(), w.getValue());
                if (file.equals("instance.cfg")) rec.put("pending", true);
                rec.put("at", Instant.now().toString());
            } else {
                if (e != null && Boolean.TRUE.equals(e.get("pending"))) rec.put("pending", true);
                rec.put("at", e != null && e.get("at") != null && w.getValue().equals(str(e.get("value"))) ? e.get("at") : Instant.now().toString());
            }
            keys.put(id, rec);
        }
        return write;
    }

    /** instance.cfg: the three memory keys are one group; a player who set memory in Prism owns all three. */
    static Map<String, String> decideMemory(Map<String, Object> ledger, Map<String, String> cfgNow, Map<String, String> want,
                                            Map<String, Object> adopt, String tier, double tv) {
        Map<String, Object> keys = obj(ledger.computeIfAbsent("keys", k -> new LinkedHashMap<String, Object>()));
        Map<String, String> ovWant = new LinkedHashMap<>();
        ovWant.put("OverrideMemory", want.get("OverrideMemory"));
        Map<String, Object> ad = new LinkedHashMap<>(adopt);
        ad.put("instance.cfg:OverrideMemory", List.of("false"));
        Map<String, String> out = decide(ledger, "instance.cfg", cfgNow, ovWant, ad, Set.of(), tier, tv, Map.of());
        if ("user".equals(obj(keys.get("instance.cfg:OverrideMemory")).get("owner"))) {
            for (String k : List.of("MaxMemAlloc", "MinMemAlloc")) {
                if ("user".equals(obj(keys.get("instance.cfg:" + k)).get("owner"))) continue;
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("owner", "user"); rec.put("value_seen", cfgNow.get(k)); rec.put("at", Instant.now().toString());
                keys.put("instance.cfg:" + k, rec);
            }
            return out;
        }
        // We own OverrideMemory: while it was off, Prism's global default applied, so stored numbers were never the player's choice.
        Map<String, String> rest = new LinkedHashMap<>(want);
        rest.remove("OverrideMemory");
        out.putAll(decide(ledger, "instance.cfg", cfgNow, rest, adopt, Set.of("MaxMemAlloc", "MinMemAlloc"), tier, tv, Map.of()));
        return out;
    }

    /** After a launch got past the restart check, instance.cfg values we wrote are confirmed (no longer "pending"). */
    static void confirmPending(Map<String, Object> ledger, Map<String, String> cfgNow) {
        for (Map.Entry<String, Object> e : obj(ledger.get("keys")).entrySet()) {
            Map<String, Object> r = obj(e.getValue());
            if (!e.getKey().startsWith("instance.cfg:") || !Boolean.TRUE.equals(r.get("pending"))) continue;
            if (Objects.equals(cfgNow.get(e.getKey().substring("instance.cfg:".length())), str(r.get("value")))) r.remove("pending");
        }
    }

    // ------------------------------------------------------------------ file editors

    static Map<String, String> optionsMap(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            int i = line.indexOf(':');
            if (i > 0) m.put(line.substring(0, i), line.substring(i + 1));
        }
        return m;
    }

    static String applyOptions(String text, Map<String, String> write) {
        List<String> lines = new ArrayList<>(text.isEmpty() ? List.of() : Arrays.asList(text.split("\\R", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            int c = lines.get(i).indexOf(':');
            if (c <= 0) continue;
            String k = lines.get(i).substring(0, c);
            if (write.containsKey(k)) { lines.set(i, k + ":" + write.get(k)); done.add(k); }
        }
        for (Map.Entry<String, String> e : write.entrySet()) if (!done.contains(e.getKey())) lines.add(e.getKey() + ":" + e.getValue());
        return String.join("\n", lines) + "\n";
    }

    static String section(String headerLine) { return headerLine.replaceAll("^\\[+|\\]+$", "").strip(); }

    /** Current values of the given keys, each read only inside its own [section]. */
    static Map<String, String> tomlValues(String text, Map<String, String> keySection) {
        Map<String, String> out = new LinkedHashMap<>();
        String sec = "";
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("[")) { sec = section(t); continue; }
            int eq = t.indexOf('=');
            if (t.startsWith("#") || eq <= 0) continue;
            String k = t.substring(0, eq).strip();
            if (sec.equals(keySection.get(k))) out.put(k, t.substring(eq + 1).strip());
        }
        return out;
    }

    static String applyToml(String text, Map<String, String> write, Map<String, String> keySection) {
        String[] lines = text.split("\\R", -1);
        String sec = "";
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.startsWith("[")) { sec = section(t); continue; }
            int eq = t.indexOf('=');
            if (t.startsWith("#") || eq <= 0) continue;
            String k = t.substring(0, eq).strip();
            if (write.containsKey(k) && sec.equals(keySection.get(k))) {
                String indent = lines[i].substring(0, lines[i].indexOf(t.charAt(0)));
                lines[i] = indent + k + " = " + write.get(k);
            }
        }
        return String.join("\n", lines);
    }

    static String newToml(Map<String, String> write, Map<String, String> keySection) {
        Map<String, List<String>> bySec = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : write.entrySet())
            bySec.computeIfAbsent(keySection.get(e.getKey()), s -> new ArrayList<>()).add("\t" + e.getKey() + " = " + e.getValue());
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, List<String>> e : bySec.entrySet()) b.append("[").append(e.getKey()).append("]\n").append(String.join("\n", e.getValue())).append("\n\n");
        return b.toString();
    }

    static void backup(Path f, long stamp) {
        try { if (Files.exists(f)) Files.copy(f, f.resolveSibling(f.getFileName() + ".bak-fw-" + stamp), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignore) { }
    }

    static Map<String, Object> loadLedger(Path p) {
        try { if (Files.exists(p)) return obj(parseJson(Files.readString(p))); }
        catch (Exception e) {
            log("fw-managed.json unreadable (" + e + "); kept aside, no tuning this launch");
            backup(p, System.currentTimeMillis());
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("corrupt", true);
            return l;
        }
        return new LinkedHashMap<>();
    }

    static void saveLedger(Path p, Map<String, Object> ledger) throws Exception {
        ledger.put("version", 1.0);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(ledger, "") + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }

    static Map<String, Object> loadTiers(Path mcDir) {
        String text = System.getenv("FW_TEST_TIERS");
        String src = "FW_TEST_TIERS";
        if (text == null) {
            try { text = fetch(BASE + "forever-world/tiers.json"); src = BASE; }
            catch (Exception e) {
                Path local = mcDir.resolve("forever-world/tiers.json");
                try { text = Files.readString(local); src = "local copy"; }
                catch (Exception e2) { log("tiers.json unreachable (" + e + "), no local copy: no per-PC tuning this launch"); return null; }
            }
        }
        try {
            Map<String, Object> t = obj(parseJson(text));
            if (arr(t.get("tiers")).isEmpty()) throw new IllegalArgumentException("no tiers");
            log("tiers.json v" + str(t.get("version")) + " from " + src);
            return t;
        } catch (Exception e) { log("tiers.json is malformed (" + e + "): no per-PC tuning this launch"); return null; }
    }

    static boolean fpsSyncInstalled(Path mcDir, Map<String, Object> tiers) {
        String prefix = String.valueOf(obj(tiers.get("fps")).getOrDefault("fps_sync_jar_prefix", "fps-sync")).toLowerCase();
        try (var s = Files.list(mcDir.resolve("mods"))) {
            return s.anyMatch(f -> { String n = f.getFileName().toString().toLowerCase(); return n.startsWith(prefix) && n.endsWith(".jar"); });
        } catch (Exception e) { return false; }
    }

    static String hwSummary(Map<String, Object> hw) {
        Map<String, Object> g = obj(hw.get("gpu"));
        return "os=" + hw.get("os") + " ram=" + str(hw.get("ram_mb")) + "MB threads=" + str(hw.get("threads")) + " gpu=" + g.get("name")
            + " (" + g.get("class") + ", " + str(g.get("vram_mb")) + "MB) refresh=" + str(hw.get("refresh_hz")) + "Hz";
    }

    /** Phase A (before Prism builds the JVM command): memory keys to write into instance.cfg, or empty. Never throws. */
    static Map<String, String> planMemory(Map<String, Object> tiers, Map<String, Object> tier, Map<String, Object> hw, Map<String, Object> ledger, String cfgText) {
        try {
            Map<String, String> want = desired(tiers, tier, hw, false).get("instance.cfg");
            Map<String, String> w = decideMemory(ledger, props(cfgText), want, obj(tiers.get("adopt_defaults")), str(tier.get("name")), num(tiers.get("version"), 0));
            if (!w.isEmpty()) log("instance.cfg memory for this PC: " + w);
            return w;
        } catch (Throwable t) { log("memory tuning skipped: " + t); return Map.of(); }
    }

    /** Phase B (after the mod sync, before the game starts): options.txt and Distant Horizons. Never throws. */
    static void applyGameSettings(Path mcDir, Map<String, Object> tiers, Map<String, Object> tier, Map<String, Object> hw, Map<String, Object> ledger) {
        long stamp = System.currentTimeMillis();
        String tn = str(tier.get("name"));
        double tv = num(tiers.get("version"), 0);
        Map<String, Object> adopt = obj(tiers.get("adopt_defaults"));
        boolean fs = fpsSyncInstalled(mcDir, tiers);
        Map<String, Map<String, String>> want = desired(tiers, tier, hw, fs);
        try {
            Path f = mcDir.resolve("options.txt");
            String text = Files.exists(f) ? Files.readString(f) : "";
            Map<String, Set<String>> extra = new HashMap<>();
            String fsv = str(obj(tiers.get("fps")).getOrDefault("fps_sync_value", -10.0));
            // FPS-Sync paused: vanilla rejects its -10 and keeps its default 120; that is still our value, not the player's.
            Map<String, Object> e = obj(obj(ledger.get("keys")).get("options.txt:maxFps"));
            if (!fs && fsv.equals(str(e.get("value")))) extra.put("maxFps", Set.of("120", fsv));
            Map<String, String> w = decide(ledger, "options.txt", optionsMap(text), want.get("options.txt"), adopt, Set.of(), tn, tv, extra);
            if (!w.isEmpty()) {
                backup(f, stamp);
                Files.writeString(f, applyOptions(text, w), StandardCharsets.UTF_8);
                log("options.txt set for this PC: " + w + (fs && w.containsKey("maxFps") ? " (maxFps " + fsv + " = FPS-Sync: cap at the monitor's refresh rate)" : ""));
            }
        } catch (Throwable t) { log("options.txt tuning skipped: " + t); }
        try {
            Path f = mcDir.resolve("config/DistantHorizons.toml");
            Map<String, String> ks = new LinkedHashMap<>();
            for (Map.Entry<String, Object> en : obj(tiers.get("dh_keys")).entrySet()) if (!en.getKey().startsWith("_")) ks.put(en.getKey(), str(en.getValue()));
            Map<String, String> wantDh = new LinkedHashMap<>();
            for (Map.Entry<String, String> en : want.get("DistantHorizons.toml").entrySet())
                if (ks.containsKey(en.getKey())) wantDh.put(en.getKey(), en.getValue()); else log("DH key " + en.getKey() + " has no section in tiers.json dh_keys; skipped");
            if (Files.exists(f)) {
                String text = Files.readString(f);
                Map<String, String> cur = tomlValues(text, ks);
                Map<String, String> present = new LinkedHashMap<>();
                for (Map.Entry<String, String> en : wantDh.entrySet())
                    if (cur.containsKey(en.getKey())) present.put(en.getKey(), en.getValue());
                    else log("DistantHorizons.toml has no " + en.getKey() + " under [" + ks.get(en.getKey()) + "] (renamed by a DH update?); skipped");
                Map<String, String> w = decide(ledger, "DistantHorizons.toml", cur, present, adopt, Set.of(), tn, tv, Map.of());
                if (!w.isEmpty()) {
                    backup(f, stamp);
                    Files.writeString(f, applyToml(text, w, ks), StandardCharsets.UTF_8);
                    log("DistantHorizons.toml set for this PC: " + w);
                }
            } else {
                Map<String, String> w = decide(ledger, "DistantHorizons.toml", Map.of(), wantDh, adopt, Set.of(), tn, tv, Map.of());
                if (!w.isEmpty()) {
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, newToml(w, ks), StandardCharsets.UTF_8);
                    log("DistantHorizons.toml created for this PC: " + w);
                }
            }
        } catch (Throwable t) { log("Distant Horizons tuning skipped: " + t); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--watch")) { watch(args); return; }
        if (args.length > 0 && args[0].equals("--detect")) { detectOnly(); return; }
        Path mcDir = Paths.get(System.getenv("INST_MC_DIR"));
        Path instDir = Paths.get(System.getenv("INST_DIR"));
        Files.createDirectories(mcDir.resolve("forever-world"));
        logFile = mcDir.resolve("forever-world/check.log");
        Path packFile = instDir.resolve("mmc-pack.json");
        Path cfgFile = instDir.resolve("instance.cfg");
        Path marker = mcDir.resolve("forever-world/restart-needed");
        Path ledgerFile = mcDir.resolve("forever-world/fw-managed.json");

        // Per-PC tuning inputs. Best effort: any failure here means "no tuning this launch", never a blocked launch.
        Map<String, Object> tiers = null, tier = null, hw = null, ledger = null;
        try {
            tiers = loadTiers(mcDir);
            if (tiers != null) {
                hw = detectHardware();
                tier = selectTier(tiers, hw);
                ledger = loadLedger(ledgerFile);
                if (Boolean.TRUE.equals(ledger.get("corrupt"))) tiers = null;
                else log("this PC: " + hwSummary(hw) + " -> tier " + tier.get("name"));
            }
        } catch (Throwable t) { log("hardware/tier step failed, no tuning this launch: " + t); tiers = null; }

        String wantPack = System.getenv("FW_TEST_MMC_PACK"), wantCfg = System.getenv("FW_TEST_CFG");
        try {
            if (wantPack == null) wantPack = fetch(BASE + "forever-world/mmc-pack.json");
            if (wantCfg == null) { try { wantCfg = fetch(BASE + "forever-world/instance-cfg.properties"); } catch (Exception e) { wantCfg = ""; } }
        } catch (Exception offline) { log("manifest unreachable, skipping the check: " + offline); wantPack = null; }

        String haveCfg = Files.exists(cfgFile) ? Files.readString(cfgFile) : "";
        Map<String, String> enforced = new LinkedHashMap<>();
        if (wantPack != null)
            for (Map.Entry<String, String> e : props(wantCfg == null ? "" : wantCfg).entrySet())
                if (ALLOWED_CFG.contains(e.getKey())) enforced.put(e.getKey(), e.getValue());
                else log("instance-cfg.properties key " + e.getKey() + " is not enforced (per-PC keys come from tiers.json)");
        Map<String, String> memWrite = tiers != null ? planMemory(tiers, tier, hw, ledger, haveCfg) : Map.of();

        List<String> diffs = new ArrayList<>();
        if (wantPack != null) {
            Map<String, String> want = components(wantPack);
            if (want.isEmpty() || !want.containsKey("net.minecraft")) {
                log("manifest has no Minecraft component, ignoring it");
                wantPack = null;
                enforced.clear();
            } else {
                Map<String, String> have = components(Files.readString(packFile));
                for (Map.Entry<String, String> e : want.entrySet())
                    if (!e.getValue().equals(have.get(e.getKey()))) diffs.add(e.getKey() + " " + have.get(e.getKey()) + " -> " + e.getValue());
            }
        }
        Map<String, String> cfgAll = new LinkedHashMap<>(enforced);
        cfgAll.putAll(memWrite);
        boolean cfgDiff = cfgDiffers(haveCfg, cfgAll);
        if (!diffs.isEmpty() || cfgDiff) {
            log("instance differs from the pack: " + diffs + (cfgDiff ? " + instance.cfg" : "") + "; fixing");
            long stamp = System.currentTimeMillis();
            if (!diffs.isEmpty()) {
                Files.copy(packFile, packFile.resolveSibling("mmc-pack.json.bak-" + stamp));
                Files.writeString(packFile, wantPack, StandardCharsets.UTF_8);
            }
            if (cfgDiff) {
                if (Files.exists(cfgFile)) Files.copy(cfgFile, cfgFile.resolveSibling("instance.cfg.bak-" + stamp));
                Files.writeString(cfgFile, applyCfg(haveCfg, cfgAll), StandardCharsets.UTF_8);
            }
            if (ledger != null) try { saveLedger(ledgerFile, ledger); } catch (Exception e) { log("could not save fw-managed.json: " + e); }
            Files.writeString(marker, stamp + ",0");
            StringBuilder wc = new StringBuilder();
            for (Map.Entry<String, String> e : cfgAll.entrySet()) wc.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            Files.writeString(mcDir.resolve("forever-world/want-mmc-pack.json"), wantPack != null ? wantPack : Files.readString(packFile), StandardCharsets.UTF_8);
            Files.writeString(mcDir.resolve("forever-world/want-instance-cfg.properties"), wc.toString(), StandardCharsets.UTF_8);
            if (System.getenv("FW_TEST_NO_WATCH") == null) {
                String java = new File(System.getProperty("java.home"), "bin/java").getPath();
                new ProcessBuilder(java, "-cp", mcDir.resolve("forever-world").toString(), "ForeverWorldCheck", "--watch",
                    instDir.toString(), mcDir.resolve("forever-world").toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            }
            List<String> what = new ArrayList<>(diffs);
            if (memWrite.containsKey("MaxMemAlloc")) what.add("memory for this PC: " + memWrite.get("MaxMemAlloc") + " MB");
            if (what.isEmpty()) what.add("instance settings");
            popup("Toshi's Server updated",
                "The server changed (" + String.join(", ", what) + ").\n"
                + "This instance has been fixed for you.\n\n"
                + "Close Prism completely and open it again, then press Play.\n"
                + "Your settings and keybinds are untouched.");
            System.exit(1);
        }
        if (Files.exists(marker)) {
            String[] parts = Files.readString(marker).trim().split(",");
            long when = Long.parseLong(parts[0]);
            int tries = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            Instant ps = prismStart();
            log("restart marker present; prism start=" + ps + ", marker=" + Instant.ofEpochMilli(when) + ", tries=" + tries);
            boolean stillOld = ps != null && ps.toEpochMilli() < when;
            boolean unknown = ps == null && tries == 0; // cannot tell (other OS): ask once, then trust the fix
            if (stillOld || unknown) {
                Files.writeString(marker, when + "," + (tries + 1));
                if (ledger != null) try { saveLedger(ledgerFile, ledger); } catch (Exception e) { log("could not save fw-managed.json: " + e); }
                popup("Restart Prism", "Almost there: close Prism completely and open it again, then press Play.");
                System.exit(1);
            }
            Files.deleteIfExists(marker);
        }
        if (ledger != null) confirmPending(ledger, props(haveCfg));

        String exe = new File(System.getProperty("java.home"), "bin/java.exe").exists() ? "java.exe" : "java";
        Path bootstrap = mcDir.resolve("mods/packwiz-installer-bootstrap.jar");
        int code;
        if (System.getenv("FW_TEST_NO_SYNC") != null) code = 0;
        else try {
            code = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/" + exe).getPath(), "-jar",
                bootstrap.toString(), PACK).inheritIO().start().waitFor();
        } catch (Exception e) { log("mod sync could not run: " + e); code = 1; }
        if (code != 0) {
            // A failed sync (offline, GitHub down) must never stop the game: Prism aborts the launch on a non-zero exit.
            // If mods are already installed, play with them; only a first run with no mods at all is a real failure.
            boolean haveMods = false;
            try (var s = Files.list(mcDir.resolve("mods"))) { haveMods = s.anyMatch(f -> f.getFileName().toString().endsWith(".jar") && !f.getFileName().toString().startsWith("packwiz-")); }
            catch (Exception ignore) { }
            log("mod sync exited " + code + (haveMods ? "; mods already installed, launching anyway" : "; no mods installed yet"));
            if (haveMods) code = 0;
        }
        if (tiers != null) {
            try {
                applyGameSettings(mcDir, tiers, tier, hw, ledger);
                saveLedger(ledgerFile, ledger);
            } catch (Throwable t) { log("per-PC settings skipped: " + t); }
        }
        System.exit(code);
    }

    /** --detect: print detected hardware, the tier and what it would want, as JSON (CI and manual checks). */
    static void detectOnly() {
        Map<String, Object> hw = detectHardware();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hardware", hw);
        try {
            Map<String, Object> tiers = loadTiers(Paths.get("."));
            if (tiers != null) {
                Map<String, Object> t = selectTier(tiers, hw);
                out.put("tier", t.get("name"));
                out.put("desired", desired(tiers, t, hw, true));
            }
        } catch (Throwable t) { out.put("tier_error", t.toString()); }
        System.out.println(toJson(out, ""));
    }

    /** args: --watch instDir foreverWorldDir. Re-applies the fix for 2 minutes in case Prism saves its old copy back. */
    static void watch(String[] a) throws Exception {
        Path instDir = Paths.get(a[1]), fw = Paths.get(a[2]);
        logFile = fw.resolve("check.log");
        String wantPack = Files.readString(fw.resolve("want-mmc-pack.json"));
        Map<String, String> want = components(wantPack);
        Map<String, String> wantCfg = props(Files.readString(fw.resolve("want-instance-cfg.properties")));
        Path packFile = instDir.resolve("mmc-pack.json"), cfgFile = instDir.resolve("instance.cfg");
        log("watcher started");
        for (int i = 0; i < 120; i++) {
            Thread.sleep(1000);
            try {
                if (!components(Files.readString(packFile)).entrySet().containsAll(want.entrySet())) {
                    Files.writeString(packFile, wantPack, StandardCharsets.UTF_8);
                    log("Prism wrote the old component list back at t+" + i + "s; fixed again");
                }
                if (Files.exists(cfgFile)) {
                    String cur = Files.readString(cfgFile);
                    if (cfgDiffers(cur, wantCfg)) {
                        Files.writeString(cfgFile, applyCfg(cur, wantCfg), StandardCharsets.UTF_8);
                        log("Prism wrote the old instance.cfg back at t+" + i + "s; fixed again");
                    }
                }
            } catch (Exception e) { log("watcher: " + e); }
        }
        log("watcher finished");
    }
}
