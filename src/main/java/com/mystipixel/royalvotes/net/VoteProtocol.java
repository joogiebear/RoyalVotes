package com.mystipixel.royalvotes.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vexsoftware.votifier.model.Vote;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.function.Function;

/**
 * The two Votifier wire protocols, with no Bukkit in sight so they can be tested against plain streams.
 *
 * <p>Both start the same way: the server greets with {@code VOTIFIER 2 <challenge>}. A v1 client
 * ignores the challenge and sends one 256-byte RSA block. A v2 client sends the magic {@code 0x733A},
 * a length, and a JSON envelope whose payload is signed with a shared token and must echo the
 * challenge — which is what stops a captured vote being replayed.
 */
public final class VoteProtocol {

    private static final int V2_MAGIC = 0x733A;
    private static final int V1_BLOCK = 256;
    /** A real v2 message is a few hundred bytes; the length field allows 64k, which we don't need. */
    private static final int V2_MAX_LENGTH = 4096;

    private VoteProtocol() {
    }

    /** Thrown for anything wrong with what the client sent. The message is safe to log. */
    public static final class VoteException extends Exception {
        public VoteException(String message) {
            super(message);
        }

        public VoteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static String greeting(String challenge) {
        return "VOTIFIER 2 " + challenge + "\n";
    }

    /**
     * Read one vote from a connection that has already been greeted.
     *
     * @param rsaKey      private key for v1, or null to refuse v1 votes
     * @param tokenLookup service name → v2 token, returning null when the service has none
     */
    public static Vote read(InputStream in, OutputStream out, String challenge, PrivateKey rsaKey,
                            Function<String, String> tokenLookup) throws IOException, VoteException {
        byte[] head = in.readNBytes(2);
        if (head.length < 2) {
            throw new VoteException("connection closed before a vote was sent");
        }
        int magic = ((head[0] & 0xFF) << 8) | (head[1] & 0xFF);
        if (magic != V2_MAGIC) {
            return readV1(head, in, rsaKey);
        }
        try {
            Vote vote = readV2(in, challenge, tokenLookup);
            out.write("{\"status\":\"ok\"}\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return vote;
        } catch (VoteException bad) {
            // v2 clients wait for a reply, and vote sites surface this text in their test tools.
            JsonObject error = new JsonObject();
            error.addProperty("status", "error");
            error.addProperty("cause", "VoteException");
            error.addProperty("error", bad.getMessage());
            out.write((error + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            throw bad;
        }
    }

    private static Vote readV1(byte[] head, InputStream in, PrivateKey rsaKey)
            throws IOException, VoteException {
        if (rsaKey == null) {
            throw new VoteException("v1 (RSA) vote refused: protocol.v1-enabled is false");
        }
        byte[] block = new byte[V1_BLOCK];
        System.arraycopy(head, 0, block, 0, head.length);
        int read = in.readNBytes(block, head.length, V1_BLOCK - head.length);
        if (read != V1_BLOCK - head.length) {
            throw new VoteException("v1 vote was " + (read + head.length) + " bytes, expected " + V1_BLOCK);
        }
        String text;
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, rsaKey);
            text = new String(cipher.doFinal(block), StandardCharsets.UTF_8);
        } catch (Exception undecryptable) {
            throw new VoteException("v1 vote could not be decrypted — the site has the wrong public key",
                    undecryptable);
        }
        String[] lines = text.split("\n");
        if (lines.length < 5 || !lines[0].equals("VOTE")) {
            throw new VoteException("v1 vote decrypted to something that is not a vote");
        }
        return new Vote(lines[1], lines[2], lines[3], lines[4]);
    }

    private static Vote readV2(InputStream in, String challenge, Function<String, String> tokenLookup)
            throws IOException, VoteException {
        byte[] lengthBytes = in.readNBytes(2);
        if (lengthBytes.length < 2) {
            throw new VoteException("v2 vote ended before its length");
        }
        int length = ((lengthBytes[0] & 0xFF) << 8) | (lengthBytes[1] & 0xFF);
        if (length == 0 || length > V2_MAX_LENGTH) {
            throw new VoteException("v2 vote declared an implausible length of " + length);
        }
        byte[] body = in.readNBytes(length);
        if (body.length != length) {
            throw new VoteException("v2 vote ended early");
        }

        String payloadText;
        String signature;
        JsonObject payload;
        try {
            JsonObject envelope = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            payloadText = envelope.get("payload").getAsString();
            signature = envelope.get("signature").getAsString();
            payload = JsonParser.parseString(payloadText).getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new VoteException("v2 vote was not valid JSON", malformed);
        }

        String service = string(payload, "serviceName");
        String token = tokenLookup.apply(service);
        if (token == null || token.isEmpty()) {
            throw new VoteException("no token configured for service '" + service + "'");
        }
        byte[] expected = hmac(token, payloadText);
        byte[] given;
        try {
            given = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException notBase64) {
            throw new VoteException("v2 signature was not base64");
        }
        // Constant-time, so response timing says nothing about how much of the signature matched.
        if (!MessageDigest.isEqual(expected, given)) {
            throw new VoteException("v2 signature did not match — the site has the wrong token for '"
                    + service + "'");
        }
        if (!challenge.equals(string(payload, "challenge"))) {
            throw new VoteException("v2 challenge did not match (replayed vote?)");
        }

        byte[] extra = null;
        if (payload.has("additionalData") && !payload.get("additionalData").isJsonNull()) {
            try {
                extra = Base64.getDecoder().decode(payload.get("additionalData").getAsString());
            } catch (RuntimeException ignored) {
                // Optional and unused by us; a malformed blob is no reason to lose the vote.
            }
        }
        return new Vote(service, string(payload, "username"), string(payload, "address"),
                string(payload, "timestamp"), extra);
    }

    private static String string(JsonObject object, String key) throws VoteException {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new VoteException("v2 vote is missing '" + key + "'");
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException wrongType) {
            throw new VoteException("v2 vote has a malformed '" + key + "'");
        }
    }

    static byte[] hmac(String token, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException("HmacSHA256 is required of every JVM", impossible);
        }
    }
}
