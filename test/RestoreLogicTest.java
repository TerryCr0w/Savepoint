import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.bukkit.command.CommandSender;

/**
 * 对编译产物 Savepoint 的恢复逻辑做真实校验：反射调用 private static 方法 + 临时目录文件演练。
 * 运行：node build.cjs test
 * 控制台输出保持英文（Windows 代码页会把中文变成乱码）。
 */
public class RestoreLogicTest {

    static final String MANIFEST = "savepoint-manifest.txt";
    static final String W = "bskyblock_world";
    static final String OVER = W + "/dimensions/minecraft/overworld/";
    static final String NETHER = W + "/dimensions/minecraft/the_nether/";
    static final String END = W + "/dimensions/minecraft/the_end/";

    static int failures = 0;
    static int checks = 0;
    static Method mReadZipUnits, mResolveTargets, mPartialWorlds, mRestoreDirs, mTopLevelDirs;
    static Method mUnitKeyOf, mUnitPrefixOf, mManifestText, mZipTree;
    static java.lang.reflect.Constructor<?> ctorUnit;
    static Field fPrefixes;

    public static void main(String[] args) throws Exception {
        Class<?> plugin = Class.forName("Savepoint");
        Class<?> zipUnitsCls = Class.forName("Savepoint$ZipUnits");
        Class<?> unitCls = Class.forName("Savepoint$Unit");
        mReadZipUnits = plugin.getDeclaredMethod("readZipUnits", File.class, CommandSender.class);
        mResolveTargets = plugin.getDeclaredMethod("resolveTargets", List.class, zipUnitsCls,
                CommandSender.class);
        mPartialWorlds = plugin.getDeclaredMethod("partialWorlds", Set.class);
        mRestoreDirs = plugin.getDeclaredMethod("restoreDirs", Path.class, List.class);
        mTopLevelDirs = plugin.getDeclaredMethod("topLevelDirs", List.class);
        mUnitKeyOf = plugin.getDeclaredMethod("unitKeyOf", Path.class);
        mUnitPrefixOf = plugin.getDeclaredMethod("unitPrefixOf", Path.class);
        mManifestText = plugin.getDeclaredMethod("manifestText", List.class);
        mZipTree = plugin.getDeclaredMethod("zipTree", ZipOutputStream.class, unitCls);
        ctorUnit = unitCls.getDeclaredConstructor(String.class, Path.class, String.class,
                List.class);
        fPrefixes = zipUnitsCls.getDeclaredField("prefixes");
        for (Method m : new Method[] {mReadZipUnits, mResolveTargets, mPartialWorlds, mRestoreDirs,
                mTopLevelDirs, mUnitKeyOf, mUnitPrefixOf, mManifestText, mZipTree}) {
            m.setAccessible(true);
        }
        ctorUnit.setAccessible(true);
        fPrefixes.setAccessible(true);

        Path tmp = Files.createTempDirectory("savepoint-test");
        List<String> wholeEntries = List.of(W + "/level.dat", W + "/uid.dat",
                W + "/players/steve.dat", OVER + "region/r.0.0.mca", NETHER + "region/r.0.0.mca",
                END + "region/r.0.0.mca");
        List<String> dimEntries = List.of(OVER + "region/r.0.0.mca", OVER + "level.dat_old");

        File wholeV1 = zipFile(tmp, "whole-v1.zip", W + "\n", wholeEntries);
        File dimV1 = zipFile(tmp, "dim-v1.zip", W + "@overworld\n", dimEntries);
        File wholeLegacy = zipFile(tmp, "whole-legacy.zip", null, wholeEntries);
        File dimLegacy = zipFile(tmp, "dim-legacy.zip", null, dimEntries);
        File broken = zipFile(tmp, "broken.zip", W + "\n", dimEntries);

        System.out.println("\n== unit detection ==");
        eq("v1 whole-world manifest", unitMap(wholeV1),
                "{" + W + "=" + W + "/, " + W + "@overworld=" + OVER + ", " + W + "@the_nether="
                        + NETHER + ", " + W + "@the_end=" + END + "}");
        eq("v1 single-dimension manifest", unitMap(dimV1),
                "{bskyblock_world@overworld=" + OVER + "}");
        eq("legacy whole-world, inferred", keys(wholeLegacy),
                "[bskyblock_world, bskyblock_world@overworld, bskyblock_world@the_nether, "
                        + "bskyblock_world@the_end]");
        eq("legacy single-dimension, inferred", keys(dimLegacy), "[bskyblock_world@overworld]");
        eq("manifest does not match content -> reject", unitMap(broken), "null");

        System.out.println("\n== restore target resolution ==");
        eq("whole backup + world name", targets(wholeV1, W), "[bskyblock_world/]");
        eq("whole backup + unique bare dimension", targets(wholeV1, "overworld"), "[" + OVER + "]");
        eq("dimension backup + world name -> reject", targetsOrNull(dimV1, W), "null");
        eq("dimension backup + world@dim", targets(dimV1, W + "@overworld"), "[" + OVER + "]");
        eq("dimension backup + unique bare dim", targets(dimV1, "overworld"), "[" + OVER + "]");
        eq("dimension backup + no unit given", targets(dimV1), "[" + OVER + "]");
        eq("legacy whole, no unit (dims folded away)", targets(wholeLegacy), "[bskyblock_world/]");
        eq("legacy whole + one dimension", targets(wholeLegacy, W + "@the_nether"), "[" + NETHER
                + "]");
        eq("legacy whole + two dimensions", targets(wholeLegacy, W + "@overworld", W + "@the_end"),
                "[" + OVER + ", " + END + "]");
        File twoWorlds = zipFile(tmp, "two-worlds.zip", null, List.of(
                W + "/level.dat", OVER + "region/r.0.0.mca", "world_nether/level.dat",
                "world_nether/dimensions/minecraft/overworld/region/r.0.0.mca"));
        eq("ambiguous bare dimension -> reject", targetsOrNull(twoWorlds, "overworld"), "null");
        eq("ambiguous name written in full resolves",
                targets(twoWorlds, "world_nether@overworld"),
                "[world_nether/dimensions/minecraft/overworld/]");

        System.out.println("\n== partial-dimension gate (needs --force) ==");
        eq("whole restore is not partial", partial(wholeV1, W), "[]");
        eq("legacy whole restore is not partial", partial(wholeLegacy), "[]");
        eq("dimension restore is partial", partial(dimV1, W + "@overworld"), "[bskyblock_world]");
        eq("legacy one dimension is partial", partial(wholeLegacy, W + "@the_nether"),
                "[bskyblock_world]");
        eq("whole + dimension mixed is not partial", partial(wholeLegacy, W, W + "@the_nether"),
                "[]");

        System.out.println("\n== rename plan ==");
        eq("whole world: only the world root", renamePlan(wholeV1, W), "[" + W + "]");
        eq("single dimension: only that dimension", renamePlan(dimV1, W + "@overworld"),
                "[" + W + "/dimensions/minecraft/overworld]");
        eq("two dimensions: both dirs", renamePlan(wholeLegacy, W + "@overworld", W + "@the_end"),
                "[" + W + "/dimensions/minecraft/overworld, "
                        + W + "/dimensions/minecraft/the_end]");

        System.out.println("\n== backup -> restore round trip (real zipTree + manifestText) ==");
        Path srv = Files.createDirectories(tmp.resolve("server-c"));
        touch(srv, W + "/level.dat", "L");
        touch(srv, W + "/players/steve.dat", "P");
        touch(srv, W + "/session.lock", "LOCK");
        touch(srv, OVER + "region/r.0.0.mca", "O");
        touch(srv, OVER + "session.lock", "LOCK");
        touch(srv, NETHER + "region/r.0.0.mca", "N");
        Path relWhole = srv.relativize(srv.resolve(W));
        Path relOver = srv.relativize(srv.resolve(OVER.substring(0, OVER.length() - 1)));
        eq("unitKeyOf whole world", call(mUnitKeyOf, relWhole), W);
        eq("unitKeyOf dimension", call(mUnitKeyOf, relOver), W + "@overworld");
        eq("unitPrefixOf whole world", call(mUnitPrefixOf, relWhole), W + "/");
        eq("unitPrefixOf dimension", call(mUnitPrefixOf, relOver), OVER);

        File rtWhole = roundTripZip(srv, "rt-whole.zip", relWhole, srv.resolve(W));
        eq("manifest entry written", hasEntry(rtWhole, MANIFEST), "true");
        eq("manifest body", manifestBody(rtWhole), "# Savepoint manifest v1\n" + W + "\n");
        eq("whole zip: every dimension included, session.lock excluded", sortedEntriesOf(rtWhole),
                "[" + W + "/dimensions/minecraft/overworld/region/r.0.0.mca, "
                        + W + "/dimensions/minecraft/the_nether/region/r.0.0.mca, "
                        + W + "/level.dat, " + W + "/players/steve.dat]");
        eq("whole round trip: units", unitMap(rtWhole),
                "{" + W + "=" + W + "/, " + W + "@overworld=" + OVER + ", " + W + "@the_nether="
                        + NETHER + "}");
        eq("whole round trip: targets", targets(rtWhole, W), "[" + W + "/]");
        eq("whole round trip: no force needed", partial(rtWhole, W), "[]");
        eq("whole round trip: rename plan", renamePlan(rtWhole, W), "[" + W + "]");
        eq("whole round trip: one dimension still restorable", targets(rtWhole, W + "@overworld"),
                "[" + OVER + "]");
        eq("whole round trip: one dimension needs force", partial(rtWhole, W + "@overworld"),
                "[" + W + "]");
        eq("whole round trip: unknown dimension rejected",
                targetsOrNull(rtWhole, W + "@the_wrong"), "null");

        File rtDim = roundTripZip(srv, "rt-dim.zip", relOver, srv.resolve(OVER.substring(0,
                OVER.length() - 1)));
        eq("dimension manifest body", manifestBody(rtDim),
                "# Savepoint manifest v1\n" + W + "@overworld\n");
        eq("dimension zip entries", sortedEntriesOf(rtDim),
                "[" + OVER + "region/r.0.0.mca]");
        eq("dimension round trip: units", unitMap(rtDim), "{" + W + "@overworld=" + OVER + "}");
        eq("dimension round trip: targets", targets(rtDim, W + "@overworld"), "[" + OVER + "]");
        eq("dimension round trip: needs force", partial(rtDim, W + "@overworld"), "[" + W + "]");
        eq("dimension round trip: world name rejected", targetsOrNull(rtDim, W), "null");
        eq("dimension round trip: rename plan", renamePlan(rtDim, W + "@overworld"),
                "[" + W + "/dimensions/minecraft/overworld]");

        File overstated = srv.resolve("rt-overstated.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(overstated))) {
            zos.putNextEntry(new ZipEntry(MANIFEST));
            zos.write(("# Savepoint manifest v1\n" + W + "\n").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            mZipTree.invoke(null, zos, newUnit(relOver, srv.resolve(OVER.substring(0,
                    OVER.length() - 1))));
        }
        eq("manifest overstates real content -> reject", unitMap(overstated), "null");

        System.out.println("\n== filesystem drill ==");
        Path root = Files.createDirectories(tmp.resolve("server-a"));
        seedWorld(root);
        applyPlan(root, wholeV1, targetList(wholeV1, W));
        eq("whole restore: level.dat rolled back", read(root, W + "/level.dat"), "OLD");
        eq("whole restore: player data rolled back", read(root, W + "/players/steve.dat"), "OLD");
        eq("whole restore: overworld rolled back", read(root, OVER + "region/r.0.0.mca"), "OLD");
        eq("whole restore: the_nether rolled back", read(root, NETHER + "region/r.0.0.mca"), "OLD");
        eq("whole restore: the_end rolled back", read(root, END + "region/r.0.0.mca"), "OLD");
        eq("whole restore: no temp dirs left", leftovers(root), "[]");
        eq("manifest not unpacked into server root", Files.exists(root.resolve(MANIFEST)), false);

        Path root2 = Files.createDirectories(tmp.resolve("server-b"));
        seedWorld(root2);
        applyPlan(root2, dimV1, targetList(dimV1, W + "@overworld"));
        eq("dimension restore: overworld rolled back", read(root2, OVER + "region/r.0.0.mca"),
                "OLD");
        eq("dimension restore: level.dat untouched", read(root2, W + "/level.dat"), "NOW");
        eq("dimension restore: players untouched", read(root2, W + "/players/steve.dat"), "NOW");
        eq("dimension restore: the_nether still there", read(root2, NETHER + "region/r.0.0.mca"),
                "NOW");
        eq("dimension restore: the_end still there", read(root2, END + "region/r.0.0.mca"), "NOW");
        eq("dimension restore: no temp dirs left", leftovers(root2), "[]");

        System.out.println("\n== plugin.yml / config.yml sanity ==");
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        Map<String, Object> py;
        try (var in = Files.newInputStream(Paths.get("resources", "plugin.yml"))) {
            py = yaml.load(in);
        }
        eq("plugin.yml parses", py != null, true);
        eq("plugin name", py.get("name"), "Savepoint");
        eq("main class matches compiled class", py.get("main"), plugin.getSimpleName());
        Map<String, Object> cmds = castMap(py.get("commands"));
        eq("command labels", String.valueOf(cmds.keySet()), "[savepoint, sp]");
        Map<String, Object> spCmd = castMap(cmds.get("savepoint"));
        eq("savepoint has no aliases (sp is a top-level command)", spCmd.get("aliases"), null);
        eq("command permission", spCmd.get("permission"), "savepoint.admin");
        Map<String, Object> spAlias = castMap(cmds.get("sp"));
        eq("sp command permission", spAlias.get("permission"), "savepoint.admin");
        eq("declared permission matches",
                String.valueOf(castMap(py.get("permissions")).keySet()), "[savepoint.admin]");
        String usage = String.valueOf(spCmd.get("usage"));
        eq("usage carries no legacy /wb", usage.contains("/wb"), false);
        eq("usage uses /savepoint", usage.startsWith("/savepoint"), true);
        Map<String, Object> cfg;
        try (var in = Files.newInputStream(Paths.get("resources", "config.yml"))) {
            cfg = yaml.load(in);
        }
        eq("config.yml parses", cfg != null, true);
        eq("config keys", String.valueOf(new ArrayList<>(cfg.keySet())),
                "[backup-dir, interval-minutes, last-offline-delay-minutes, keep-count, "
                        + "when-empty, restore-backup-before]");

        System.out.println("\n" + (failures == 0
                ? "ALL PASS (" + checks + " checks)" : failures + " FAILED of " + checks));
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    static Object call(Method m, Path p) throws Exception {
        return m.invoke(null, p);
    }

    /** 用生产端的键与前缀推导构造一个 Unit。 */
    static Object newUnit(Path rel, Path folder) throws Exception {
        String key = (String) mUnitKeyOf.invoke(null, rel);
        String prefix = (String) mUnitPrefixOf.invoke(null, rel);
        return ctorUnit.newInstance(key, folder, prefix, new ArrayList<Path>());
    }

    /** 用生产代码（manifestText + zipTree）真实打包一个单位，验证两端契约。 */
    static File roundTripZip(Path srv, String name, Path rel, Path folder) throws Exception {
        Object unit = newUnit(rel, folder);
        File f = srv.resolve(name).toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f))) {
            zos.putNextEntry(new ZipEntry(MANIFEST));
            String manifest = (String) mManifestText.invoke(null, List.of(unit));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            mZipTree.invoke(null, zos, unit);
        }
        return f;
    }

    static boolean hasEntry(File zip, String name) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return true;
                }
                zin.closeEntry();
            }
        }
        return false;
    }

    static String manifestBody(File zip) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals(MANIFEST)) {
                    return new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                }
                zin.closeEntry();
            }
        }
        return "<no manifest>";
    }

    static String sortedEntriesOf(File zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.getName().equals(MANIFEST)) {
                    names.add(e.getName());
                }
                zin.closeEntry();
            }
        }
        names.sort(null);
        return String.valueOf(names);
    }

    static File zipFile(Path dir, String name, String manifest, List<String> entries)
            throws IOException {
        File f = dir.resolve(name).toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f))) {
            if (manifest != null) {
                put(zos, MANIFEST, "# Savepoint manifest v1\n" + manifest);
            }
            for (String e : entries) {
                put(zos, e, "OLD");
            }
        }
        return f;
    }

    static void put(ZipOutputStream zos, String name, String body) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(body.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    static Object units(File zip) throws Exception {
        return mReadZipUnits.invoke(null, zip, null);
    }

    @SuppressWarnings("unchecked")
    static String unitMap(File zip) throws Exception {
        Object u = units(zip);
        if (u == null) {
            return "null";
        }
        return String.valueOf(new LinkedHashMap<>((Map<String, String>) fPrefixes.get(u)));
    }

    @SuppressWarnings("unchecked")
    static String keys(File zip) throws Exception {
        Object u = units(zip);
        return u == null ? "null"
                : String.valueOf(((Map<String, String>) fPrefixes.get(u)).keySet());
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> targetsMap(File zip, String... tokens) throws Exception {
        Object u = units(zip);
        if (u == null) {
            return null;
        }
        return (Map<String, String>) mResolveTargets.invoke(null, Arrays.asList(tokens), u, null);
    }

    static String targets(File zip, String... tokens) throws Exception {
        Map<String, String> m = targetsMap(zip, tokens);
        return m == null ? "UNEXPECTED-NULL" : String.valueOf(new ArrayList<>(m.values()));
    }

    static String targetsOrNull(File zip, String... tokens) throws Exception {
        return targetsMap(zip, tokens) == null ? "null" : "UNEXPECTED-RESOLVED";
    }

    static String partial(File zip, String... tokens) throws Exception {
        Map<String, String> m = targetsMap(zip, tokens);
        if (m == null) {
            return "UNEXPECTED-NULL";
        }
        return String.valueOf(mPartialWorlds.invoke(null, m.keySet()));
    }

    @SuppressWarnings("unchecked")
    static List<String[]> dirs(Path root, List<String> prefixes) throws Exception {
        return (List<String[]>) mRestoreDirs.invoke(null, root, prefixes);
    }

    @SuppressWarnings("unchecked")
    static List<String[]> topLevel(Path root, List<String> prefixes) throws Exception {
        return (List<String[]>) mTopLevelDirs.invoke(null, dirs(root, prefixes));
    }

    /** 改名计划，以相对路径显示（root 用占位目录 R）。 */
    static String renamePlan(File zip, String... tokens) throws Exception {
        Map<String, String> m = targetsMap(zip, tokens);
        if (m == null) {
            return "UNEXPECTED-NULL";
        }
        List<String> names = new ArrayList<>();
        for (String[] pd : topLevel(Paths.get("R"), new ArrayList<>(m.values()))) {
            names.add(pd[1].substring(2).replace('\\', '/'));
        }
        return String.valueOf(names);
    }

    static List<String> targetList(File zip, String... tokens) throws Exception {
        return new ArrayList<>(targetsMap(zip, tokens).values());
    }

    static void seedWorld(Path root) throws IOException {
        touch(root, W + "/level.dat", "NOW");
        touch(root, W + "/players/steve.dat", "NOW");
        touch(root, OVER + "region/r.0.0.mca", "NOW");
        touch(root, NETHER + "region/r.0.0.mca", "NOW");
        touch(root, END + "region/r.0.0.mca", "NOW");
    }

    static void touch(Path root, String rel, String body) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path root, String rel) {
        Path p = root.resolve(rel);
        if (!Files.isRegularFile(p)) {
            return "<missing>";
        }
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<error:" + e + ">";
        }
    }

    /** 复刻 doRestoreOnExit 的文件操作：顶层改名 -> 按前缀解压 -> 删除旧目录。 */
    static void applyPlan(Path root, File zip, List<String> prefixes) throws Exception {
        List<String> extract = new ArrayList<>();
        List<Path> pre = new ArrayList<>();
        for (String[] pd : topLevel(root, prefixes)) {
            Path dir = Paths.get(pd[1]);
            if (Files.exists(dir)) {
                Path renamed = dir.resolveSibling(dir.getFileName() + ".pre-restore");
                Files.move(dir, renamed);
                pre.add(renamed);
            }
            extract.add(pd[0]);
        }
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                boolean match = false;
                for (String p : extract) {
                    if (name.startsWith(p)) {
                        match = true;
                        break;
                    }
                }
                if (match && !e.isDirectory()) {
                    Path out = root.resolve(name);
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zin.closeEntry();
            }
        }
        for (Path p : pre) {
            deleteTree(p);
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(walk.collect(java.util.stream.Collectors.toList()));
            all.sort(java.util.Comparator.reverseOrder());
            for (Path p : all) {
                Files.delete(p);
            }
        }
    }

    static String leftovers(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.getFileName().toString().contains("pre-restore")) {
                    out.add(root.relativize(p).toString());
                }
            }
        }
        return String.valueOf(out);
    }

    static void eq(String label, Object actual, Object expected) {
        checks++;
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + label
                + (ok ? "" : "\n            expected: " + expected + "\n            actual:   "
                        + actual));
    }
}
