package lockmerge.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 合并会话。持久化原始文本、解析诊断、裁决与输出版本。
 * version 用于乐观并发控制；裁决绑定做出裁决时的三输入指纹。
 */
public final class Session {
    public String id;
    public String baseText = "";
    public String leftText = "";
    public String rightText = "";
    public String fingerprint = "";
    public long version = 1;
    public long createdAt;
    public long updatedAt;
    public List<String> diagnostics = new ArrayList<>();
    public List<Decision> decisions = new ArrayList<>();
    public List<OutputVersion> outputs = new ArrayList<>();

    public static final class Decision {
        public String conflictId;
        public String choice; // "left" | "right"
        public String fingerprint;
        public long at;

        public Decision(String conflictId, String choice, String fingerprint, long at) {
            this.conflictId = conflictId;
            this.choice = choice;
            this.fingerprint = fingerprint;
            this.at = at;
        }
    }

    public static final class OutputVersion {
        public long version;
        public String fingerprint;
        public int decisionCount;
        public String content;
        public long at;

        public OutputVersion(long version, String fingerprint, int decisionCount, String content, long at) {
            this.version = version;
            this.fingerprint = fingerprint;
            this.decisionCount = decisionCount;
            this.content = content;
            this.at = at;
        }
    }
}
