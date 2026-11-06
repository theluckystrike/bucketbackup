package one.zovo.bucketbackup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Posts a short message to a Discord webhook. Failures are logged by the caller, never thrown. */
public final class Notifier {
    private Notifier() { }

    public static String json(String text) {
        StringBuilder sb = new StringBuilder("{\"username\":\"BucketBackup\",\"content\":\"");
        String t = text.length() > 1900 ? text.substring(0, 1900) + "..." : text;
        for (char c : t.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\",\"allowed_mentions\":{\"parse\":[]}}").toString();
    }

    public static int send(String webhookUrl, String text) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest r = HttpRequest.newBuilder(URI.create(webhookUrl)).timeout(Duration.ofSeconds(15))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(text))).build();
        return http.send(r, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
