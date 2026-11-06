package one.zovo.bucketbackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Test vectors from the AWS S3 docs page "Examples: Signature Calculations in AWS Signature Version 4". */
class SigV4Test {
    private final SigV4 signer = new SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "us-east-1", "s3");
    private final ZonedDateTime when = ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void getObjectWithRange() {
        Map<String, String> h = signer.sign("GET", "examplebucket.s3.amazonaws.com", "/test.txt", Map.of(),
                Map.of("Range", "bytes=0-9"), SigV4.EMPTY_SHA256, when);
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", h.get("authorization"));
        assertEquals("20130524T000000Z", h.get("x-amz-date"));
        assertTrue(!h.containsKey("host"), "host is set by the HTTP client");
    }

    @Test
    void putObject() {
        byte[] body = "Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8);
        String hash = SigV4.sha256Hex(body);
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Date", "Fri, 24 May 2013 00:00:00 GMT");
        headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        Map<String, String> h = signer.sign("PUT", "examplebucket.s3.amazonaws.com",
                "/" + SigV4.uriEncode("test$file.text", false), Map.of(), headers, hash, when);
        assertTrue(h.get("authorization").endsWith(
                "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,"
                + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"), h.get("authorization"));
    }

    @Test
    void listObjects() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("prefix", "J");
        q.put("max-keys", "2");
        Map<String, String> h = signer.sign("GET", "examplebucket.s3.amazonaws.com", "/", q, Map.of(),
                SigV4.EMPTY_SHA256, when);
        assertTrue(h.get("authorization").endsWith(
                "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"), h.get("authorization"));
    }

    @Test
    void encoding() {
        assertEquals("a%20b/c%2Bd~e", SigV4.uriEncode("a b/c+d~e", false));
        assertEquals("a%2Fb", SigV4.uriEncode("a/b", true));
        assertEquals("%C3%A9", SigV4.uriEncode("é", true));
        assertEquals("a=1&b=&uploads=", SigV4.canonicalQuery(new java.util.TreeMap<>(Map.of("uploads", "", "b", "", "a", "1"))));
    }
}
