package one.zovo.bucketbackup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** A small S3 client for any S3-compatible store (AWS S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO). */
public final class S3Client {
    public record S3Object(String key, long size, Instant lastModified) { }

    public static final class S3Exception extends IOException {
        private final int status;

        public S3Exception(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final int MAX_TRIES = 3;
    private final HttpClient http;
    private final SigV4 signer;
    private final URI endpoint;
    private final String bucket;
    private final boolean pathStyle;

    public S3Client(Settings s) {
        this(s.endpoint(), s.region(), s.bucket(), s.accessKey(), s.secretKey(), s.pathStyle());
    }

    public S3Client(String endpoint, String region, String bucket, String accessKey, String secretKey,
                    boolean pathStyle) {
        this.endpoint = URI.create(endpoint);
        this.bucket = bucket;
        this.pathStyle = pathStyle;
        this.signer = new SigV4(accessKey, secretKey, region, "s3");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private String host() {
        String h = pathStyle ? endpoint.getHost() : bucket + "." + endpoint.getHost();
        return endpoint.getPort() == -1 ? h : h + ":" + endpoint.getPort();
    }

    private String path(String key) {
        if (key == null) {
            return pathStyle ? "/" + bucket : "/";
        }
        String k = SigV4.uriEncode(key, false);
        return pathStyle ? "/" + bucket + "/" + k : "/" + k;
    }

    private HttpResponse<byte[]> send(String method, String key, Map<String, String> query, byte[] body,
                                      Map<String, String> extraHeaders) throws IOException {
        String encPath = path(key);
        String payloadHash = body == null || body.length == 0 ? SigV4.EMPTY_SHA256 : SigV4.sha256Hex(body);
        String q = SigV4.canonicalQuery(query);
        URI uri = URI.create(endpoint.getScheme() + "://" + host() + encPath + (q.isEmpty() ? "" : "?" + q));
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
            Map<String, String> headers = signer.sign(method, host(), encPath, query, extraHeaders, payloadHash,
                    ZonedDateTime.now());
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5));
            headers.forEach(rb::header);
            rb.method(method, body == null || body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
            try {
                HttpResponse<byte[]> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() / 100 == 2) {
                    return r;
                }
                S3Exception e = new S3Exception(r.statusCode(), describe(r));
                if (r.statusCode() < 500 && r.statusCode() != 429) {
                    throw e;
                }
                last = e;
            } catch (S3Exception e) {
                throw e;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            try {
                Thread.sleep(1000L * attempt * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        throw last;
    }

    private static String describe(HttpResponse<byte[]> r) {
        String body = new String(r.body(), StandardCharsets.UTF_8);
        String code = tag(body, "Code");
        String msg = tag(body, "Message");
        String hint = switch (r.statusCode()) {
            case 403 -> " (check access-key, secret-key, region and that the token can write to this bucket)";
            case 404 -> " (check the bucket name and the endpoint)";
            case 301, 307 -> " (wrong region or endpoint for this bucket)";
            default -> "";
        };
        return "HTTP " + r.statusCode() + (code.isEmpty() ? "" : " " + code) + (msg.isEmpty() ? "" : ": " + msg) + hint;
    }

    private static String tag(String xml, String name) {
        int a = xml.indexOf("<" + name + ">");
        int b = xml.indexOf("</" + name + ">");
        return a >= 0 && b > a ? xml.substring(a + name.length() + 2, b) : "";
    }

    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IOException("Could not read the S3 XML reply", e);
        }
    }

    private static String text(Element parent, String name) {
        NodeList n = parent.getElementsByTagName(name);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent();
    }

    public void putObject(String key, byte[] data) throws IOException {
        send("PUT", key, Map.of(), data, Map.of());
    }

    public void deleteObject(String key) throws IOException {
        send("DELETE", key, Map.of(), null, Map.of());
    }

    public byte[] getObject(String key) throws IOException {
        return send("GET", key, Map.of(), null, Map.of()).body();
    }

    /** Lists every object under the prefix, following continuation tokens. */
    public List<S3Object> list(String prefix) throws IOException {
        List<S3Object> out = new ArrayList<>();
        String token = null;
        do {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("list-type", "2");
            q.put("prefix", prefix);
            q.put("max-keys", "1000");
            if (token != null) {
                q.put("continuation-token", token);
            }
            Document d = parse(send("GET", null, q, null, Map.of()).body());
            NodeList contents = d.getElementsByTagName("Contents");
            for (int i = 0; i < contents.getLength(); i++) {
                Element e = (Element) contents.item(i);
                String lm = text(e, "LastModified");
                out.add(new S3Object(text(e, "Key"), Long.parseLong(text(e, "Size").isEmpty() ? "0" : text(e, "Size")),
                        lm.isEmpty() ? Instant.EPOCH : Instant.parse(lm)));
            }
            boolean truncated = "true".equals(text(d.getDocumentElement(), "IsTruncated"));
            token = truncated ? text(d.getDocumentElement(), "NextContinuationToken") : null;
            if (truncated && (token == null || token.isEmpty())) {
                throw new IOException("S3 list is truncated but has no continuation token");
            }
        } while (token != null);
        return out;
    }

    /** Uploads a file. Files up to one part size go in one PUT, bigger files use a multipart upload. */
    public void upload(String key, Path file, int partSizeMb, LongConsumer progress) throws IOException {
        long size = Files.size(file);
        long partSize = partSizeMb * 1024L * 1024L;
        if (size <= partSize) {
            putObject(key, Files.readAllBytes(file));
            progress.accept(size);
            return;
        }
        Document init = parse(send("POST", key, Map.of("uploads", ""), null,
                Map.of("content-type", "application/zip")).body());
        String uploadId = text(init.getDocumentElement(), "UploadId");
        if (uploadId.isEmpty()) {
            throw new IOException("S3 did not return an UploadId");
        }
        List<String> etags = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[(int) partSize];
            long done = 0;
            for (int part = 1; done < size; part++) {
                int n = in.readNBytes(buf, 0, (int) Math.min(partSize, size - done));
                byte[] body = n == buf.length ? buf : java.util.Arrays.copyOf(buf, n);
                HttpResponse<byte[]> r = send("PUT", key,
                        Map.of("partNumber", Integer.toString(part), "uploadId", uploadId), body, Map.of());
                String etag = r.headers().firstValue("etag").orElseThrow(() -> new IOException("part has no ETag"));
                etags.add(etag);
                done += n;
                progress.accept(done);
            }
            StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
            for (int i = 0; i < etags.size(); i++) {
                xml.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
                        .append(etags.get(i).replace("&", "&amp;").replace("<", "&lt;")).append("</ETag></Part>");
            }
            xml.append("</CompleteMultipartUpload>");
            HttpResponse<byte[]> done2 = send("POST", key, Map.of("uploadId", uploadId),
                    xml.toString().getBytes(StandardCharsets.UTF_8), Map.of("content-type", "application/xml"));
            // S3 can answer 200 with an <Error> body when the complete step fails late.
            String body = new String(done2.body(), StandardCharsets.UTF_8);
            if (body.contains("<Error>")) {
                throw new S3Exception(200, "complete failed: " + tag(body, "Code") + " " + tag(body, "Message"));
            }
        } catch (IOException | RuntimeException e) {
            try {
                send("DELETE", key, Map.of("uploadId", uploadId), null, Map.of());
            } catch (IOException ignored) {
                // The bucket lifecycle rule in the README cleans up parts that could not be aborted.
            }
            throw e;
        }
    }
}
