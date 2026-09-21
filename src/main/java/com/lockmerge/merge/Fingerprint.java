package com.lockmerge.merge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Binds resolutions to the exact triple of inputs. */
public final class Fingerprint {

    private Fingerprint() {}

    public static String of(String base, String left, String right) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(base.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(left.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(right.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
