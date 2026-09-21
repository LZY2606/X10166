package mergeroom.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 content fingerprints binding verdicts to their exact three inputs. */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(String text) {
        if (text == null) {
            text = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String triple(String base, String left, String right) {
        return sha256(sha256(base) + ":" + sha256(left) + ":" + sha256(right));
    }
}
