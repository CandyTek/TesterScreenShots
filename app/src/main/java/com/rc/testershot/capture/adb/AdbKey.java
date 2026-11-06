package com.rc.testershot.capture.adb;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

/** The app's persistent ADB RSA key (2048 bit, e = 65537), with adb-compatible signing and public key format. */
public final class AdbKey {
    private static final int KEY_BITS = 2048;
    private static final int KEY_BYTES = KEY_BITS / 8;
    private static final int KEY_WORDS = KEY_BITS / 32;

    /** DER prefix of a SHA-1 DigestInfo; adbd verifies RSA_sign(NID_sha1, token). */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final RSAPrivateKey priv;
    private final RSAPublicKey pub;

    private AdbKey(RSAPrivateKey priv, RSAPublicKey pub) {
        this.priv = priv;
        this.pub = pub;
    }

    public static synchronized AdbKey loadOrCreate(Context c) throws IOException {
        File dir = new File(c.getFilesDir(), "adb");
        File privFile = new File(dir, "adbkey"), pubFile = new File(dir, "adbkey.x509");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (privFile.isFile() && pubFile.isFile()) {
                RSAPrivateKey pk = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(read(privFile)));
                RSAPublicKey pu = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(read(pubFile)));
                return new AdbKey(pk, pu);
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4));
            KeyPair kp = g.generateKeyPair();
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            write(privFile, kp.getPrivate().getEncoded());
            write(pubFile, kp.getPublic().getEncoded());
            return new AdbKey((RSAPrivateKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic());
        } catch (GeneralSecurityException e) {
            throw new IOException("ADB key: " + e, e);
        }
    }

    /** PKCS#1 v1.5 signature of the 20-byte token, treating the token as a SHA-1 digest. */
    public byte[] sign(byte[] token) {
        byte[] em = new byte[KEY_BYTES];
        int tLen = SHA1_DIGEST_INFO.length + token.length;
        em[0] = 0x00;
        em[1] = 0x01;
        int psEnd = KEY_BYTES - tLen - 1;
        for (int i = 2; i < psEnd; i++) em[i] = (byte) 0xff;
        em[psEnd] = 0x00;
        System.arraycopy(SHA1_DIGEST_INFO, 0, em, psEnd + 1, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, em, psEnd + 1 + SHA1_DIGEST_INFO.length, token.length);
        BigInteger s = new BigInteger(1, em).modPow(priv.getPrivateExponent(), priv.getModulus());
        return toFixed(s, KEY_BYTES);
    }

    /** "base64(android_pubkey) user@host\0" as sent in AUTH RSAPUBLICKEY. */
    public byte[] adbPublicKeyPayload(String comment) {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(KEY_WORDS);
        bb.putInt(n0inv.intValue());
        putWordsLE(bb, n);
        putWordsLE(bb, rr);
        bb.putInt(pub.getPublicExponent().intValue());

        String b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        return (b64 + " " + comment + "\u0000").getBytes(StandardCharsets.US_ASCII);
    }

    private static void putWordsLE(ByteBuffer bb, BigInteger v) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < KEY_WORDS; i++) {
            bb.putInt(v.shiftRight(32 * i).and(mask).intValue());
        }
    }

    private static byte[] toFixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        if (raw.length == len) return raw;
        byte[] out = new byte[len];
        if (raw.length > len) System.arraycopy(raw, raw.length - len, out, 0, len);
        else System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        return out;
    }

    private static byte[] read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return b;
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
