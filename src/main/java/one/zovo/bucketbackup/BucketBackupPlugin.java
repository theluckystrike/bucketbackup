package one.zovo.bucketbackup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class BucketBackupPlugin extends JavaPlugin implements TabCompleter {
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final List<String> SUBS = List.of("now", "status", "list", "test", "reload");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<World, Boolean> pausedAutoSave = new HashMap<>();
    private Settings settings;
    private BukkitTask timer;
    private volatile Instant nextRun;
    private volatile Instant lastSuccess;
    private Instant lastStamp;
    private volatile String lastSummary = "none yet";
    private volatile String lastError = "none";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        if (getCommand("bucketbackup") != null) {
            getCommand("bucketbackup").setTabCompleter(this);
        }
        runner().cleanStale();
    }

    @Override
    public void onDisable() {
        if (timer != null) {
            timer.cancel();
        }
        resumeAutoSave();
    }

    private void load() {
        reloadConfig();
        settings = Settings.from(getConfig(), System.getenv());
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        nextRun = null;
        if (!settings.valid()) {
            getLogger().warning("BucketBackup is not set up yet. Edit plugins/BucketBackup/config.yml, then run /bucketbackup reload:");
            settings.errors().forEach(e -> getLogger().warning(" - " + e));
            return;
        }
        getLogger().info("Backups go to " + settings.endpoint() + " bucket " + settings.bucket() + " under "
                + settings.prefix() + "/" + (settings.intervalMinutes() > 0
                ? ", every " + settings.intervalMinutes() + " minutes" : ", schedule off (use /bucketbackup now)"));
        if (settings.intervalMinutes() > 0) {
            long period = settings.intervalMinutes() * 60L * 20L;
            long delay = settings.backupOnStartup() ? 20L * 60 : period;
            nextRun = Instant.now().plusSeconds(delay / 20);
            timer = Bukkit.getScheduler().runTaskTimer(this, () -> {
                nextRun = Instant.now().plusSeconds(period / 20);
                startBackup(Bukkit.getConsoleSender(), "schedule");
            }, delay, period);
        }
    }

    private BackupRunner runner() {
        Path work = getDataFolder().toPath().resolve("work");
        return new BackupRunner(settings, serverDir(), work, msg -> getLogger().info(msg));
    }

    private static Path serverDir() {
        return Path.of("").toAbsolutePath().normalize();
    }

    /** Save folders of the selected worlds plus extra paths, relative to the server folder, without nesting. */
    List<String> selectedPaths() {
        Path root = serverDir();
        List<Path> picked = new ArrayList<>();
        boolean all = settings.worlds().contains("*");
        for (World w : Bukkit.getWorlds()) {
            if (all || settings.worlds().contains(w.getName())) {
                picked.add(WorldPaths.levelRoot(w.getWorldFolder().toPath(), root));
            }
        }
        for (String p : settings.extraPaths()) {
            picked.add(root.resolve(p));
        }
        return WorldPaths.relativeUnique(picked, root);
    }

    private void pauseAutoSave() {
        for (World w : Bukkit.getWorlds()) {
            pausedAutoSave.put(w, w.isAutoSave());
            w.setAutoSave(false);
            w.save();
        }
    }

    private void resumeAutoSave() {
        pausedAutoSave.forEach((w, was) -> {
            if (was) {
                w.setAutoSave(true);
            }
        });
        pausedAutoSave.clear();
    }

    private void tell(CommandSender who, String msg) {
        Bukkit.getScheduler().runTask(this, () -> who.sendMessage("[BucketBackup] " + msg));
    }

    void startBackup(CommandSender who, String reason) {
        if (!settings.valid()) {
            who.sendMessage("[BucketBackup] Not set up: " + settings.errors().get(0));
            return;
        }
        if (!running.compareAndSet(false, true)) {
            who.sendMessage("[BucketBackup] A backup is already running.");
            return;
        }
        Settings s = settings;
        BackupRunner r = runner();
        List<String> paths = selectedPaths();
        Instant when = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if (lastStamp != null && !when.isAfter(lastStamp)) {
            when = lastStamp.plusSeconds(1);
        }
        lastStamp = when;
        Instant stamp = when;
        getLogger().info("Backup started (" + reason + "): " + String.join(", ", paths));
        if (!(who instanceof org.bukkit.command.ConsoleCommandSender)) {
            who.sendMessage("[BucketBackup] Backup started: " + String.join(", ", paths));
        }
        pauseAutoSave();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Archiver.Result zip;
            long t0 = System.nanoTime();
            try {
                zip = r.archive(paths, stamp);
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, this::resumeAutoSave);
                fail(who, s, "zip failed: " + e.getMessage());
                return;
            }
            Duration zipTime = Duration.ofNanos(System.nanoTime() - t0);
            Bukkit.getScheduler().runTask(this, this::resumeAutoSave);
            try {
                BackupRunner.Report rep = r.upload(zip, stamp, zipTime);
                lastSuccess = Instant.now();
                lastSummary = rep.summary();
                getLogger().info("Backup done. " + rep.summary() + " sha256 " + rep.sha256());
                for (String k : rep.deleted()) {
                    getLogger().info("Retention removed " + k);
                }
                if (!(who instanceof org.bukkit.command.ConsoleCommandSender)) {
                    tell(who, "Backup done. " + rep.summary());
                }
                if ("always".equals(s.notifyOn())) {
                    notify(s, "Backup done on " + Bukkit.getServer().getName() + ". " + rep.summary());
                }
            } catch (Exception e) {
                fail(who, s, "upload failed: " + e.getMessage());
                return;
            } finally {
                running.set(false);
            }
        });
    }

    private void fail(CommandSender who, Settings s, String msg) {
        running.set(false);
        lastError = SHOW.format(Instant.now()) + " " + msg;
        getLogger().severe("Backup failed: " + msg);
        if (!(who instanceof org.bukkit.command.ConsoleCommandSender)) {
            tell(who, "Backup failed: " + msg);
        }
        if (!"never".equals(s.notifyOn())) {
            notify(s, "Backup FAILED on " + Bukkit.getServer().getName() + ": " + msg);
        }
    }

    private void notify(Settings s, String text) {
        if (s.webhookUrl().isEmpty()) {
            return;
        }
        try {
            int code = Notifier.send(s.webhookUrl(), text);
            if (code / 100 != 2) {
                getLogger().warning("Discord webhook answered HTTP " + code);
            }
        } catch (Exception e) {
            getLogger().warning("Discord webhook failed: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "now" -> startBackup(sender, "command by " + sender.getName());
            case "reload" -> {
                load();
                sender.sendMessage("[BucketBackup] Reloaded. " + (settings.valid() ? "Config is valid."
                        : "Config has " + settings.errors().size() + " problem(s): " + String.join("; ", settings.errors())));
            }
            case "status" -> {
                sender.sendMessage("[BucketBackup] " + (settings.valid() ? "Target " + settings.bucket() + "/"
                        + settings.prefix() + "/ at " + settings.endpoint() : "Not set up: " + String.join("; ", settings.errors())));
                sender.sendMessage("  Running now: " + (running.get() ? "yes" : "no")
                        + ", next scheduled: " + (nextRun == null ? "off" : SHOW.format(nextRun)));
                sender.sendMessage("  Last success: " + (lastSuccess == null ? "none" : SHOW.format(lastSuccess))
                        + ". " + lastSummary);
                sender.sendMessage("  Last error: " + lastError);
                sender.sendMessage("  Backs up: " + String.join(", ", selectedPaths()));
            }
            case "list" -> async(sender, () -> {
                List<S3Client.S3Object> b = Retention.backups(settings.prefix(),
                        new S3Client(settings).list(settings.prefix() + "/"));
                StringBuilder sb = new StringBuilder(b.size() + " backup(s) in " + settings.bucket() + "/"
                        + settings.prefix() + "/");
                for (int i = 0; i < Math.min(10, b.size()); i++) {
                    S3Client.S3Object o = b.get(i);
                    sb.append("\n  ").append(o.key().substring(settings.prefix().length() + 1)).append("  ")
                            .append(BackupRunner.human(o.size()));
                }
                return sb.toString();
            });
            case "test" -> async(sender, () -> {
                long t0 = System.nanoTime();
                S3Client c = new S3Client(settings);
                String key = settings.prefix() + "/.bucketbackup-write-test";
                c.putObject(key, ("write test " + Instant.now()).getBytes(StandardCharsets.UTF_8));
                c.list(settings.prefix() + "/");
                c.deleteObject(key);
                return "Connection OK: write, list and delete work (" + (System.nanoTime() - t0) / 1_000_000 + " ms).";
            });
            default -> {
                sender.sendMessage("[BucketBackup] Use /bucketbackup <now|status|list|test|reload>");
                return true;
            }
        }
        return true;
    }

    private interface Job {
        String run() throws Exception;
    }

    private void async(CommandSender sender, Job job) {
        if (!settings.valid()) {
            sender.sendMessage("[BucketBackup] Not set up: " + String.join("; ", settings.errors()));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                tell(sender, job.run());
            } catch (Exception e) {
                tell(sender, "Failed: " + e.getMessage());
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
