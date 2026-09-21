package lockmerge.parse;

import java.util.List;
import lockmerge.model.LockGraph;

public final class ParseResult {
    public final LockGraph graph; // 存在 ERROR 时为 null
    public final List<Diagnostic> diagnostics;

    public ParseResult(LockGraph graph, List<Diagnostic> diagnostics) {
        this.graph = graph;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public boolean ok() {
        return graph != null;
    }
}
