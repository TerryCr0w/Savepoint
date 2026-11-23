import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

public final class Savepoint extends JavaPlugin implements Listener, TabCompleter {

    private static final String VERSION = "1.4.0";
    /** 内部标记沿用历史 wb 前缀：改名会让旧版本遗留在服务器根目录的锁定文件认不出来。 */
    private static final String LOCK_NAME = "__wb_lock__";
    private static final String MANIFEST_NAME = "savepoint-manifest.txt";
    /** 解压失败时打进改名目录里的标记：告诉下次启动的清理逻辑“这是安全副本，别删”。 */
    private static final String KEEP_MARKER = "SAVEPOINT_OLD_DATA_KEEP";

    private boolean periodicActive = false;
    private long periodicNextRun = 0;
    private long offlineDeadline = 0;
    private boolean emptyAnnounced = false;
    private volatile boolean backupRunning = false;
    private volatile String lastBackup = "none";
    private String pendingConfirm = null;
    private byte[] originalWhitelist = null;
    private byte[] originalProperties = null;

    /** 备份/还原单位：世界组（根文件+全部维度）或单个维度目录。 */
    private static final class Unit {
        final String key;
        final Path folder;
        final String prefix;
        final List<Path> excludeDirs;

        Unit(String key, Path folder, String prefix, List<Path> excludeDirs) {
            this.key = key;
            this.folder = folder;
            this.prefix = prefix;
            this.excludeDirs = excludeDirs;
        }
    }

    /** 备份包内的单位：单位键（小写）-> 该单位在 zip 内的路径前缀。 */
    private static final class ZipUnits {
        final Map<String, String> prefixes = new LinkedHashMap<>();

        List<String> keys() {
            return new ArrayList<>(prefixes.keySet());
        }
    }

    /** 控制台走英文（避免 Windows 代码页乱码），游戏内走中文。 */
    private static void tell(CommandSender sender, String en, String zh) {
        if (sender != null) {
            sender.sendMessage(sender instanceof ConsoleCommandSender ? en : zh);
        }
    }

    @Override
    public void onEnable() {
        migrateLegacyConfig();
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("savepoint") != null) {
            getCommand("savepoint").setTabCompleter(this);
        }
        // sp 是独立顶层命令（非 plugin.yml aliases），需单独挂补全器，
        // 否则 brigadier 化 Paper 上 /sp <Tab> 不会触发子命令提示。
        if (getCommand("sp") != null) {
            getCommand("sp").setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        // 上次回档改名换位的旧世界目录可能因本进程仍占用句柄而没删掉，
        // 此时本进程已重启、句柄已释放，异步清理掉这些 *.pre-restore-* 残留。
        Bukkit.getScheduler().runTaskAsynchronously(this, this::cleanupStalePreRestores);
        getLogger().info("Enabled. interval " + intervalMinutes() + "m, offline delay "
                + delayMinutes() + "m, backup dir: " + backupDir().getAbsolutePath());
    }

    /** 删除服务器根目录里上次回档遗留的 *.pre-restore-* 旧世界副本目录。 */
    private void cleanupStalePreRestores() {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        File[] leftovers = root.toFile().listFiles(
                (d, n) -> new File(d, n).isDirectory() && n.contains(".pre-restore-"));
        if (leftovers == null || leftovers.length == 0) {
            return;
        }
        for (File dir : leftovers) {
            if (Files.exists(dir.toPath().resolve(KEEP_MARKER))) {
                getLogger().warning("Skipping " + dir.getName()
                        + " — marked as a failed-restore safety copy, recover from it or"
                        + " delete it manually once done.");
                continue;
            }
            if (deleteRecursive(dir)) {
                getLogger().info("Cleaned up leftover old world copy: " + dir.getName());
            } else {
                getLogger().warning("Could not delete leftover " + dir.getName()
                        + ", remove it manually.");
            }
        }
    }

    /** 1.4.0 起插件由 WorldBackup 改名为 Savepoint，数据目录随插件名变化，先搬旧配置。 */
    private void migrateLegacyConfig() {
        File config = new File(getDataFolder(), "config.yml");
        File legacy = new File(new File(getDataFolder().getParentFile(), "WorldBackup"), "config.yml");
        if (config.exists() || !legacy.isFile()) {
            return;
        }
        try {
            getDataFolder().mkdirs();
            Files.copy(legacy.toPath(), config.toPath());
            getLogger().info("Imported settings from plugins/WorldBackup/config.yml");
        } catch (IOException e) {
            getLogger().warning("Could not import plugins/WorldBackup/config.yml: " + e);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        offlineDeadline = 0;
    }

    // ------------------------------------------------------------------
    // scheduling
    // ------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        boolean online = !Bukkit.getOnlinePlayers().isEmpty();
        long intervalMs = intervalMinutes() * 60_000L;
        long delayMs = delayMinutes() * 60_000L;

        if (online) {
            emptyAnnounced = false;
            offlineDeadline = 0;
            if (!periodicActive) {
                periodicActive = true;
                periodicNextRun = now + intervalMs;
            }
            if (now >= periodicNextRun) {
                periodicNextRun = now + intervalMs;
                startBackup("interval backup", null, null, true, null);
            }
        } else if (emptyMode().equals("always")) {
            // 空服：先按离线倒计时做一次 final backup（抓下线路点），再转入每 interval 分钟的间隔备份。
            if (!emptyAnnounced) {
                emptyAnnounced = true;
                offlineDeadline = now + delayMs;
                getLogger().info("All players offline (empty mode: always), final backup in "
                        + delayMinutes() + "m, then interval backups every " + intervalMinutes() + "m.");
            }
            if (offlineDeadline > 0 && now >= offlineDeadline) {
                offlineDeadline = 0;
                startBackup("final backup", null, null, true, null);
                periodicActive = true;
                periodicNextRun = now + intervalMs;
            } else if (offlineDeadline == 0 && periodicActive && now >= periodicNextRun) {
                periodicNextRun = now + intervalMs;
                startBackup("interval backup", null, null, true, null);
            }
        } else {
            // final-only：末次玩家离线倒计时后做一次 final backup，然后停止直到有人再上线。
            if (!emptyAnnounced) {
                emptyAnnounced = true;
                periodicActive = false;
                offlineDeadline = now + delayMs;
                getLogger().info("All players offline (empty mode: final-only), final backup in "
                        + delayMinutes() + "m.");
            }
            if (offlineDeadline > 0 && now >= offlineDeadline) {
                offlineDeadline = -1;
                startBackup("final backup", null, null, true, null);
            }
        }
    }

