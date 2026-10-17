import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Tests for ForeverWorldCheck: tier selection, memory policy, the "player changed it" ledger, file editors, hardware
 * output parsers (recorded fixtures), and end-to-end runs of the real check against fake instances (fake hardware via
 * FW_FAKE_* env vars, no network: FW_TEST_* hooks). Usage: java -cp build:test-build CheckTest <classDir> <tiers.json>
 */
public class CheckTest {
    static int fails = 0, passes = 0;

    static void check(boolean ok, String what) {
        if (ok) { passes++; System.out.println("ok   " + what); }
        else { fails++; System.out.println("FAIL " + what); }
    }

    static void eq(Object want, Object got, String what) { check(Objects.equals(want, got), what + " (want " + want + ", got " + got + ")"); }

    static Map<String, Object> hw(double ram, double threads, String cls, double vram, double hz) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("name", "test"); g.put("class", cls); g.put("vram_mb", vram);
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("ram_mb", ram); h.put("threads", threads); h.put("gpu", g); h.put("refresh_hz", hz);
        return h;
    }

    static String classDir;
    static String tiersText;

    public static void main(String[] a) throws Exception {
        classDir = a[0];
        tiersText = Files.readString(Paths.get(a[1]));
        Map<String, Object> tiers = ForeverWorldCheck.obj(ForeverWorldCheck.parseJson(tiersText));
        unitTiers(tiers);
        unitParsers();
        unitLedger(tiers);
        unitEditors();
        integration();
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    static String tierOf(Map<String, Object> tiers, Map<String, Object> h) { return String.valueOf(ForeverWorldCheck.selectTier(tiers, h).get("name")); }

    static void unitTiers(Map<String, Object> t) {
        eq("ultra", tierOf(t, hw(15023, 16, "discrete", 16304, 180)), "Dylan's PC (15 GiB, 16 threads, RX 9070 XT 16 GB) -> ultra");
        eq("high", tierOf(t, hw(16000, 6, "discrete", 8192, 144)), "16 GB, 6 threads, 8 GB dGPU -> high");
        eq("high", tierOf(t, hw(16384, 8, "apple", 10922, 120)), "Apple M-series 16 GB -> high");
        eq("mid", tierOf(t, hw(16000, 8, "unknown", -1, -1)), "16 GB, unknown GPU -> mid (unknown never reaches high)");
        eq("mid", tierOf(t, hw(8000, 4, "discrete", 4096, 60)), "8 GB, 4 GB dGPU -> mid");
        eq("potato", tierOf(t, hw(16000, 8, "integrated", 512, 60)), "Intel/AMD iGPU -> potato");
        eq("potato", tierOf(t, hw(4000, 4, "discrete", 2048, 60)), "4 GB RAM -> potato");
        eq("potato", tierOf(t, hw(-1, -1, "unknown", -1, -1)), "nothing detected -> potato");
        Map<String, Object> mem = ForeverWorldCheck.obj(t.get("memory"));
        eq(8192L, ForeverWorldCheck.maxMemMb(mem, 15023), "memory: 15 GiB usable (16 GB PC) -> 8192");
        eq(8192L, ForeverWorldCheck.maxMemMb(mem, 64000), "memory: 64 GB -> capped 8192");
        eq(4096L, ForeverWorldCheck.maxMemMb(mem, 7800), "memory: 8 GB PC -> 4096");
        eq(6144L, ForeverWorldCheck.maxMemMb(mem, 12000), "memory: 12 GB PC -> 6144");
        eq(3072L, ForeverWorldCheck.maxMemMb(mem, 3900), "memory: 4 GB PC -> floor 3072");
        eq(3072L, ForeverWorldCheck.maxMemMb(mem, -1), "memory: unknown RAM -> floor 3072");
        Map<String, Map<String, String>> d = ForeverWorldCheck.desired(t, ForeverWorldCheck.selectTier(t, hw(8000, 4, "discrete", 4096, 75)), hw(8000, 4, "discrete", 4096, 75), false);
        eq("75", d.get("options.txt").get("maxFps"), "no FPS-Sync: maxFps = refresh rate");
        d = ForeverWorldCheck.desired(t, ForeverWorldCheck.selectTier(t, hw(8000, 4, "discrete", 4096, 500)), hw(8000, 4, "discrete", 4096, 500), false);
        eq("260", d.get("options.txt").get("maxFps"), "no FPS-Sync: 500 Hz clamps to 260");
        d = ForeverWorldCheck.desired(t, ForeverWorldCheck.selectTier(t, hw(8000, 4, "discrete", 4096, -1)), hw(8000, 4, "discrete", 4096, -1), false);
        check(!d.get("options.txt").containsKey("maxFps"), "no FPS-Sync, unknown refresh: maxFps left alone");
        d = ForeverWorldCheck.desired(t, ForeverWorldCheck.selectTier(t, hw(8000, 4, "discrete", 4096, -1)), hw(8000, 4, "discrete", 4096, -1), true);
        eq("-10", d.get("options.txt").get("maxFps"), "FPS-Sync installed: maxFps -10 (sync to monitor)");
        eq("\"HALF_CHUNK\"", d.get("DistantHorizons.toml").get("maxHorizontalResolution"), "DH strings quoted for TOML");
    }

    static void unitParsers() {
        String lspci = "03:00.0 \"VGA compatible controller\" \"Advanced Micro Devices, Inc. [AMD/ATI]\" \"Navi 48 [Radeon RX 9070/9070 XT/9070 GRE]\" -rc0 -p00 \"Sapphire Technology Limited\" \"Device e491\"\n"
            + "73:00.0 \"VGA compatible controller\" \"Advanced Micro Devices, Inc. [AMD/ATI]\" \"Raphael [Radeon 610M]\" -rcb -p00 \"MSI\" \"Device 7e51\"\n"
            + "00:1f.3 \"Audio device\" \"Intel Corporation\" \"Cannon Lake PCH cAVS\"\n";
        Map<String, String> l = ForeverWorldCheck.parseLspci(lspci);
        eq(2, l.size(), "lspci: two display controllers, audio ignored");
        eq("discrete", ForeverWorldCheck.gpu(l.get("03:00.0"), "amd", 16304).get("class"), "AMD 16 GB VRAM -> discrete");
        eq("integrated", ForeverWorldCheck.gpu(l.get("73:00.0"), "amd", 512).get("class"), "AMD 512 MB (Radeon 610M) -> integrated");
        List<Map<String, Object>> two = new ArrayList<>(List.of(ForeverWorldCheck.gpu("Radeon 610M", "amd", 512), ForeverWorldCheck.gpu("RX 9070 XT", "amd", 16304)));
        eq("RX 9070 XT", ForeverWorldCheck.bestGpu(two).get("name"), "best GPU = the discrete one");
        eq("integrated", ForeverWorldCheck.gpu("Intel(R) UHD Graphics 620", null, -1).get("class"), "Intel UHD -> integrated");
        eq("integrated", ForeverWorldCheck.gpu("Intel(R) Arc(TM) Graphics", null, -1).get("class"), "Intel Arc iGPU (Meteor Lake) -> integrated");
        eq("discrete", ForeverWorldCheck.gpu("Intel(R) Arc(TM) A770 Graphics", null, 16384).get("class"), "Intel Arc A770 -> discrete");
        eq("unknown", ForeverWorldCheck.gpu("Microsoft Basic Display Adapter", null, -1).get("class"), "Basic display adapter -> unknown");
        List<Map<String, Object>> nv = ForeverWorldCheck.parseNvidiaSmi("NVIDIA GeForce RTX 3060, 12288\n");
        eq(12288.0, nv.get(0).get("vram_mb"), "nvidia-smi VRAM");
        String regDesc = "\r\nHKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}\\0000\r\n    DriverDesc    REG_SZ    NVIDIA GeForce RTX 4070\r\n\r\n"
            + "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}\\0001\r\n    DriverDesc    REG_SZ    Intel(R) UHD Graphics 770\r\n\r\nEnd of search: 2 match(es) found.\r\n";
        String regMem = "\r\nHKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}\\0000\r\n    HardwareInformation.qwMemorySize    REG_QWORD    0x300000000\r\n\r\nEnd of search: 1 match(es) found.\r\n";
        List<Map<String, Object>> w = ForeverWorldCheck.parseWindows(null, regDesc, regMem);
        eq(2, w.size(), "Windows registry: two adapters");
        eq(12288.0, ForeverWorldCheck.bestGpu(w).get("vram_mb"), "Windows qwMemorySize 0x300000000 = 12 GB");
        eq("NVIDIA GeForce RTX 4070", ForeverWorldCheck.bestGpu(w).get("name"), "Windows best adapter");
        List<Map<String, Object>> cim = ForeverWorldCheck.parseWindows("AMD Radeon RX 6700 XT|4293918720\r\n", null, null);
        eq(-1.0, cim.get(0).get("vram_mb"), "Windows CIM AdapterRAM capped at 4 GB -> unknown");
        eq("discrete", cim.get(0).get("class"), "Windows CIM RX name -> discrete");
        String mac = "{\"SPDisplaysDataType\":[{\"_name\":\"kHW_AppleM1Item\",\"sppci_model\":\"Apple M1\",\"spdisplays_vendor\":\"sppci_vendor_Apple\",\"sppci_cores\":\"8\"}]}";
        List<Map<String, Object>> m = ForeverWorldCheck.parseMacProfiler(mac, 8192);
        eq("apple", m.get(0).get("class"), "macOS Apple Silicon -> apple");
        eq(5461.0, m.get(0).get("vram_mb"), "macOS unified memory = 2/3 of RAM");
        String intelMac = "{\"SPDisplaysDataType\":[{\"sppci_model\":\"Intel UHD Graphics 630\",\"spdisplays_vendor\":\"Intel\",\"spdisplays_vram_shared\":\"1536 MB\"},"
            + "{\"sppci_model\":\"AMD Radeon Pro 5500M\",\"spdisplays_vendor\":\"sppci_vendor_amd\",\"spdisplays_vram\":\"8 GB\"}]}";
        m = ForeverWorldCheck.parseMacProfiler(intelMac, 16384);
        eq("AMD Radeon Pro 5500M", ForeverWorldCheck.bestGpu(m).get("name"), "Intel Mac: dGPU chosen");
        eq(8192.0, ForeverWorldCheck.bestGpu(m).get("vram_mb"), "Intel Mac: 8 GB VRAM");
        check(ForeverWorldCheck.parseMacProfiler("not json", 1).isEmpty(), "macOS garbage output -> no GPU, no crash");
        check(ForeverWorldCheck.parseWindows(null, null, null).isEmpty(), "Windows no output -> no GPU, no crash");
    }

    static Map<String, String> m(String... kv) { Map<String, String> r = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) r.put(kv[i], kv[i + 1]); return r; }

    static String owner(Map<String, Object> ledger, String id) { return String.valueOf(ForeverWorldCheck.obj(ForeverWorldCheck.obj(ledger.get("keys")).get(id)).get("owner")); }

    static void unitLedger(Map<String, Object> t) {
        Map<String, Object> adopt = ForeverWorldCheck.obj(t.get("adopt_defaults"));
        Map<String, Object> led = new LinkedHashMap<>();
        Map<String, String> w = ForeverWorldCheck.decide(led, "options.txt", m("renderDistance", "12", "maxFps", "180"), m("renderDistance", "8", "maxFps", "-10", "simulationDistance", "6"), adopt, Set.of(), "mid", 1, Map.of());
        eq(m("renderDistance", "8", "simulationDistance", "6"), w, "no ledger: default 12 adopted, missing key written, player's maxFps 180 kept");
        eq("user", owner(led, "options.txt:maxFps"), "maxFps 180 marked as the player's");
        // later launch: still ours, tiers changed -> update
        w = ForeverWorldCheck.decide(led, "options.txt", m("renderDistance", "8", "maxFps", "180", "simulationDistance", "6"), m("renderDistance", "10", "maxFps", "-10", "simulationDistance", "6"), adopt, Set.of(), "mid", 2, Map.of());
        eq(m("renderDistance", "10"), w, "ours and unchanged: a tiers change updates it; player key still untouched");
        // the player changes renderDistance in game
        w = ForeverWorldCheck.decide(led, "options.txt", m("renderDistance", "16", "maxFps", "180", "simulationDistance", "6"), m("renderDistance", "10", "maxFps", "-10", "simulationDistance", "6"), adopt, Set.of(), "mid", 2, Map.of());
        check(w.isEmpty(), "player changed renderDistance -> nothing written");
        eq("user", owner(led, "options.txt:renderDistance"), "renderDistance now the player's");
        // even if they set it back to our old value, it stays theirs
        w = ForeverWorldCheck.decide(led, "options.txt", m("renderDistance", "10", "maxFps", "180", "simulationDistance", "6"), m("renderDistance", "4", "maxFps", "-10", "simulationDistance", "6"), adopt, Set.of(), "potato", 3, Map.of());
        check(!w.containsKey("renderDistance"), "player-owned key is never touched again");
        // Dylan's DH: 256/BLOCK/8 with no ledger -> all his
        Map<String, Object> dl = new LinkedHashMap<>();
        w = ForeverWorldCheck.decide(dl, "DistantHorizons.toml", m("lodChunkRenderDistanceRadius", "256", "maxHorizontalResolution", "\"BLOCK\"", "numberOfThreads", "8"),
            m("lodChunkRenderDistanceRadius", "96", "maxHorizontalResolution", "\"HALF_CHUNK\"", "numberOfThreads", "4"), adopt, Set.of(), "mid", 1, Map.of());
        check(w.isEmpty(), "Dylan's DH 256/BLOCK/8 never lowered (no ledger)");
        // old zip default 96/HALF_CHUNK/4 adopted
        Map<String, Object> fl = new LinkedHashMap<>();
        w = ForeverWorldCheck.decide(fl, "DistantHorizons.toml", m("lodChunkRenderDistanceRadius", "96", "maxHorizontalResolution", "\"HALF_CHUNK\"", "numberOfThreads", "4"),
            m("lodChunkRenderDistanceRadius", "256", "maxHorizontalResolution", "\"BLOCK\"", "numberOfThreads", "8"), adopt, Set.of(), "ultra", 1, Map.of());
        eq(3, w.size(), "friend on the old zip defaults: all three DH keys tuned");
        // memory: override off -> ours; override on, no ledger -> the player's
        Map<String, Object> ml = new LinkedHashMap<>();
        w = ForeverWorldCheck.decideMemory(ml, m("OverrideMemory", "false", "MaxMemAlloc", "4096", "MinMemAlloc", "512"), m("OverrideMemory", "true", "MaxMemAlloc", "8192", "MinMemAlloc", "1024"), adopt, "ultra", 1);
        eq(m("OverrideMemory", "true", "MaxMemAlloc", "8192", "MinMemAlloc", "1024"), w, "memory override off -> all three written");
        ml = new LinkedHashMap<>();
        w = ForeverWorldCheck.decideMemory(ml, m("OverrideMemory", "true", "MaxMemAlloc", "12000", "MinMemAlloc", "512"), m("OverrideMemory", "true", "MaxMemAlloc", "8192", "MinMemAlloc", "1024"), adopt, "ultra", 1);
        check(w.isEmpty(), "player set memory in Prism -> untouched");
        eq("user", owner(ml, "instance.cfg:MaxMemAlloc"), "player's MaxMemAlloc marked user");
        // Prism writes its stale instance.cfg back before the restart: still ours, write again
        ml = new LinkedHashMap<>();
        ForeverWorldCheck.decideMemory(ml, m("OverrideMemory", "false"), m("OverrideMemory", "true", "MaxMemAlloc", "8192", "MinMemAlloc", "1024"), adopt, "ultra", 1);
        w = ForeverWorldCheck.decideMemory(ml, m("OverrideMemory", "false"), m("OverrideMemory", "true", "MaxMemAlloc", "8192", "MinMemAlloc", "1024"), adopt, "ultra", 1);
        eq("true", w.get("OverrideMemory"), "pending write reverted by Prism -> re-applied, not the player's");
        ForeverWorldCheck.confirmPending(ml, m("OverrideMemory", "true", "MaxMemAlloc", "8192", "MinMemAlloc", "1024"));
        w = ForeverWorldCheck.decideMemory(ml, m("OverrideMemory", "true", "MaxMemAlloc", "6000", "MinMemAlloc", "1024"), m("OverrideMemory", "true", "MaxMemAlloc", "8192", "MinMemAlloc", "1024"), adopt, "ultra", 1);
        check(w.isEmpty() && "user".equals(owner(ml, "instance.cfg:MaxMemAlloc")), "after confirm, player edit of MaxMemAlloc in Prism is respected");
        // FPS-Sync paused: vanilla resets -10 to 120; still ours
        Map<String, Object> fs = new LinkedHashMap<>();
        ForeverWorldCheck.decide(fs, "options.txt", m("maxFps", "120"), m("maxFps", "-10"), adopt, Set.of(), "mid", 1, Map.of());
        w = ForeverWorldCheck.decide(fs, "options.txt", m("maxFps", "120"), m("maxFps", "144"), adopt, Set.of(), "mid", 1, Map.of("maxFps", Set.of("120", "-10")));
        eq(m("maxFps", "144"), w, "FPS-Sync paused: vanilla's reset 120 is still ours -> refresh-rate cap 144");
        // JSON round trip of a ledger
        String js = ForeverWorldCheck.toJson(fs, "");
        eq(js, ForeverWorldCheck.toJson(ForeverWorldCheck.parseJson(js), ""), "ledger JSON round trip");
    }

    static void unitEditors() {
        String toml = "[server]\n\t[common.multiThreading]\n\t\t#\n\t\tnumberOfThreads = 8\n[client]\n\t\t[client.advanced.debugging]\n\t\t\tnumberOfThreads = 99\n\t\t\t[client.advanced.graphics.quality]\n\t\t\t\tmaxHorizontalResolution = \"BLOCK\"\n\t\t\t\tlodChunkRenderDistanceRadius = 256\n";
        Map<String, String> ks = m("lodChunkRenderDistanceRadius", "client.advanced.graphics.quality", "maxHorizontalResolution", "client.advanced.graphics.quality", "numberOfThreads", "common.multiThreading");
        Map<String, String> v = ForeverWorldCheck.tomlValues(toml, ks);
        eq(m("numberOfThreads", "8", "maxHorizontalResolution", "\"BLOCK\"", "lodChunkRenderDistanceRadius", "256"), v, "TOML values read per section (same key elsewhere ignored)");
        String out = ForeverWorldCheck.applyToml(toml, m("numberOfThreads", "4", "lodChunkRenderDistanceRadius", "96"), ks);
        check(out.contains("\t\tnumberOfThreads = 4\n") && out.contains("\t\t\tnumberOfThreads = 99") && out.contains("\t\t\t\tlodChunkRenderDistanceRadius = 96"), "TOML edit keeps indentation and other sections");
        eq(toml.split("\n", -1).length, out.split("\n", -1).length, "TOML edit keeps the line count");
        String opt = "version:4671\nrenderDistance:12\nmaxFps:120\n";
        eq("version:4671\nrenderDistance:8\nmaxFps:120\nsimulationDistance:6\n", ForeverWorldCheck.applyOptions(opt, m("renderDistance", "8", "simulationDistance", "6")), "options.txt line edit + append");
        eq("renderDistance:8\n", ForeverWorldCheck.applyOptions("", m("renderDistance", "8")), "options.txt created when missing");
        String cfg = "[General]\nOverrideMemory=false\nname=x\n";
        String c2 = ForeverWorldCheck.applyCfg(cfg, m("OverrideMemory", "true", "MaxMemAlloc", "8192", "Bogus", "1"));
        check(c2.contains("OverrideMemory=true") && c2.contains("MaxMemAlloc=8192") && !c2.contains("Bogus"), "instance.cfg: managed keys written, unknown key refused");
    }

    // ------------------------------------------------------------------ end to end: the real class against fake instances

    static final String MMC = "{\n    \"components\": [\n        {\n            \"uid\": \"net.minecraft\",\n            \"version\": \"26.3\"\n        }\n    ],\n    \"formatVersion\": 1\n}\n";
    static final String CFG_WANT = "OverrideJavaArgs=true\nJvmArgs=-XX:+UseZGC\nMaxMemAlloc=99999\n";

    static Path makeInstance(String name, String cfg, String options, String dh) throws Exception {
        Path root = Files.createTempDirectory("fw-inst-" + name);
        Path mc = root.resolve("minecraft");
        Files.createDirectories(mc.resolve("mods"));
        Files.createDirectories(mc.resolve("config"));
        Files.writeString(root.resolve("mmc-pack.json"), MMC);
        Files.writeString(root.resolve("instance.cfg"), cfg);
        if (options != null) Files.writeString(mc.resolve("options.txt"), options);
        if (dh != null) Files.writeString(mc.resolve("config/DistantHorizons.toml"), dh);
        Files.writeString(mc.resolve("mods/fps-sync-1.0.0+26.3.jar"), "fake");
        Files.writeString(mc.resolve("mods/sodium-fabric-0.9.2+mc26.3.jar"), "fake");
        return root;
    }

    static int runCheck(Path inst, Map<String, String> env) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", classDir, "ForeverWorldCheck");
        Map<String, String> e = pb.environment();
        e.put("INST_DIR", inst.toString());
        e.put("INST_MC_DIR", inst.resolve("minecraft").toString());
        e.put("FW_TEST_MMC_PACK", MMC);
        e.put("FW_TEST_CFG", CFG_WANT);
        e.put("FW_TEST_TIERS", tiersText);
        e.put("FW_TEST_NO_SYNC", "1");
        e.put("FW_TEST_NO_POPUP", "1");
        e.put("FW_TEST_NO_WATCH", "1");
        e.put("FW_BASE", "http://127.0.0.1:9/");
        e.putAll(env);
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start().waitFor();
    }

    static Map<String, String> dylanEnv() { return m("FW_FAKE_RAM_MB", "15023", "FW_FAKE_THREADS", "16", "FW_FAKE_GPU", "discrete,16304,AMD Radeon RX 9070 XT", "FW_FAKE_HZ", "180"); }

    static String read(Path p) throws Exception { return Files.exists(p) ? Files.readString(p) : ""; }

    static final String CFG_BASE = "[General]\nConfigVersion=1.3\nInstanceType=OneSix\nJvmArgs=-XX:+UseZGC\nOverrideJavaArgs=true\nOverrideMemory=false\nname=Test\n";
    static final String DH_OLDZIP = "[common.multiThreading]\n\tnumberOfThreads = 4\n\n[client.advanced.graphics.quality]\n\tmaxHorizontalResolution = \"HALF_CHUNK\"\n\tlodChunkRenderDistanceRadius = 96\n";
    static final String DH_DYLAN = "[server]\n\t[common.multiThreading]\n\t\tnumberOfThreads = 8\n[client]\n\t\t\t[client.advanced.graphics.quality]\n\t\t\t\tmaxHorizontalResolution = \"BLOCK\"\n\t\t\t\tlodChunkRenderDistanceRadius = 256\n";

    static void integration() throws Exception {
        // 1. Friend on the old zip, potato laptop: memory needs a Prism restart, then options + DH are tuned.
        Path a = makeInstance("friend", CFG_BASE, "version:4671\nrenderDistance:12\nmaxFps:120\n", DH_OLDZIP);
        Map<String, String> pot = m("FW_FAKE_RAM_MB", "7800", "FW_FAKE_THREADS", "4", "FW_FAKE_GPU", "integrated,512,Intel UHD 620", "FW_FAKE_HZ", "60");
        eq(1, runCheck(a, pot), "e2e friend: first launch writes memory and asks for a Prism restart (exit 1)");
        Map<String, String> cfg = ForeverWorldCheck.props(read(a.resolve("instance.cfg")));
        eq("true", cfg.get("OverrideMemory"), "e2e friend: OverrideMemory=true");
        eq("4096", cfg.get("MaxMemAlloc"), "e2e friend: MaxMemAlloc=4096 (8 GB PC)");
        eq("1024", cfg.get("MinMemAlloc"), "e2e friend: MinMemAlloc=1024");
        check(!read(a.resolve("instance.cfg")).contains("99999"), "e2e: instance-cfg.properties cannot force a memory value");
        eq(1, runCheck(a, pot), "e2e friend: second launch, Prism start unknown -> asks once");
        eq(0, runCheck(a, pot), "e2e friend: third launch passes");
        Map<String, String> o = ForeverWorldCheck.optionsMap(read(a.resolve("minecraft/options.txt")));
        eq("6", o.get("renderDistance"), "e2e friend: renderDistance 6 (potato)");
        eq("5", o.get("simulationDistance"), "e2e friend: simulationDistance 5");
        eq("-10", o.get("maxFps"), "e2e friend: FPS-Sync on");
        String dh = read(a.resolve("minecraft/config/DistantHorizons.toml"));
        check(dh.contains("lodChunkRenderDistanceRadius = 48") && dh.contains("\"FOUR_BLOCKS\"") && dh.contains("numberOfThreads = 2"), "e2e friend: DH 48/FOUR_BLOCKS/2");
        String led = read(a.resolve("minecraft/forever-world/fw-managed.json"));
        check(led.contains("\"options.txt:renderDistance\"") && led.contains("\"instance.cfg:MaxMemAlloc\""), "e2e friend: ledger written");
        check(!led.contains("\"pending\""), "e2e friend: memory confirmed after the restart");
        // the player raises render distance and memory; both must survive further launches
        Files.writeString(a.resolve("minecraft/options.txt"), read(a.resolve("minecraft/options.txt")).replace("renderDistance:6", "renderDistance:10"));
        Files.writeString(a.resolve("instance.cfg"), read(a.resolve("instance.cfg")).replace("MaxMemAlloc=4096", "MaxMemAlloc=5000"));
        for (int i = 0; i < 3; i++) runCheck(a, pot);
        eq("10", ForeverWorldCheck.optionsMap(read(a.resolve("minecraft/options.txt"))).get("renderDistance"), "e2e friend: player's renderDistance 10 survives 3 launches");
        eq("5000", ForeverWorldCheck.props(read(a.resolve("instance.cfg"))).get("MaxMemAlloc"), "e2e friend: player's MaxMemAlloc 5000 survives 3 launches");
        eq(0, runCheck(a, pot), "e2e friend: launches normally afterwards");

        // 2. Dylan-like instance: his values are never lowered.
        Path d = makeInstance("dylan", CFG_BASE, "version:4671\nrenderDistance:12\nsimulationDistance:12\nmaxFps:180\nenableVsync:true\n", DH_DYLAN);
        runCheck(d, dylanEnv()); runCheck(d, dylanEnv());
        eq(0, runCheck(d, dylanEnv()), "e2e Dylan: third launch passes");
        Map<String, String> dc = ForeverWorldCheck.props(read(d.resolve("instance.cfg")));
        eq("8192", dc.get("MaxMemAlloc"), "e2e Dylan: memory 8192");
        Map<String, String> dopt = ForeverWorldCheck.optionsMap(read(d.resolve("minecraft/options.txt")));
        eq("180", dopt.get("maxFps"), "e2e Dylan: his maxFps 180 kept");
        eq("12", dopt.get("renderDistance"), "e2e Dylan: renderDistance 12");
        eq("12", dopt.get("simulationDistance"), "e2e Dylan: simulationDistance 12");
        eq(DH_DYLAN, read(d.resolve("minecraft/config/DistantHorizons.toml")), "e2e Dylan: DH file byte-identical (256/BLOCK/8)");

        // 3. Broken inputs: never block the launch, never change settings.
        Path b = makeInstance("broken", CFG_BASE.replace("OverrideMemory=false", "OverrideMemory=true\nMaxMemAlloc=4096"), "renderDistance:12\n", DH_OLDZIP);
        eq(0, runCheck(b, m("FW_TEST_TIERS", "{ this is not json")), "e2e malformed tiers.json: launch proceeds (exit 0)");
        eq("renderDistance:12\n", read(b.resolve("minecraft/options.txt")), "e2e malformed tiers.json: options untouched");
        eq(0, runCheck(b, m("FW_FAKE_GPU", "garbage", "FW_FAKE_RAM_MB", "notanumber")), "e2e garbage fake hardware: launch proceeds");
        Path c = makeInstance("nofiles", CFG_BASE, null, null);
        runCheck(c, pot); runCheck(c, pot);
        eq(0, runCheck(c, pot), "e2e no options.txt/DH config: launch passes");
        check(read(c.resolve("minecraft/options.txt")).contains("renderDistance:6") && read(c.resolve("minecraft/config/DistantHorizons.toml")).contains("[client.advanced.graphics.quality]"), "e2e missing files created as partial files");
        Files.writeString(c.resolve("minecraft/forever-world/fw-managed.json"), "{broken");
        Files.writeString(c.resolve("minecraft/options.txt"), "renderDistance:3\n");
        eq(0, runCheck(c, pot), "e2e corrupt ledger: launch proceeds");
        eq("renderDistance:3\n", read(c.resolve("minecraft/options.txt")), "e2e corrupt ledger: nothing changed");
        Path r = makeInstance("renamed", CFG_BASE, "", "[client.advanced.graphics.quality]\n\tsomethingNew = 1\n");
        runCheck(r, pot); runCheck(r, pot);
        eq(0, runCheck(r, pot), "e2e DH keys renamed: launch passes");
        eq("[client.advanced.graphics.quality]\n\tsomethingNew = 1\n", read(r.resolve("minecraft/config/DistantHorizons.toml")), "e2e DH keys renamed: file untouched");
        check(read(r.resolve("minecraft/forever-world/check.log")).contains("renamed by a DH update"), "e2e DH keys renamed: logged");
    }
}
