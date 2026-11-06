package one.zovo.bucketbackup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NotifierTest {
    @Test
    void escapesJsonAndBlocksMentions() {
        assertEquals("{\"username\":\"BucketBackup\",\"content\":\"a \\\"b\\\"\\nc\\\\d\\u0001\","
                + "\"allowed_mentions\":{\"parse\":[]}}", Notifier.json("a \"b\"\r\nc\\d\u0001"));
    }

    @Test
    void postsToTheWebhook() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> got = new AtomicReference<>();
        srv.createContext("/hook", ex -> {
            got.set(ex.getRequestMethod() + " " + ex.getRequestHeaders().getFirst("content-type") + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        srv.start();
        try {
            int code = Notifier.send("http://127.0.0.1:" + srv.getAddress().getPort() + "/hook", "Backup FAILED");
            assertEquals(204, code);
            assertEquals("POST application/json " + Notifier.json("Backup FAILED"), got.get());
        } finally {
            srv.stop(0);
        }
    }
}
