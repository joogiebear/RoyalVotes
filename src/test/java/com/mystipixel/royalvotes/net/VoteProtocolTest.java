package com.mystipixel.royalvotes.net;

import com.google.gson.JsonObject;
import com.vexsoftware.votifier.model.Vote;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builds votes the way a vote site does and checks they come out the other side. */
class VoteProtocolTest {

    private static final String CHALLENGE = "challenge123";
    private static final String TOKEN = "s3cret-token";

    private static KeyPair keys;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    private static byte[] v1(KeyPair pair, String service, String username) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pair.getPublic());
        return cipher.doFinal(("VOTE\n" + service + "\n" + username + "\n1.2.3.4\n1700000000\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] v2(String token, String challenge, String service, String username) {
        JsonObject payload = new JsonObject();
        payload.addProperty("serviceName", service);
        payload.addProperty("username", username);
        payload.addProperty("address", "1.2.3.4");
        payload.addProperty("timestamp", 1700000000000L);
        payload.addProperty("challenge", challenge);
        String payloadText = payload.toString();

        JsonObject envelope = new JsonObject();
        envelope.addProperty("payload", payloadText);
        envelope.addProperty("signature",
                Base64.getEncoder().encodeToString(VoteProtocol.hmac(token, payloadText)));
        byte[] body = envelope.toString().getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x73);
        out.write(0x3A);
        out.write(body.length >> 8);
        out.write(body.length & 0xFF);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static Vote read(byte[] wire, ByteArrayOutputStream reply, boolean allowV1) throws Exception {
        return VoteProtocol.read(new ByteArrayInputStream(wire), reply, CHALLENGE,
                allowV1 ? keys.getPrivate() : null, service -> TOKEN);
    }

    @Test
    void v1VoteDecrypts() throws Exception {
        Vote vote = read(v1(keys, "ExampleSite.com", "Steve"), new ByteArrayOutputStream(), true);
        assertEquals("ExampleSite.com", vote.getServiceName());
        assertEquals("Steve", vote.getUsername());
        assertEquals("1.2.3.4", vote.getAddress());
    }

    @Test
    void v1VoteEncryptedForAnotherServerIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] wire = v1(generator.generateKeyPair(), "ExampleSite.com", "Steve");
        assertThrows(VoteProtocol.VoteException.class, () -> read(wire, new ByteArrayOutputStream(), true));
    }

    @Test
    void v1IsRefusedWhenDisabled() throws Exception {
        byte[] wire = v1(keys, "ExampleSite.com", "Steve");
        assertThrows(VoteProtocol.VoteException.class, () -> read(wire, new ByteArrayOutputStream(), false));
    }

    @Test
    void v2VoteVerifiesAndIsAcknowledged() throws Exception {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        Vote vote = read(v2(TOKEN, CHALLENGE, "ExampleSite.com", "Alex"), reply, true);
        assertEquals("Alex", vote.getUsername());
        assertEquals("1700000000000", vote.getTimeStamp());
        assertTrue(reply.toString(StandardCharsets.UTF_8).contains("\"ok\""));
    }

    @Test
    void v2WrongTokenIsRejectedWithAnErrorReply() {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        assertThrows(VoteProtocol.VoteException.class,
                () -> read(v2("not-the-token", CHALLENGE, "ExampleSite.com", "Alex"), reply, true));
        assertTrue(reply.toString(StandardCharsets.UTF_8).contains("\"error\""));
    }

    @Test
    void v2ReplayedChallengeIsRejected() {
        assertThrows(VoteProtocol.VoteException.class,
                () -> read(v2(TOKEN, "an-older-challenge", "ExampleSite.com", "Alex"),
                        new ByteArrayOutputStream(), true));
    }

    @Test
    void truncatedInputIsRejectedNotHung() {
        assertThrows(VoteProtocol.VoteException.class,
                () -> read(new byte[] {0x73, 0x3A, 0x01}, new ByteArrayOutputStream(), true));
        assertThrows(VoteProtocol.VoteException.class,
                () -> read(new byte[100], new ByteArrayOutputStream(), true));
    }
}
