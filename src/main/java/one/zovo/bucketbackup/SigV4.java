package one.zovo.bucketbackup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 for S3 requests. No SDK, only the JDK. */
public final class SigV4 {
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final String service;

    public SigV4(String accessKey, String secretKey, String region, String service) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        this.service = service;
    }

    /**
     * Signs a request and returns the headers to send (x-amz-date, x-amz-content-sha256, Authorization and
     * every header that was passed in). The host header is signed but must not be sent by the caller: the
     * HTTP client sets it.
     */
    public Map<String, String> sign(String method, String host, String encodedPath, Map<String, String> query,
                                    Map<String, String> headers, String payloadSha256, ZonedDateTime now) {
        ZonedDateTime utc = now.withZoneSameInstant(ZoneOffset.UTC);
        String amzDate = AMZ_DATE.format(utc);
        String date = DATE.format(utc);

        TreeMap<String, String> canon = new TreeMap<>();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            canon.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue().trim().replaceAll(" +", " "));
        }
        canon.put("host", host);
        canon.put("x-amz-date", amzDate);
        canon.put("x-amz-content-sha256", payloadSha256);

        StringBuilder ch = new StringBuilder();
        for (Map.Entry<String, String> h : canon.entrySet()) {
            ch.append(h.getKey()).append(':').append(h.getValue()).append('\n');
        }
        String signedHeaders = String.join(";", canon.keySet());
        String canonicalRequest = method + '\n' + encodedPath + '\n' + canonicalQuery(query) + '\n'
                + ch + '\n' + signedHeaders + '\n' + payloadSha256;
        String scope = date + '/' + region + '/' + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + '\n' + scope + '\n'
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(kSigning, stringToSign));

        TreeMap<String, String> out = new TreeMap<>(canon);
        out.remove("host");
        out.put("authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + '/' + scope
                + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature);
        return out;
    }

    public static String canonicalQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : query.entrySet()) {
            sorted.put(uriEncode(e.getKey(), true), uriEncode(e.getValue() == null ? "" : e.getValue(), true));
        }
        List<String> parts = new ArrayList<>();
        sorted.forEach((k, v) -> parts.add(k + '=' + v));
        return String.join("&", parts);
    }

    /** RFC 3986 encoding as S3 expects it. Keeps '/' when encodeSlash is false (object key paths). */
    public static String uriEncode(String s, boolean encodeSlash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else if (c == '/' && !encodeSlash) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    public static String sha256Hex(byte[] data) {
        return sha256Hex(data, 0, data.length);
    }

    public static String sha256Hex(byte[] data, int off, int len) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data, off, len);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
