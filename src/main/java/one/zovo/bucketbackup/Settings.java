package one.zovo.bucketbackup;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Parsed and checked config.yml. Environment variables override the keys so secrets can stay out of files. */
public record Settings(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        String prefix,
        int intervalMinutes,
        boolean backupOnStartup,
        List<String> worlds,
        List<String> extraPaths,
        List<String> exclude,
        int keepLast,
        int maxAgeDays,
        int partSizeMb,
        int compressionLevel,
        int keepLocalCopies,
        String webhookUrl,
        String notifyOn,
        List<String> errors) {

    public static final String ENV_ACCESS = "BUCKETBACKUP_ACCESS_KEY";
    public static final String ENV_SECRET = "BUCKETBACKUP_SECRET_KEY";

    public boolean valid() {
        return errors.isEmpty();
    }

    public static Settings from(ConfigurationSection c, Map<String, String> env) {
        List<String> errors = new ArrayList<>();
        String endpoint = c.getString("storage.endpoint", "").trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        String region = c.getString("storage.region", "auto").trim();
        String bucket = c.getString("storage.bucket", "").trim();
        String access = env.getOrDefault(ENV_ACCESS, c.getString("storage.access-key", "")).trim();
        String secret = env.getOrDefault(ENV_SECRET, c.getString("storage.secret-key", "")).trim();
        String ps = c.getString("storage.path-style", "auto").trim().toLowerCase(Locale.ROOT);

        if (endpoint.isEmpty()) {
            errors.add("storage.endpoint is empty. Example: https://<account-id>.r2.cloudflarestorage.com");
        } else {
            try {
                URI u = URI.create(endpoint);
                if (u.getHost() == null || !(u.getScheme().equals("https") || u.getScheme().equals("http"))) {
                    errors.add("storage.endpoint must start with https:// (or http:// for a local test server)");
                }
            } catch (IllegalArgumentException e) {
                errors.add("storage.endpoint is not a valid URL: " + endpoint);
            }
        }
        if (bucket.isEmpty()) {
            errors.add("storage.bucket is empty");
        } else if (!bucket.matches("[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9]")) {
            errors.add("storage.bucket '" + bucket + "' is not a valid bucket name (3-63 chars, a-z 0-9 . -)");
        }
        if (access.isEmpty() || access.startsWith("CHANGE")) {
            errors.add("storage.access-key is not set (or set the env variable " + ENV_ACCESS + ")");
        }
        if (secret.isEmpty() || secret.startsWith("CHANGE")) {
            errors.add("storage.secret-key is not set (or set the env variable " + ENV_SECRET + ")");
        }
        boolean pathStyle = switch (ps) {
            case "true" -> true;
            case "false" -> false;
            default -> !endpoint.contains("amazonaws.com");
        };

        String prefix = c.getString("backup.prefix", "minecraft/server").trim();
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (prefix.isEmpty()) {
            errors.add("backup.prefix must not be empty. Retention only deletes files under this prefix.");
        }
        int interval = c.getInt("backup.interval-minutes", 360);
        if (interval < 0) {
            errors.add("backup.interval-minutes must be 0 (off) or more");
        } else if (interval > 0 && interval < 5) {
            errors.add("backup.interval-minutes must be at least 5");
        }
        int keepLast = c.getInt("retention.keep-last", 14);
        int maxAge = c.getInt("retention.max-age-days", 0);
        if (keepLast < 1) {
            errors.add("retention.keep-last must be 1 or more");
        }
        if (maxAge < 0) {
            errors.add("retention.max-age-days must be 0 (off) or more");
        }
        int part = c.getInt("upload.part-size-mb", 16);
        if (part < 5 || part > 512) {
            errors.add("upload.part-size-mb must be between 5 and 512 (S3 rule: parts are at least 5 MB)");
        }
        int level = c.getInt("backup.compression-level", 6);
        if (level < 0 || level > 9) {
            errors.add("backup.compression-level must be between 0 and 9");
        }
        int keepLocal = c.getInt("backup.keep-local-copies", 0);
        if (keepLocal < 0) {
            errors.add("backup.keep-local-copies must be 0 or more");
        }
        String notify = c.getString("notify.when", "failure").trim().toLowerCase(Locale.ROOT);
        if (!List.of("failure", "always", "never").contains(notify)) {
            errors.add("notify.when must be failure, always or never");
        }
        String webhook = c.getString("notify.discord-webhook", "").trim();
        if (!webhook.isEmpty() && !webhook.startsWith("https://")) {
            errors.add("notify.discord-webhook must start with https://");
        }
        List<String> worlds = c.getStringList("backup.worlds");
        if (worlds.isEmpty()) {
            worlds = List.of("*");
        }
        for (String p : c.getStringList("backup.extra-paths")) {
            if (p.contains("..") || p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
                errors.add("backup.extra-paths entry '" + p + "' must be a path inside the server folder");
            }
        }
        return new Settings(endpoint, region.isEmpty() ? "auto" : region, bucket, access, secret, pathStyle, prefix,
                interval, c.getBoolean("backup.on-startup", false), List.copyOf(worlds),
                List.copyOf(c.getStringList("backup.extra-paths")), List.copyOf(c.getStringList("backup.exclude")),
                keepLast, maxAge, part, level, keepLocal, webhook, notify, List.copyOf(errors));
    }
}