    private String emptyModeCached = null;

    private String emptyMode() {
        String configured = getConfig().getString("when-empty", "auto").toLowerCase(Locale.ROOT);
        if (!configured.equals("auto")) {
            return configured.equals("final-only") ? "final-only" : "always";
        }
        if (emptyModeCached == null) {
            emptyModeCached = detectServerStopsWhenEmpty() ? "final-only" : "always";
            getLogger().info("Detected server "
                    + (emptyModeCached.equals("final-only")
                            ? "pauses when empty: stop backing up after final backup."
                            : "keeps running when empty: continue periodic backups."));
        }
        return emptyModeCached;
    }

    private boolean detectServerStopsWhenEmpty() {
        Integer global = readNumber(Paths.get("config/paper-global.yml"),
                "settings", "pause-when-empty-seconds");
        if (global != null && global >= 0) {
            return true;
        }
        for (World world : Bukkit.getWorlds()) {
            File folder = world.getWorldFolder();
            if (folder == null) {
                continue;
            }
            Integer perWorld = readNumber(folder.toPath().resolve("paper-world.yml"),
                    "settings", "pause-when-empty-seconds");
            if (perWorld != null && perWorld >= 0) {
                return true;
            }
        }
        return false;
    }

    private static Integer readNumber(Path file, String... path) {
        Object v = readPath(file, path);
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Object readPath(Path file, String... path) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            Object node = new Yaml().load(Files.newInputStream(file));
            for (String key : path) {
                if (!(node instanceof Map)) {
                    return null;
                }
                node = ((Map<String, Object>) node).get(key);
            }
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // backup units
    // ------------------------------------------------------------------

    /**
     * 单位模型（适配 26.2 新式布局：维度位于 <世界组>/dimensions/<ns>/<dim>/，
     * 每个 World 的 getWorldFolder() 可能直接指向维度目录）：
     *   - 世界组（服务器根下第一级目录）= 整体单位，zip 内含根文件与全部维度；
     *   - 每个已加载维度目录 = 子单位，键为 <世界组>@<维度目录名>。
     */
    private Map<String, Unit> allUnits() {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Map<String, Unit> units = new LinkedHashMap<>();
        for (World world : Bukkit.getWorlds()) {
            File folderFile = world.getWorldFolder();
            if (folderFile == null) {
                continue;
            }
            Path folder = folderFile.toPath().toAbsolutePath().normalize();
            if (!Files.isDirectory(folder)) {
                continue;
            }
            Path rel;
            try {
                rel = root.relativize(folder);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (rel.getNameCount() == 0) {
                continue;
            }
            Path groupRel = rel.getName(0);
            String groupKey = unitKeyOf(groupRel);
            units.computeIfAbsent(groupKey, g -> new Unit(g, root.resolve(groupRel),
                    unitPrefixOf(groupRel), new ArrayList<>()));
            if (rel.getNameCount() > 1) {
                String key = unitKeyOf(rel);
                units.putIfAbsent(key,
                        new Unit(key, folder, unitPrefixOf(rel), new ArrayList<>()));
            }
        }
        return units;
    }

    /** 相对服务器根的世界目录 -> 单位键：首段目录是整体单位，深层是 世界组@维度目录名。 */
    static String unitKeyOf(Path rel) {
        String group = rel.getName(0).toString();
        return rel.getNameCount() == 1 ? group
                : group + "@" + rel.getName(rel.getNameCount() - 1).toString();
    }

    /** 单位在 zip 内的路径前缀，Windows 分隔符归一为 /。 */
    static String unitPrefixOf(Path rel) {
        return rel.toString().replace('\\', '/') + "/";
    }

    /** tokens 为 null/空 -> 全部世界整体；非法返回 null（已提示）。 */
    private List<Unit> resolveUnits(Map<String, Unit> units, List<String> tokens,
            CommandSender sender) {
        if (tokens == null || tokens.isEmpty()) {
            List<Unit> all = new ArrayList<>();
            for (Unit u : units.values()) {
                if (!u.key.contains("@")) {
                    all.add(u);
                }
            }
            if (all.isEmpty()) {
                getLogger().warning("No loaded world folders to back up.");
                tell(sender, "§cNo loaded worlds to back up.", "§c当前没有已加载的世界可备份。");
                return null;
            }
            return all;
        }
        List<Unit> chosen = new ArrayList<>();
        for (String token : tokens) {
            Unit u = units.get(token.toLowerCase(Locale.ROOT));
            if (u == null && !token.contains("@")) {
                List<String> matches = new ArrayList<>();
                for (Unit cand : units.values()) {
                    if (cand.key.endsWith("@" + token)) {
                        matches.add(cand.key);
                    }
                }
                if (matches.size() == 1) {
                    u = units.get(matches.get(0));
                } else if (matches.size() > 1) {
                    tell(sender, "§cDimension " + token + " exists in multiple worlds, use full key: "
                                    + String.join(", ", matches),
                            "§c维度 " + token + " 在多个世界存在，请写全: "
                                    + String.join(", ", matches));
                    return null;
                }
            }
            if (u == null) {
                tell(sender, "§cUnknown unit: " + token + "; available: "
                                + String.join(", ", units.keySet()) + " (see /savepoint world list)",
                        "§c未知备份单位: " + token + "；可选: "
                                + String.join(", ", units.keySet()) + "（用 /savepoint world list 查看）");
                return null;
            }
            if (!chosen.contains(u)) {
                chosen.add(u);
            }
        }
        return chosen;
    }

    // ------------------------------------------------------------------
    // backup
    // ------------------------------------------------------------------

    private boolean startBackup(String reason, String customName, List<String> tokens,
            boolean skipIfRunning, CommandSender reporter) {
        if (backupRunning && skipIfRunning) {
            tell(reporter, "§ePrevious backup still running, skipped (use --force to queue).",
                    "§e上一次备份仍在进行，本次已跳过（--force 可排队等待）。");
            return false;
        }
        Map<String, Unit> units = allUnits();
        List<Unit> chosen = resolveUnits(units, tokens, reporter);
        if (chosen == null) {
            return false;
        }
        String fileName = buildZipName(customName);
        if (fileName == null) {
            tell(reporter, "§cInvalid name, only letters, digits, dot, underscore, dash.",
                    "§c名称不合法，只允许字母、数字、点、下划线、短横。");
            return false;
        }
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
        } catch (RuntimeException e) {
            getLogger().warning("save-all failed: " + e.getMessage());
        }
        getLogger().info("Start " + reason + ", " + chosen.size() + " unit(s) -> " + fileName);
        backupRunning = true;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                doBackup(chosen, fileName);
            } finally {
                backupRunning = false;
            }
        });
        return true;
    }

    private String buildZipName(String customName) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        if (customName == null || customName.isEmpty()) {
            return "backup-" + stamp + ".zip";
        }
        if (!customName.matches("[A-Za-z0-9._-]{1,64}")) {
            return null;
        }
        return "backup-" + stamp + "_" + customName + ".zip";
    }

    private void doBackup(List<Unit> units, String fileName) {
        File dir = backupDir();
        if (!dir.exists() && !dir.mkdirs()) {
            getLogger().severe("Cannot create backup dir: " + dir.getAbsolutePath());
            return;
        }
        File out = new File(dir, fileName);
        if (out.exists()) {
            out = new File(dir, fileName.replace(".zip",
                    "-" + System.currentTimeMillis() % 1000 + ".zip"));
        }
        long bytes = 0L;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out.toPath()))) {
            zos.putNextEntry(new ZipEntry(MANIFEST_NAME));
            zos.write(manifestText(units).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (Unit unit : units) {
                bytes += zipTree(zos, unit);
            }
        } catch (IOException | RuntimeException e) {
            getLogger().severe("Backup failed: " + e);
            if (out.exists()) {
                out.delete();
            }
            return;
        }
        lastBackup = out.getName();
        getLogger().info("Done " + lastBackup + " (" + units.size() + " unit(s), ~"
                + (bytes / 1024 / 1024) + " MB raw)");
        prune();
    }

    /** 恢复端靠这份清单识别备份单位，不再从目录层级猜；旧 zip 没有它时才回退到猜。 */
    private static String manifestText(List<Unit> units) {
        StringBuilder sb = new StringBuilder("# Savepoint manifest v1\n");
        for (Unit u : units) {
            sb.append(u.key).append('\n');
        }
        return sb.toString();
    }

    private static long zipTree(ZipOutputStream zos, Unit unit) throws IOException {
        long total = 0L;
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(unit.folder, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                for (Path ex : unit.excludeDirs) {
                    if (d.toAbsolutePath().normalize().equals(ex)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!file.getFileName().toString().equals("session.lock")) {
                    paths.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        byte[] buffer = new byte[1 << 16];
        for (Path file : paths) {
            String entryName = unit.prefix
                    + unit.folder.relativize(file).toString().replace('\\', '/');
            zos.putNextEntry(new ZipEntry(entryName));
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    zos.write(buffer, 0, read);
                    total += read;
                }
            }
            zos.closeEntry();
        }
        return total;
    }

    private void prune() {
        int keep = Math.max(1, getConfig().getInt("keep-count", 48));
        File[] zips = backupDir().listFiles(
                (d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        if (zips == null || zips.length <= keep) {
            return;
        }
        List<File> sorted = new ArrayList<>(List.of(zips));
        sorted.sort(Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < sorted.size() - keep; i++) {
            if (sorted.get(i).delete()) {
                getLogger().info("Pruned old backup: " + sorted.get(i).getName());
            }
        }
    }

    // ------------------------------------------------------------------
    // restore
    // ------------------------------------------------------------------

    private void handleRestore(CommandSender sender, String[] args) {
        boolean force = false;
        boolean skipBackup = false;
        List<String> positional = new ArrayList<>();
        for (String a : args) {
            if (a.equalsIgnoreCase("--force")) {
                force = true;
            } else if (a.equalsIgnoreCase("skip-backup")) {
                skipBackup = true;
            } else {
                positional.add(a);
            }
        }
        if (positional.isEmpty()) {
            tell(sender, "§cUsage: /savepoint restore <latest|name> [unit...] [skip-backup] [--force]",
                    "§c用法: /savepoint restore <latest|备份名> [单位...] [skip-backup] [--force]");
            tell(sender, "§7Units: world group (e.g. bskyblock_world) or group@dimension (e.g. bskyblock_world@the_nether), see /savepoint world list",
                    "§7单位: 世界名（如 bskyblock_world）或 世界@维度（如 bskyblock_world@the_nether），见 /savepoint world list");
            return;
        }
        File zip = resolveZip(positional.get(0), sender);
        if (zip == null) {
            return;
        }
        List<String> tokens = positional.subList(1, positional.size());
        ZipUnits units = readZipUnits(zip, sender);
        if (units == null) {
            return;
        }
        Map<String, String> targets = resolveTargets(tokens, units, sender);
        if (targets == null) {
            return;
        }
        Set<String> partial = partialWorlds(targets.keySet());
        if (!partial.isEmpty()) {
            List<String> dims = new ArrayList<>();
            for (String key : targets.keySet()) {
                int at = key.indexOf('@');
                if (at > 0 && partial.contains(key.substring(0, at))) {
                    dims.add(key);
                }
            }
            tell(sender, "§cPartial-dimension restore (" + String.join(", ", dims)
                            + "): level.dat and players/ stay as they are now, so chest contents"
                            + " roll back while inventories do not (item duplication), and player"
                            + " positions may end up inside blocks or in ungenerated space.",
                    "§c单维度回档（" + String.join(", ", dims)
                            + "）：level.dat 与 players/ 不会回退，箱子内容回档但背包不回档"
                            + "（可能刷物品），玩家坐标也可能落到回档后不存在的位置。");
            if (!force) {
                tell(sender, "§eNothing done. Repeat the command with --force to accept that risk.",
                        "§e已中止，未改动任何文件。确认承担上述风险请加 --force 重新执行。");
                return;
            }
        }
        List<String> targetPrefixes = new ArrayList<>(targets.values());
        if (!force && pendingConfirm == null) {
            pendingConfirm = zip.getName() + "|" + String.join(",", targetPrefixes);
            tell(sender, "§eAbout to restore " + zip.getName() + " (targets: "
                            + String.join(", ", targets.keySet())
                            + "). All players will be kicked and the server will stop.",
                    "§e即将回档 " + zip.getName() + "（还原单位: "
                            + String.join(", ", targets.keySet())
                            + "）。所有在线玩家将被踢出并关服。");
            tell(sender, "§eRepeat the same command with --force to confirm, or /savepoint cancel (valid 60s).",
                    "§e再次执行相同命令加 --force 确认，或 /savepoint cancel 取消（60 秒内有效）。");
            getServer().getScheduler().runTaskLater(this, () -> pendingConfirm = null, 1200L);
            return;
        }
        if (!force) {
            pendingConfirm = null;
        }
        tell(sender, "§aRestore started (file: " + zip.getName() + ").",
                "§a开始回档流程（备份文件: " + zip.getName() + "）。");
        doRestoreFlow(zip, targetPrefixes, skipBackup);
    }

    private File resolveZip(String arg, CommandSender sender) {
        File dir = backupDir();
        if (arg.equalsIgnoreCase("latest")) {
            File[] zips = dir.listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
            if (zips == null || zips.length == 0) {
                tell(sender, "§cNo backup files found.", "§c备份目录中没有找到备份文件。");
                return null;
            }
            File latest = zips[0];
            for (File f : zips) {
                if (f.lastModified() > latest.lastModified()) {
                    latest = f;
                }
            }
            return latest;
        }
        String fileName = arg.toLowerCase(Locale.ROOT);
        if (!fileName.matches("[A-Za-z0-9._@-]{1,128}")) {
            tell(sender, "§cInvalid backup name.", "§c备份名不合法。");
            return null;
        }
        if (!fileName.endsWith(".zip")) {
            fileName = fileName + ".zip";
        }
        File f = new File(dir, fileName);
        if (!f.isFile()) {
            tell(sender, "§cBackup not found: " + f.getName() + " (see /savepoint list)",
                    "§c找不到备份: " + f.getName() + "，用 /savepoint list 查看全部备份。");
            return null;
        }
        return f;
    }

    /** zip 内的单位级目录前缀：世界根 "X/" 与维度 "X/dimensions/<ns>/<dim>/"。 */
    private static ZipUnits readZipUnits(File zip, CommandSender sender) {
        Map<String, String> dims = new LinkedHashMap<>();     // world|dim -> zip 内前缀
        Set<String> rootDataWorlds = new LinkedHashSet<>();   // 有 level.dat 等根数据的世界
        List<String> manifestKeys = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash < 0) {
                    if (name.equals(MANIFEST_NAME)) {
                        manifestKeys.addAll(readManifest(zin));
                    }
                } else {
                    String world = name.substring(0, slash);
                    int dimStart = name.indexOf("/dimensions/", slash);
                    if (dimStart > 0) {
                        int nsEnd = name.indexOf('/', dimStart + "/dimensions/".length());
                        int dimEnd = nsEnd < 0 ? -1 : name.indexOf('/', nsEnd + 1);
                        if (dimEnd > 0) {
                            dims.putIfAbsent(world + "|" + name.substring(nsEnd + 1, dimEnd),
                                    name.substring(0, dimEnd + 1));
                        }
                    } else {
                        rootDataWorlds.add(world);
                    }
                }
                zin.closeEntry();
            }
        } catch (IOException e) {
            tell(sender, "§cCannot read backup: " + e.getMessage(),
                    "§c无法读取备份文件: " + e.getMessage());
            return null;
        }
        ZipUnits units = new ZipUnits();
        if (manifestKeys.isEmpty()) {
            for (String w : rootDataWorlds) {
                units.prefixes.put(w.toLowerCase(Locale.ROOT), w + "/");
            }
        } else {
            for (String key : manifestKeys) {
                String prefix = prefixForUnit(key, rootDataWorlds, dims);
                if (prefix == null) {
                    tell(sender, "§cManifest lists " + key + " but the archive holds no such data.",
                            "§c备份清单声明了 " + key + "，但压缩包里找不到对应数据，文件可能已损坏。");
                    return null;
                }
                units.prefixes.putIfAbsent(key.toLowerCase(Locale.ROOT), prefix);
            }
        }
        // 整世界备份里每个维度也允许单独回档，所以维度键一律按实际条目登记；
        // 不带单位时它们会被上面的整世界键覆盖掉，不会重复还原。
        for (Map.Entry<String, String> en : dims.entrySet()) {
            String[] wd = en.getKey().split("\\|", 2);
            units.prefixes.putIfAbsent((wd[0] + "@" + wd[1]).toLowerCase(Locale.ROOT),
                    en.getValue());
        }
        if (units.prefixes.isEmpty()) {
            tell(sender, "§cBackup contains no world data.", "§c备份文件中没有世界数据。");
            return null;
        }
        return units;
    }

    private static String prefixForUnit(String key, Set<String> rootDataWorlds,
            Map<String, String> dims) {
        int at = key.indexOf('@');
        if (at < 0) {
            return rootDataWorlds.contains(key) ? key + "/" : null;
        }
        return dims.get(key.substring(0, at) + "|" + key.substring(at + 1));
    }

    private static List<String> readManifest(ZipInputStream zin) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int read;
        while ((read = zin.read(buf)) > 0) {
            sb.append(new String(buf, 0, read, StandardCharsets.UTF_8));
        }
        List<String> keys = new ArrayList<>();
        for (String line : sb.toString().split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                keys.add(trimmed);
            }
        }
        return keys;
    }

    /** 按单位键解析恢复目标（键 -> zip 内前缀）；有非法键返回 null（已提示）。 */
    private static Map<String, String> resolveTargets(List<String> tokens, ZipUnits units,
            CommandSender sender) {
        List<String> keys = new ArrayList<>();
        if (tokens.isEmpty()) {
            keys.addAll(units.keys());
        } else {
            for (String token : tokens) {
                String key = matchUnitKey(token, units, sender);
                if (key == null) {
                    return null;
                }
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        Map<String, String> targets = new LinkedHashMap<>();
        for (String key : keys) {
            targets.put(key, units.prefixes.get(key));
        }
        // 整世界单位已覆盖它的维度子单位，去掉冗余免得重复改名。
        targets.keySet().removeIf(k -> k.contains("@")
                && targets.containsKey(k.substring(0, k.indexOf('@'))));
        return targets;
    }

    /** 接受 world 与 world@dim，也接受能唯一命中的裸维度名；否则返回 null（已提示）。 */
    private static String matchUnitKey(String token, ZipUnits units, CommandSender sender) {
        String t = token.toLowerCase(Locale.ROOT);
        if (units.prefixes.containsKey(t)) {
            return t;
        }
        List<String> matches = new ArrayList<>();
        for (String key : units.prefixes.keySet()) {
            if (key.endsWith("@" + t)) {
                matches.add(key);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            tell(sender, "§cDimension " + t + " exists in multiple worlds, use full key: "
                            + String.join(", ", matches),
                    "§c维度 " + t + " 在备份的多个世界里都有，请写全: "
                            + String.join(", ", matches));
            return null;
        }
        tell(sender, "§cNot in this backup: " + token + "; units: "
                        + String.join(", ", units.keys()),
                "§c该备份中不存在: " + token + "；内含单位: " + String.join(", ", units.keys()));
        return null;
    }

    /** 本次目标里只还原部分维度的世界——这些世界的 level.dat 与 players 不会回退。 */
    private static Set<String> partialWorlds(Set<String> keys) {
        Set<String> whole = new LinkedHashSet<>();
        Set<String> touched = new LinkedHashSet<>();
        for (String k : keys) {
            int at = k.indexOf('@');
            if (at < 0) {
                whole.add(k);
            } else {
                touched.add(k.substring(0, at));
            }
        }
        touched.removeAll(whole);
        return touched;
    }

    private void doRestoreFlow(File zip, List<String> targetPrefixes, boolean skipBackup) {
        boolean needBackup = !skipBackup && getConfig().getBoolean("restore-backup-before", true);
        if (needBackup && !backupRunning) {
            startBackup("pre-restore safety backup", "pre-restore", null, false, null);
            waitBackupDone(() -> lockLoginsAndCountdown(zip, targetPrefixes));
        } else {
            if (needBackup) {
                getLogger().warning("Previous backup still running, skipping pre-restore backup.");
            }
            lockLoginsAndCountdown(zip, targetPrefixes);
        }
    }

    private void waitBackupDone(Runnable then) {
        long start = System.currentTimeMillis();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                while (backupRunning && System.currentTimeMillis() - start < 120_000L) {
                    Thread.sleep(500L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (backupRunning) {
                getLogger().warning("Timed out waiting for pre-restore backup, continuing.");
            }
            getServer().getScheduler().runTask(this, then);
        });
    }

    private void lockLoginsAndCountdown(File zip, List<String> targetPrefixes) {
        lockLogins();
        broadcast("§c[Savepoint] Server is restarting from a backup. Reconnect after the files are restored.",
                "§c[Savepoint] 服务器即将回档并关闭，玩家暂时无法进入，文件还原完成后会恢复正常。");
        getServer().getScheduler().runTaskLater(this,
                () -> broadcast("§cRestoring in 5s...", "§c回档倒计时 5 秒…"), 100L);
        getServer().getScheduler().runTaskLater(this,
                () -> broadcast("§c3s...", "§c3 秒…"), 150L);
        getServer().getScheduler().runTaskLater(this,
                () -> broadcast("§c2s...", "§c2 秒…"), 170L);
        getServer().getScheduler().runTaskLater(this,
                () -> broadcast("§c1s... shutting down!", "§c1 秒…关服！"), 190L);
        getServer().getScheduler().runTaskLater(this,
                () -> kickAndShutdown(zip, targetPrefixes), 200L);
    }

    private void broadcast(String en, String zh) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(zh);
        }
        getLogger().info(en.replaceAll("§.", ""));
    }

    private void kickAndShutdown(File zip, List<String> targetPrefixes) {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            try {
                p.kickPlayer("§cServer is restoring from backup, please reconnect later.");
            } catch (RuntimeException e) {
                getLogger().warning("Kick failed for " + p.getName() + ": " + e.getMessage());
            }
        }
        Path root = Paths.get(".").toAbsolutePath().normalize();
        List<String[]> prefixDirs = restoreDirs(root, targetPrefixes);
        getLogger().info("Players kicked, restore on JVM exit: " + String.join(", ", targetPrefixes));
        launchRestoreThread(root, zip, prefixDirs);
        try {
            Bukkit.shutdown();
        } catch (Throwable t) {
            getLogger().severe("shutdown error: " + t);
        }
    }

    /**
     * 还原在 JVM shutdown hook 阶段执行（关服最终保存已结束），
     * 工作线程与 hook 互等：线程做文件操作，hook 等它完成后 JVM 才退出。
     * 进程被外部强杀时 hook 同样运行；若 shutdown 卡死，60 秒后线程主动 halt 触发 hook。
     */
    private final Object restoreLock = new Object();
    private volatile boolean shutdownHookStarted = false;
    private volatile boolean restoreWorkDone = false;

    private void launchRestoreThread(Path root, File zip, List<String[]> prefixDirs) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (restoreLock) {
                shutdownHookStarted = true;
                restoreLock.notifyAll();
            }
            synchronized (restoreLock) {
                while (!restoreWorkDone) {
                    try {
                        restoreLock.wait(1000L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "Savepoint-RestoreHook"));
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 60_000L;
            synchronized (restoreLock) {
                while (!shutdownHookStarted && System.currentTimeMillis() < deadline) {
                    try {
                        restoreLock.wait(500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!shutdownHookStarted) {
                System.err.println("[Savepoint-restore] Shutdown not finished in 60s, forcing halt to run restore.");
                Runtime.getRuntime().halt(1); // halt 会触发 shutdown hook，由 hook 完成还原
                return;
            }
            doRestoreOnExit(root, zip, prefixDirs);
            synchronized (restoreLock) {
                restoreWorkDone = true;
                restoreLock.notifyAll();
            }
        }, "Savepoint-Restore");
        t.setDaemon(true);
        t.start();
    }

    /** zip 内前缀 -> 磁盘上的目标目录（绝对路径），按目录去重。 */
    private static List<String[]> restoreDirs(Path root, List<String> targetPrefixes) {
        List<String[]> dirs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String p : targetPrefixes) {
            String dir = p.substring(0, p.length() - 1);
            if (seen.add(dir)) {
                dirs.add(new String[] {p, root.resolve(dir).toString()});
            }
        }
        return dirs;
    }

    /** 目标目录里没有任何被还原祖先的那些——它们才需要改名。 */
    private static List<String[]> topLevelDirs(List<String[]> prefixDirs) {
        List<String[]> top = new ArrayList<>();
        for (String[] pd : prefixDirs) {
            boolean hasRestoredAncestor = false;
            for (String[] other : prefixDirs) {
                if (other != pd && pd[1].startsWith(other[1] + File.separator)) {
                    hasRestoredAncestor = true;
                    break;
                }
            }
            if (!hasRestoredAncestor) {
                top.add(pd);
            }
        }
        return top;
    }

    private void doRestoreOnExit(Path root, File zip, List<String[]> prefixDirs) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        System.out.println("[Savepoint-restore] Restoring from " + zip.getName());
        // 只改名最顶层的目标目录：整世界时世界根目录一次改名连带全部维度，
        // 单维度时该维度目录自己就是顶层；其下条目的前缀不再是独立目标。
        List<String[]> topRenames = topLevelDirs(prefixDirs);
        Set<String> failedDirs = new LinkedHashSet<>();
        List<Path> preRestores = new ArrayList<>();
        Set<String> renamedOk = new LinkedHashSet<>();
        for (String[] pd : topRenames) {
            File dir = new File(pd[1]);
            if (!dir.exists()) {
                renamedOk.add(pd[1]);
                continue;
            }
            File renamed = new File(dir.getParentFile(), dir.getName() + ".pre-restore-" + stamp);
            if (renameWithRetry(dir, renamed)) {
                preRestores.add(renamed.toPath());
                renamedOk.add(pd[1]);
            } else {
                failedDirs.add(pd[1]);
                System.err.println("[Savepoint-restore] Cannot rename " + dir
                        + ", skipping its restore to avoid mixing data.");
            }
        }
        List<String> extractPrefixes = new ArrayList<>();
        for (String[] pd : prefixDirs) {
            File dir = new File(pd[1]);
            boolean ancestorFailed = false;
            for (String bad : failedDirs) {
                if (dir.toString().startsWith(bad + File.separator)) {
                    ancestorFailed = true;
                    break;
                }
            }
            if (!ancestorFailed && renamedOk.contains(pd[1])) {
                extractPrefixes.add(pd[0]);
            }
        }
        try {
            unzipEntries(root, zip, extractPrefixes);
        } catch (IOException e) {
            System.err.println("[Savepoint-restore] Unzip failed: " + e);
            System.err.println("[Savepoint-restore] Old data kept in *.pre-restore-" + stamp
                    + ", fix manually before starting the server.");
            // 标记这些改名目录为“解压失败保留的安全副本”，避免下次启动自动清理时误删。
            for (Path pre : preRestores) {
                try {
                    Files.createFile(pre.resolve(KEEP_MARKER));
                } catch (IOException ignore) {
                    System.err.println("[Savepoint-restore] Could not mark " + pre
                            + " as keep, do NOT let the next start clean it blindly.");
                }
            }
            return;
        }
        for (Path pre : preRestores) {
            if (!deleteRecursive(pre.toFile())) {
                System.err.println("[Savepoint-restore] Cannot delete old dir " + pre
                        + " (still held open by this JVM), it will be removed on next start.");
            }
        }
        boolean unlocked = unlockLoginsOnDisk();
        System.out.println("[Savepoint-restore] Restore done"
                + (unlocked ? ", login config restored."
                        : ", but restoring login config FAILED, fix *.wb-prerestore manually.")
                + " Start the server now (start.bat).");
    }

    private void unzipEntries(Path root, File zip, List<String> targetPrefixes)
            throws IOException {
        byte[] buffer = new byte[1 << 16];
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                boolean match = false;
                for (String p : targetPrefixes) {
                    if (name.startsWith(p)) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    zin.closeEntry();
                    continue;
                }
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Backup contains an illegal path: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        int read;
                        while ((read = zin.read(buffer)) > 0) {
                            os.write(buffer, 0, read);
                        }
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private boolean renameWithRetry(File from, File to) {
        for (int i = 0; i < 10; i++) {
            if (from.renameTo(to)) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean deleteRecursive(File dir) {
        Path start = dir.toPath();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc)
                        throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // login lock
    // ------------------------------------------------------------------

    private void lockLogins() {
        try {
            Path whitelist = Paths.get("whitelist.json");
            originalWhitelist = Files.exists(whitelist)
                    ? Files.readAllBytes(whitelist) : "[]".getBytes();
            Files.copy(whitelist, Paths.get("whitelist.json.wb-prerestore"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(whitelist,
                    "[{\"uuid\":\"00000000-0000-0000-0000-000000000000\",\"name\":\""
                            + LOCK_NAME + "\"}]");
            Path props = Paths.get("server.properties");
            originalProperties = Files.readAllBytes(props);
            Files.copy(props, Paths.get("server.properties.wb-prerestore"),
                    StandardCopyOption.REPLACE_EXISTING);
            List<String> lines = Files.readAllLines(props);
            setPropertyKey(lines, "online-mode", "false");
            setPropertyKey(lines, "white-list", "true");
            Files.write(props, lines);
            getLogger().info("Logins locked (online-mode=false + placeholder whitelist), auto-restore after done.");
        } catch (IOException e) {
            getLogger().severe("Failed to lock logins: " + e);
        }
    }

    private static void setPropertyKey(List<String> lines, String key, String value) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                return;
            }
        }
        lines.add(key + "=" + value);
    }

    /** 在还原线程里使用（不依赖插件生命周期），从磁盘 .wb-prerestore 恢复。 */
    private boolean unlockLoginsOnDisk() {
        try {
            Path whitelist = Paths.get("whitelist.json");
            Path whitelistBackup = Paths.get("whitelist.json.wb-prerestore");
            if (Files.exists(whitelistBackup)) {
                Files.copy(whitelistBackup, whitelist, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.write(whitelist, "[]".getBytes());
            }
            Path propsBackup = Paths.get("server.properties.wb-prerestore");
            if (Files.exists(propsBackup)) {
                Files.copy(propsBackup, Paths.get("server.properties"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            System.err.println("[Savepoint-restore] Failed to restore login config: " + e);
            return false;
        }
    }

    private boolean unlockLoginsInMemory() {
        try {
            if (originalWhitelist != null) {
                Files.write(Paths.get("whitelist.json"), originalWhitelist);
            } else if (Files.exists(Paths.get("whitelist.json.wb-prerestore"))) {
                Files.copy(Paths.get("whitelist.json.wb-prerestore"),
                        Paths.get("whitelist.json"), StandardCopyOption.REPLACE_EXISTING);
            } else {
                return false;
            }
            if (originalProperties != null) {
                Files.write(Paths.get("server.properties"), originalProperties);
            } else if (Files.exists(Paths.get("server.properties.wb-prerestore"))) {
                Files.copy(Paths.get("server.properties.wb-prerestore"),
                        Paths.get("server.properties"), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            getLogger().severe("Failed to restore login config: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // config helpers / commands
    // ------------------------------------------------------------------

    private File backupDir() {
        String configured = getConfig().getString("backup-dir", "backups");
        Path p = Paths.get(configured);
        if (!p.isAbsolute()) {
            p = Paths.get(".").toAbsolutePath().normalize().resolve(p);
        }
        return p.toFile();
    }

    private int intervalMinutes() {
        return Math.max(1, getConfig().getInt("interval-minutes", 60));
    }

    private int delayMinutes() {
        return Math.max(1, getConfig().getInt("last-offline-delay-minutes", 3));
    }

    /** 完整命令说明（/savepoint help 与 /savepoint ?）。 */
    private void sendHelp(CommandSender sender) {
        tell(sender, "§b--- Savepoint v" + VERSION + " commands ---",
                "§b--- Savepoint v" + VERSION + " 命令说明 ---");
        tell(sender, "§a/savepoint §7- status summary", "§a/savepoint §7- 查看状态概要");
        tell(sender, "§a/savepoint backup §7- back up all worlds, backup-<timestamp>.zip",
                "§a/savepoint backup §7- 立即备份全部世界 -> backup-<时间戳>.zip");
        tell(sender, "§a/savepoint backup <name> §7- custom file name: backup-<timestamp>_<name>.zip",
                "§a/savepoint backup <名称> §7- 自定义文件名 -> backup-<时间戳>_<名称>.zip");
        tell(sender, "§a/savepoint backup [name] <unit...> §7- only selected units",
                "§a/savepoint backup [名称] <单位...> §7- 仅备份指定单位（世界或维度）");
        tell(sender, "§a/savepoint backup ... --force §7- queue even if a backup is running",
                "§a/savepoint backup ... --force §7- 上一次备份未结束时仍排队执行");
        tell(sender, "§a/savepoint world list §7- list backup units (world group and each dimension)",
                "§a/savepoint world list §7- 列出可备份单位（世界整体 / 单维度）");
        tell(sender, "§a/savepoint list §7- list backup files (newest first)",
                "§a/savepoint list §7- 列出备份文件（最新优先）");
        tell(sender, "§a/savepoint restore <latest|name> [unit...] [skip-backup] [--force]",
                "§a/savepoint restore <latest|备份名> [单位...] [skip-backup] [--force]");
        tell(sender, "§7restore: confirm -> pre-backup -> lock logins -> kick all -> shutdown -> restore files",
                "§7回档流程: 二次确认 -> 保护备份 -> 锁定登录 -> 踢出玩家 -> 关服 -> 还原文件");
        tell(sender, "§7  skip-backup: no safety backup before restore;",
                "§7  skip-backup: 回档前不做保护备份；");
        tell(sender, "§7  --force: skip the confirmation prompt; required for a single-dimension restore.",
                "§7  --force: 跳过二次确认；只回档单个维度时必须加（背包不回退，有刷物品风险）。");
        tell(sender, "§a/savepoint cancel §7- cancel a pending restore confirmation",
                "§a/savepoint cancel §7- 取消待确认的回档");
        tell(sender, "§a/savepoint unlock §7- restore whitelist.json / server.properties after a locked restore",
                "§a/savepoint unlock §7- 手动恢复 whitelist.json / server.properties（回档锁定后开门）");
        tell(sender, "§a/savepoint reload §7- reload config.yml",
                "§a/savepoint reload §7- 重载 config.yml");
        tell(sender, "§b/config: " + getConfig().getString("backup-dir", "backups")
                        + ", interval " + intervalMinutes() + "m, offline delay " + delayMinutes()
                        + "m, keep " + getConfig().getInt("keep-count", 48)
                        + ", empty mode " + emptyMode(),
                "§b配置: 目录 " + getConfig().getString("backup-dir", "backups")
                        + "，间隔 " + intervalMinutes() + " 分钟，离线倒计时 " + delayMinutes()
                        + " 分钟，保留 " + getConfig().getInt("keep-count", 48)
                        + " 份，空服模式 " + emptyMode());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            tell(sender, "§bSavepoint v" + VERSION, "§bSavepoint v" + VERSION);
            tell(sender, "Interval: " + intervalMinutes() + "m, offline delay: "
                            + delayMinutes() + "m, empty mode: " + emptyMode() + ", keep: "
                            + getConfig().getInt("keep-count", 48),
                    "在线间隔: " + intervalMinutes() + " 分钟，离线倒计时: " + delayMinutes()
                            + " 分钟，空服模式: " + emptyMode() + "，保留 "
                            + getConfig().getInt("keep-count", 48) + " 份");
            tell(sender, "Last backup: " + lastBackup, "最近备份: " + lastBackup);
            tell(sender, "§7Type /savepoint help for the full command list.",
                    "§7输入 /savepoint help 查看完整命令说明。");
            return true;
        }
        if (!sender.hasPermission("savepoint.admin")) {
            tell(sender, "§cNo permission.", "§c没有权限。");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        switch (sub) {
            case "backup": {
                List<String> positional = new ArrayList<>();
                boolean force = false;
                for (String a : rest) {
                    if (a.equalsIgnoreCase("--force")) {
                        force = true;
                    } else {
                        positional.add(a);
                    }
                }
                Map<String, Unit> units = allUnits();
                String customName = null;
                List<String> tokens = new ArrayList<>();
                for (String token : positional) {
                    if (isUnitLike(token, units)) {
                        tokens.add(token);
                    } else if (customName == null && tokens.isEmpty()) {
                        customName = token;
                    } else {
                        tell(sender, "§cUnknown unit: " + token + "; available: "
                                        + String.join(", ", units.keySet()),
                                "§c未知备份单位: " + token + "；可选: "
                                        + String.join(", ", units.keySet()));
                        tell(sender, "§7Note: the first non-unit argument is treated as the backup name. See /savepoint world list.",
                                "§7提示: 第一个非单位参数视为备份名。用 /savepoint world list 查看单位。");
                        return true;
                    }
                }
                startBackup("manual backup", customName, tokens.isEmpty() ? null : tokens,
                        !force, sender);
                return true;
            }
            case "world": {
                if (rest.length > 0 && rest[0].equalsIgnoreCase("list")) {
                    tell(sender, "§bBackup units (whole world / single dimension):",
                            "§b可备份单位（世界整体 / 单维度）：");
                    List<String> wholeKeys = new ArrayList<>();
                    List<String> dimKeys = new ArrayList<>();
                    for (Unit u : allUnits().values()) {
                        (u.key.contains("@") ? dimKeys : wholeKeys).add(u.key);
                    }
                    tell(sender, "§a" + String.join(",  ", wholeKeys)
                                    + "  §7(whole world: level.dat etc + all dimensions)",
                            "§a" + String.join(",  ", wholeKeys)
                                    + "  §7（世界整体：根文件 level.dat 等 + 全部维度）");
                    tell(sender, "§b" + String.join(",  ", dimKeys),
                            "§b" + String.join(",  ", dimKeys));
                    tell(sender, "§7Single-dimension units use world@dimension; level.dat only belongs to the whole-world unit.",
                            "§7说明：只备份某维度用 世界@维度 形式；世界整体与单维度分开备份时 level.dat 只随整体备份。");
                    return true;
                }
                tell(sender, "§cUsage: /savepoint world list", "§c用法: /savepoint world list");
                return true;
            }
            case "list": {
                File[] zips = backupDir().listFiles(
                        (d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
                if (zips == null || zips.length == 0) {
                    tell(sender, "§eBackup dir is empty.", "§e备份目录为空。");
                    return true;
                }
                List<File> sorted = new ArrayList<>(List.of(zips));
                sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
                tell(sender, "§bBackups (newest first, max 10):", "§b备份文件（最新优先，最多 10 条）:");
                SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
                for (int i = 0; i < Math.min(10, sorted.size()); i++) {
                    File f = sorted.get(i);
                    String line = "  §a" + f.getName() + " §7(" + (f.length() / 1024 / 1024)
                            + " MB, " + fmt.format(new Date(f.lastModified())) + ")";
                    tell(sender, line, line);
                }
                return true;
            }
            case "restore":
                handleRestore(sender, rest);
                return true;
            case "cancel":
                if (pendingConfirm != null) {
                    pendingConfirm = null;
                    tell(sender, "§aPending restore cancelled.", "§a已取消待确认的回档。");
                } else {
                    tell(sender, "§eNothing pending.", "§e没有待确认的回档。");
                }
                return true;
            case "unlock":
                tell(sender, unlockLoginsInMemory()
                                ? "§aRestored whitelist.json and server.properties."
                                : "§cFailed, check *.wb-prerestore manually.",
                        unlockLoginsInMemory()
                                ? "§a已恢复 whitelist.json 与 server.properties 原始配置。"
                                : "§c恢复失败，请手动检查 *.wb-prerestore 文件。");
                return true;
            case "help":
            case "?":
                sendHelp(sender);
                return true;
            case "reload":
                reloadConfig();
                emptyModeCached = null;
                tell(sender, "§aConfig reloaded.", "§a配置已重载。");
                return true;
            default:
                tell(sender, "§cUnknown subcommand. See /savepoint help.", "§c未知子命令。输入 /savepoint help 查看用法。");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
            String[] args) {
        if (args.length == 1) {
            return matching(SUB_COMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "world":
                return args.length == 2 ? matching(List.of("list"), last) : List.of();
            case "backup": {
                List<String> opts = new ArrayList<>(allUnits().keySet());
                opts.add("--force");
                return matching(opts, last);
            }
            case "restore": {
                List<String> opts = new ArrayList<>();
                if (args.length == 2) {
                    opts.add("latest");
                    for (File f : backupFiles()) {
                        opts.add(f.getName());
                        opts.add(f.getName().replaceAll("\\.zip$", ""));
                    }
                } else {
                    File zip = resolveZip(args[1], null);
                    if (zip != null) {
                        opts.addAll(unitKeysInZip(zip));
                    }
                    opts.add("skip-backup");
                    opts.add("--force");
                }
                return matching(opts, last);
            }
            default:
                return List.of();
        }
    }

    private static final List<String> SUB_COMMANDS = List.of(
            "backup", "world", "list", "restore", "cancel", "unlock", "reload", "help");

    private File[] backupFiles() {
        File[] zips = backupDir().listFiles(
                (d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        return zips == null ? new File[0] : zips;
    }

    /** 备份包内的单位键，用于 /savepoint restore 的单位补全。 */
    private List<String> unitKeysInZip(File zip) {
        ZipUnits units = readZipUnits(zip, null);
        return units == null ? List.of() : units.keys();
    }

    private static List<String> matching(List<String> options, String token) {
        String t = token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) {
                out.add(o);
            }
        }
        return out;
    }

    private static boolean isUnitLike(String token, Map<String, Unit> units) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (token.contains("@")) {
            String world = lower.substring(0, lower.indexOf('@'));
            for (String key : units.keySet()) {
                if (key.startsWith(world + "@")) {
                    return true;
                }
            }
            return false;
        }
        return units.containsKey(lower);
    }
}
