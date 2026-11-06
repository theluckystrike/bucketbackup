package one.zovo.bucketbackup;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Names backups and picks old ones to delete. Only touches files this plugin made. */
public final class Retention {
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Pattern OURS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}Z\\.zip");

    private Retention() { }

    public static String keyFor(String prefix, Instant when) {
        return prefix + "/" + STAMP.format(when) + ".zip";
    }

    /** True for keys directly under the prefix that match the backup name pattern. */
    public static boolean isOurs(String prefix, String key) {
        String start = prefix + "/";
        return key.startsWith(start) && OURS.matcher(key.substring(start.length())).matches();
    }

    /** Backups under the prefix, newest first. */
    public static List<S3Client.S3Object> backups(String prefix, List<S3Client.S3Object> all) {
        List<S3Client.S3Object> ours = new ArrayList<>();
        for (S3Client.S3Object o : all) {
            if (isOurs(prefix, o.key())) {
                ours.add(o);
            }
        }
        ours.sort(Comparator.comparing(S3Client.S3Object::key).reversed());
        return ours;
    }

    /**
     * Keys to delete: everything after the newest keepLast, and (when maxAgeDays > 0) anything older than
     * maxAgeDays. The newest backup is never deleted.
     */
    public static List<String> toDelete(String prefix, List<S3Client.S3Object> all, int keepLast, int maxAgeDays,
                                        Instant now) {
        List<S3Client.S3Object> ours = backups(prefix, all);
        List<String> del = new ArrayList<>();
        for (int i = 1; i < ours.size(); i++) {
            S3Client.S3Object o = ours.get(i);
            boolean overCount = i >= keepLast;
            boolean tooOld = maxAgeDays > 0 && Duration.between(stampOf(prefix, o), now).toDays() >= maxAgeDays;
            if (overCount || tooOld) {
                del.add(o.key());
            }
        }
        return del;
    }

    static Instant stampOf(String prefix, S3Client.S3Object o) {
        String name = o.key().substring(prefix.length() + 1, o.key().length() - 4);
        return Instant.from(STAMP.parse(name));
    }
}
