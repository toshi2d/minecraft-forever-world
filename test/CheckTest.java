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
        eq(6656L, ForeverWorldCheck.maxMemMb(mem, 15023), "memory: 15 GiB usable (16 GB PC) -> 6656 (0.4 x 16 GiB)");
        // Prism 11.1.1 EnsureAvailableMemory: warns when max x 0.9 > free RAM. Dylan's PC had 6963 MiB free at a launch.
        check(ForeverWorldCheck.maxMemMb(mem, 15023) * 0.9 <= 6963, "memory: 16 GB PC with 6.8 GB free -> no Prism low-RAM warning (8192 x 0.9 = 7373 warned)");
        eq(8192L, ForeverWorldCheck.maxMemMb(mem, 32000), "memory: 32 GB -> capped 8192");
        eq(8192L, ForeverWorldCheck.maxMemMb(mem, 64000), "memory: 64 GB -> capped 8192");
        eq(3072L, ForeverWorldCheck.maxMemMb(mem, 7800), "memory: 8 GB PC -> 3072 (floor)");
        eq(5120L, ForeverWorldCheck.maxMemMb(mem, 12000), "memory: 12 GB PC -> 5120");
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
        eq("\"BLOCK\"", d.get("DistantHorizons.toml").get("maxHorizontalResolution"), "DH strings quoted for TOML");
        eq("\"0.5\"", d.get("DistantHorizons.toml").get("farFogStart"), "mid = Balanced: fog start 0.5 (DH stores floats as strings)");
        eq("2", d.get("DistantHorizons.toml").get("numberOfThreads"), "mid (Balanced) on 4 threads: ceil(4 x 0.5) = 2");
        Map<String, Object> dh = hw(15023, 16, "discrete", 16304, 180);
        Map<String, String> dd = ForeverWorldCheck.desired(t, ForeverWorldCheck.selectTier(t, dh), dh, true).get("DistantHorizons.toml");
        eq("12", dd.get("numberOfThreads"), "ultra (D) on 16 threads: ceil(16 x 0.75) = 12 (= Dylan's measured D)");
        eq("4", dd.get("maxTexturedLodDetailLevel"), "ultra = D: textured LODs to level 4");
        eq("false", dd.get("enableNoiseTexture"), "ultra = D: noise texture off");
        eq("\"EXTREME\"", dd.get("verticalQuality"), "ultra = D: vertical EXTREME");
        check(!dd.containsKey("generationRequestRateLimit") && !dd.containsKey("playerBandwidthLimit"), "D ships no server-side limits");
        eq("20", ForeverWorldCheck.desired(t, ForeverWorldCheck.selectTier(t, dh), dh, true).get("options.txt").get("renderDistance"), "ultra: renderDistance 20");
        Map<String, Object> h8 = hw(16000, 8, "discrete", 8192, 144);
        eq("6", ForeverWorldCheck.desired(t, ForeverWorldCheck.selectTier(t, h8), h8, true).get("DistantHorizons.toml").get("numberOfThreads"), "high (D) on 8 threads: ceil(8 x 0.75) = 6");
        Map<String, Object> h32 = hw(64000, 32, "discrete", 24000, 144);
        eq("12", ForeverWorldCheck.desired(t, ForeverWorldCheck.selectTier(t, h32), h32, true).get("DistantHorizons.toml").get("numberOfThreads"), "D on 32 threads: capped at 12");
        Map<String, Object> hp = hw(7800, 4, "integrated", 512, 60);
        Map<String, String> pd = ForeverWorldCheck.desired(t, ForeverWorldCheck.selectTier(t, hp), hp, true).get("DistantHorizons.toml");
        check("96".equals(pd.get("lodChunkRenderDistanceRadius")) && "\"TWO_BLOCKS\"".equals(pd.get("maxHorizontalResolution")) && "2".equals(pd.get("numberOfThreads")), "potato = Light: 96 / TWO_BLOCKS / 2 threads");
        check(ForeverWorldCheck.dhUntouched(t, dh).get("numberOfThreads").contains("8") && !ForeverWorldCheck.dhUntouched(t, dh).get("numberOfThreads").contains("12"), "DH's own thread default on 16 threads = ceil(16 x 0.5) = 8 (plus old zip 4)");
        check(ForeverWorldCheck.dhUntouched(t, dh).get("lodChunkRenderDistanceRadius").containsAll(Set.of("256", "96")), "untouched radius = DH default 256 + old zip 96");
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
        check(w.isEmpty(), "decide(): values outside the adopt list (here 256/BLOCK/8 with only the old zip list) are the player's");
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
        // Prism instance.cfg is INI: memory keys must be under [General]; under [UI] Prism ignores them (game ran at 4096 MB).
        String prism = "[General]\nConfigVersion=1.3\nOverrideMemory=false\nname=x\n\n[UI]\nmods_Page\\Columns=@ByteArray(AAAA)\n";
        String c3 = ForeverWorldCheck.applyCfg(prism, m("OverrideMemory", "true", "MaxMemAlloc", "6656", "MinMemAlloc", "1024"));
        String gen = c3.substring(0, c3.indexOf("[UI]")), ui = c3.substring(c3.indexOf("[UI]"));
        check(gen.contains("OverrideMemory=true\n") && gen.contains("MaxMemAlloc=6656\n") && gen.contains("MinMemAlloc=1024\n"), "instance.cfg: new memory keys land under [General], not at the end");
        check(!ui.contains("MemAlloc") && ui.contains("mods_Page"), "instance.cfg: [UI] untouched, no memory keys there");
        eq("6656", ForeverWorldCheck.props(c3).get("MaxMemAlloc"), "instance.cfg: props reads [General]");
        // a copy where an earlier script appended them at the end (inside [UI]): read as absent, moved to [General]
        String bad = prism + "OverrideMemory=true\nMaxMemAlloc=8192\nMinMemAlloc=1024\n";
        check(!ForeverWorldCheck.props(bad).containsKey("MaxMemAlloc") && "false".equals(ForeverWorldCheck.props(bad).get("OverrideMemory")), "instance.cfg: keys under [UI] are not instance settings (props ignores them)");
        check(ForeverWorldCheck.cfgDiffers(bad, m("OverrideMemory", "true", "MaxMemAlloc", "8192", "MinMemAlloc", "1024")), "instance.cfg: misplaced keys count as a difference -> rewritten");
        String c4 = ForeverWorldCheck.applyCfg(bad, m("OverrideMemory", "true", "MaxMemAlloc", "6656", "MinMemAlloc", "1024"));
        String g4 = c4.substring(0, c4.indexOf("[UI]")), u4 = c4.substring(c4.indexOf("[UI]"));
        check(g4.contains("OverrideMemory=true") && g4.contains("MaxMemAlloc=6656") && !u4.contains("MemAlloc") && !u4.contains("OverrideMemory"), "instance.cfg: misplaced memory keys moved from [UI] to [General]");
        eq(1, c4.split("MaxMemAlloc=", -1).length - 1, "instance.cfg: exactly one MaxMemAlloc after the move");
        String c5 = ForeverWorldCheck.applyCfg("name=old\nOverrideMemory=false\n[UI]\nx=1\n", m("OverrideMemory", "true", "MaxMemAlloc", "3072"));
        check(c5.startsWith("[General]\n") && c5.indexOf("MaxMemAlloc=3072") < c5.indexOf("[UI]") && c5.contains("OverrideMemory=true"), "instance.cfg without a [General] header: header added, keys before [UI]");
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
        eq("3072", cfg.get("MaxMemAlloc"), "e2e friend: MaxMemAlloc=3072 (8 GB PC)");
        eq("1024", cfg.get("MinMemAlloc"), "e2e friend: MinMemAlloc=1024");
        check(!read(a.resolve("instance.cfg")).contains("99999"), "e2e: instance-cfg.properties cannot force a memory value");
        eq(1, runCheck(a, pot), "e2e friend: second launch, Prism start unknown -> asks once");
        eq(0, runCheck(a, pot), "e2e friend: third launch passes");
        Map<String, String> o = ForeverWorldCheck.optionsMap(read(a.resolve("minecraft/options.txt")));
        eq("6", o.get("renderDistance"), "e2e friend: renderDistance 6 (potato)");
        eq("5", o.get("simulationDistance"), "e2e friend: simulationDistance 5");
        eq("-10", o.get("maxFps"), "e2e friend: FPS-Sync on");
        String dh = read(a.resolve("minecraft/config/DistantHorizons.toml"));
        check(dh.contains("lodChunkRenderDistanceRadius = 96") && dh.contains("\"TWO_BLOCKS\"") && dh.contains("numberOfThreads = 2"), "e2e friend: old zip DH file -> Light 96/TWO_BLOCKS/2");
        check(read(a.resolve("minecraft/forever-world/check.log")).contains("partial file"), "e2e friend: partial zip DH file logged, missing keys wait for DH's full file");
        String led = read(a.resolve("minecraft/forever-world/fw-managed.json"));
        check(led.contains("\"options.txt:renderDistance\"") && led.contains("\"instance.cfg:MaxMemAlloc\""), "e2e friend: ledger written");
        check(!led.contains("\"pending\""), "e2e friend: memory confirmed after the restart");
        // the player raises render distance and memory; both must survive further launches
        Files.writeString(a.resolve("minecraft/options.txt"), read(a.resolve("minecraft/options.txt")).replace("renderDistance:6", "renderDistance:10"));
        Files.writeString(a.resolve("instance.cfg"), read(a.resolve("instance.cfg")).replace("MaxMemAlloc=3072", "MaxMemAlloc=5000"));
        for (int i = 0; i < 3; i++) runCheck(a, pot);
        eq("10", ForeverWorldCheck.optionsMap(read(a.resolve("minecraft/options.txt"))).get("renderDistance"), "e2e friend: player's renderDistance 10 survives 3 launches");
        eq("5000", ForeverWorldCheck.props(read(a.resolve("instance.cfg"))).get("MaxMemAlloc"), "e2e friend: player's MaxMemAlloc 5000 survives 3 launches");
        eq(0, runCheck(a, pot), "e2e friend: launches normally afterwards");

        // 2. Dylan-like instance (real DH file = DH defaults, his maxFps 180): D lands, his own values stay.
        Path d = makeInstance("dylan", CFG_BASE, "version:4671\nrenderDistance:12\nsimulationDistance:12\nmaxFps:180\nenableVsync:true\n", dhFull(8));
        runCheck(d, dylanEnv()); runCheck(d, dylanEnv());
        eq(0, runCheck(d, dylanEnv()), "e2e Dylan: third launch passes");
        Map<String, String> dc = ForeverWorldCheck.props(read(d.resolve("instance.cfg")));
        eq("6656", dc.get("MaxMemAlloc"), "e2e Dylan: memory 6656 (under [General])");
        Map<String, String> dopt = ForeverWorldCheck.optionsMap(read(d.resolve("minecraft/options.txt")));
        eq("180", dopt.get("maxFps"), "e2e Dylan: his maxFps 180 kept (FPS-Sync -10 only replaces vanilla 120)");
        eq("20", dopt.get("renderDistance"), "e2e Dylan: renderDistance 12 (vanilla default) -> 20 (ultra)");
        Map<String, String> ks = dhKeys();
        Map<String, String> dv = ForeverWorldCheck.tomlValues(read(d.resolve("minecraft/config/DistantHorizons.toml")), ks);
        eq("12", dv.get("numberOfThreads"), "e2e Dylan: DH threads 8 (DH default) -> 12");
        eq("\"EXTREME\"", dv.get("verticalQuality"), "e2e Dylan: verticalQuality MEDIUM -> EXTREME");
        eq("4", dv.get("maxTexturedLodDetailLevel"), "e2e Dylan: maxTexturedLodDetailLevel 2 -> 4");
        eq("false", dv.get("enableNoiseTexture"), "e2e Dylan: noise texture off");
        eq("\"0.6\"", dv.get("farFogStart"), "e2e Dylan: fog start 0.6");
        check(read(d.resolve("minecraft/config/DistantHorizons.toml")).contains("_version = 5"), "e2e Dylan: rest of DH's file kept");
        // he changes vertical quality in the DH menu; DH resets fog start to its default (config-version bump)
        Path dt = d.resolve("minecraft/config/DistantHorizons.toml");
        Files.writeString(dt, read(dt).replace("verticalQuality = \"EXTREME\"", "verticalQuality = \"HIGH\"").replace("farFogStart = \"0.6\"", "farFogStart = \"0.4\""));
        Files.writeString(d.resolve("minecraft/options.txt"), read(d.resolve("minecraft/options.txt")).replace("renderDistance:20", "renderDistance:16"));
        for (int i = 0; i < 3; i++) runCheck(d, dylanEnv());
        dv = ForeverWorldCheck.tomlValues(read(dt), ks);
        eq("\"HIGH\"", dv.get("verticalQuality"), "e2e Dylan: player-changed verticalQuality HIGH survives 3 launches");
        eq("\"0.6\"", dv.get("farFogStart"), "e2e Dylan: value reset to DH's default (DH config wipe) is ours again -> 0.6");
        eq("16", ForeverWorldCheck.optionsMap(read(d.resolve("minecraft/options.txt"))).get("renderDistance"), "e2e Dylan: player's renderDistance 16 survives");
        // a player who tuned DH himself (non-default values, no ledger): never touched
        String tuned = dhFull(8).replace("lodChunkRenderDistanceRadius = 256", "lodChunkRenderDistanceRadius = 512").replace("numberOfThreads = 8", "numberOfThreads = 10").replace("verticalQuality = \"MEDIUM\"", "verticalQuality = \"PIXEL_ART\"");
        Path tu = makeInstance("tuned", CFG_BASE, "maxFps:120\n", tuned);
        for (int i = 0; i < 3; i++) runCheck(tu, dylanEnv());
        Map<String, String> tv = ForeverWorldCheck.tomlValues(read(tu.resolve("minecraft/config/DistantHorizons.toml")), ks);
        check("512".equals(tv.get("lodChunkRenderDistanceRadius")) && "10".equals(tv.get("numberOfThreads")) && "\"PIXEL_ART\"".equals(tv.get("verticalQuality")), "e2e player-tuned DH (512 / 10 threads / PIXEL_ART): untouched");
        eq("\"EXTREME\"", tv.get("horizontalQuality"), "e2e player-tuned DH: keys still at DH default are tuned");
        eq("-10", ForeverWorldCheck.optionsMap(read(tu.resolve("minecraft/options.txt"))).get("maxFps"), "e2e maxFps 120 (vanilla default) -> -10 with FPS-Sync");

        // 3. Memory keys an earlier script appended into [UI]: moved under [General].
        String misplaced = CFG_BASE + "\n[UI]\nmods_Page\\Columns=@ByteArray(AAAA)\nOverrideMemory=true\nMaxMemAlloc=8192\nMinMemAlloc=1024\n";
        Path mp = makeInstance("misplaced", misplaced, "", dhFull(8));
        eq(1, runCheck(mp, dylanEnv()), "e2e misplaced memory keys: rewrite + restart request");
        String mc = read(mp.resolve("instance.cfg"));
        String mg = mc.substring(0, mc.indexOf("[UI]")), mu = mc.substring(mc.indexOf("[UI]"));
        check(mg.contains("OverrideMemory=true") && mg.contains("MaxMemAlloc=6656") && mg.contains("MinMemAlloc=1024") && !mu.contains("MemAlloc") && !mu.contains("OverrideMemory") && mu.contains("mods_Page"), "e2e misplaced memory keys: now under [General], gone from [UI]");

        // 4. Broken inputs: never block the launch, never change settings.
        Path b = makeInstance("broken", CFG_BASE.replace("OverrideMemory=false", "OverrideMemory=true\nMaxMemAlloc=4096"), "renderDistance:12\n", DH_OLDZIP);
        eq(0, runCheck(b, m("FW_TEST_TIERS", "{ this is not json")), "e2e malformed tiers.json: launch proceeds (exit 0)");
        eq("renderDistance:12\n", read(b.resolve("minecraft/options.txt")), "e2e malformed tiers.json: options untouched");
        eq(0, runCheck(b, m("FW_FAKE_GPU", "garbage", "FW_FAKE_RAM_MB", "notanumber")), "e2e garbage fake hardware: launch proceeds");
        Path c = makeInstance("nofiles", CFG_BASE, null, null);
        runCheck(c, pot); runCheck(c, pot);
        eq(0, runCheck(c, pot), "e2e no options.txt/DH config: launch passes");
        check(read(c.resolve("minecraft/options.txt")).contains("renderDistance:6"), "e2e missing options.txt created as a partial file");
        check(!Files.exists(c.resolve("minecraft/config/DistantHorizons.toml")), "e2e no DH config yet: not created by us (DH writes its own)");
        check(read(c.resolve("minecraft/forever-world/check.log")).contains("DistantHorizons.toml not there yet"), "e2e no DH config yet: logged");
        Files.writeString(c.resolve("minecraft/config/DistantHorizons.toml"), dhFull(2)); // DH's first start on a 4-thread PC
        eq(0, runCheck(c, pot), "e2e DH file appeared: next launch passes");
        Map<String, String> cv = ForeverWorldCheck.tomlValues(read(c.resolve("minecraft/config/DistantHorizons.toml")), ks);
        check("96".equals(cv.get("lodChunkRenderDistanceRadius")) && "\"LOW\"".equals(cv.get("verticalQuality")) && "2".equals(cv.get("numberOfThreads")) && "true".equals(cv.get("enableCaveCulling")), "e2e DH file appeared: Light applied on the next launch");
        Files.writeString(c.resolve("minecraft/forever-world/fw-managed.json"), "{broken");
        Files.writeString(c.resolve("minecraft/options.txt"), "renderDistance:3\n");
        eq(0, runCheck(c, pot), "e2e corrupt ledger: launch proceeds");
        eq("renderDistance:3\n", read(c.resolve("minecraft/options.txt")), "e2e corrupt ledger: nothing changed");
        String renamed = "_version = 6\n\n[client.advanced.graphics.quality]\n\tsomethingNew = 1\n";
        Path r = makeInstance("renamed", CFG_BASE, "", renamed);
        runCheck(r, pot); runCheck(r, pot);
        eq(0, runCheck(r, pot), "e2e DH keys renamed: launch passes");
        eq(renamed, read(r.resolve("minecraft/config/DistantHorizons.toml")), "e2e DH keys renamed: file untouched");
        check(read(r.resolve("minecraft/forever-world/check.log")).contains("renamed by a DH update"), "e2e DH keys renamed: logged");
    }

    static Map<String, String> dhKeys() {
        Map<String, String> ks = new LinkedHashMap<>();
        Map<String, Object> t = ForeverWorldCheck.obj(ForeverWorldCheck.parseJson(tiersText));
        for (Map.Entry<String, Object> e : ForeverWorldCheck.obj(t.get("dh_keys")).entrySet()) if (!e.getKey().startsWith("_")) ks.put(e.getKey(), String.valueOf(e.getValue()));
        return ks;
    }

    /** A full DH 3.3.4 client file (as DH writes it) with every tuned key at DH's default; threads = DH's default for the PC. */
    static String dhFull(int dhDefaultThreads) {
        Map<String, Object> t = ForeverWorldCheck.obj(ForeverWorldCheck.parseJson(tiersText));
        Map<String, Object> def = ForeverWorldCheck.obj(t.get("dh_defaults"));
        Map<String, List<String>> bySec = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : dhKeys().entrySet()) {
            Object v = def.get(e.getKey());
            String lit = v instanceof Map ? Integer.toString(dhDefaultThreads) : v instanceof String ? "\"" + v + "\"" : ForeverWorldCheck.str(v);
            bySec.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add("\t\t#\n\t\t# comment\n\t\t" + e.getKey() + " = " + lit);
        }
        StringBuilder sb = new StringBuilder("_version = 5\n\n[server]\n\tgenerationRequestRateLimit = 20\n\n");
        for (Map.Entry<String, List<String>> e : bySec.entrySet()) sb.append("\t[").append(e.getKey()).append("]\n").append(String.join("\n", e.getValue())).append("\n\n");
        return sb.toString();
    }
}
