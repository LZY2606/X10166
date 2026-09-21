package dev.lockmerge.persist;

import dev.lockmerge.merge.Decision;

import java.util.ArrayList;
import java.util.List;

/** Persisted state of one merge session. */
public class Session {
    public String id;
    public String createdAt;
    public String updatedAt;
    public long revision;
    public String baseText = "";
    public String leftText = "";
    public String rightText = "";
    public final List<Decision> decisions = new ArrayList<>();
    public final List<PublishedVersion> versions = new ArrayList<>();

    public static final class PublishedVersion {
        public int number;
        public String publishedAt;
        public String text;
        public String fingerprintBase;
        public String fingerprintLeft;
        public String fingerprintRight;

        public PublishedVersion() {
        }

        public PublishedVersion(int number, String publishedAt, String text,
                                String fingerprintBase, String fingerprintLeft,
                                String fingerprintRight) {
            this.number = number;
            this.publishedAt = publishedAt;
            this.text = text;
            this.fingerprintBase = fingerprintBase;
            this.fingerprintLeft = fingerprintLeft;
            this.fingerprintRight = fingerprintRight;
        }
    }
}
