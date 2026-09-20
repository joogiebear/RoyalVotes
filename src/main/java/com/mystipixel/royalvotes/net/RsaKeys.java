package com.mystipixel.royalvotes.net;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The v1 key pair, stored as {@code rsa/public.key} and {@code rsa/private.key}.
 *
 * <p>Same file names and encoding (base64 of the X.509 / PKCS#8 bytes) as Votifier and NuVotifier, so
 * a server moving over can drop its existing {@code rsa} folder in and keep every vote site working
 * without re-entering a key anywhere.
 */
public final class RsaKeys {

    private RsaKeys() {
    }

    public static KeyPair loadOrCreate(File folder) throws IOException, GeneralSecurityException {
        File publicFile = new File(folder, "public.key");
        File privateFile = new File(folder, "private.key");
        if (publicFile.isFile() && privateFile.isFile()) {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(decode(publicFile)));
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(decode(privateFile)));
            return new KeyPair(publicKey, privateKey);
        }

        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("could not create " + folder);
        }
        // 2048 is what the v1 protocol's fixed 256-byte block implies; a larger key would not fit it.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        write(publicFile, pair.getPublic().getEncoded());
        write(privateFile, pair.getPrivate().getEncoded());
        return pair;
    }

    /** The public key as vote sites want it pasted: one line of base64. */
    public static String publicKeyText(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static byte[] decode(File file) throws IOException {
        // MIME decoder: older Votifier builds wrapped the base64 at 76 columns.
        return Base64.getMimeDecoder().decode(Files.readString(file.toPath(), StandardCharsets.UTF_8).trim());
    }

    private static void write(File file, byte[] key) throws IOException {
        Files.writeString(file.toPath(), Base64.getEncoder().encodeToString(key), StandardCharsets.UTF_8);
    }
}
