package one.zovo.bucketbackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {
    private YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void shippedConfigIsInvalidUntilFilledIn() {
        Settings s = Settings.from(defaults(), Map.of());
        assertFalse(s.valid());
        assertEquals(4, s.errors().size(), s.errors().toString());
        assertEquals(360, s.intervalMinutes());
        assertEquals(14, s.keepLast());
        assertEquals("minecraft/my-server", s.prefix());
    }

    @Test
    void r2ConfigWithEnvSecrets() {
        YamlConfiguration c = defaults();
        c.set("storage.endpoint", "https://abc123.r2.cloudflarestorage.com/");
        c.set("storage.bucket", "mc-backups");
        Settings s = Settings.from(c, Map.of(Settings.ENV_ACCESS, "AK", Settings.ENV_SECRET, "SK"));
        assertTrue(s.valid(), s.errors().toString());
        assertEquals("https://abc123.r2.cloudflarestorage.com", s.endpoint());
        assertTrue(s.pathStyle());
        assertEquals("AK", s.accessKey());
    }

    @Test
    void awsUsesVirtualHostStyle() {
        YamlConfiguration c = defaults();
        c.set("storage.endpoint", "https://s3.eu-central-1.amazonaws.com");
        c.set("storage.bucket", "mc-backups");
        c.set("storage.access-key", "AK");
        c.set("storage.secret-key", "SK");
        assertFalse(Settings.from(c, Map.of()).pathStyle());
    }

    @Test
    void rejectsBadValues() throws InvalidConfigurationException {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("""
                storage: {endpoint: 'ftp://x', bucket: 'Bad_Bucket', access-key: a, secret-key: b}
                backup: {interval-minutes: 2, prefix: '/', compression-level: 11, extra-paths: ['../secret']}
                retention: {keep-last: 0}
                upload: {part-size-mb: 1}
                notify: {when: sometimes, discord-webhook: 'http://x'}
                """);
        Settings s = Settings.from(c, Map.of());
        assertEquals(10, s.errors().size(), String.join("\n", s.errors()));
    }
}
