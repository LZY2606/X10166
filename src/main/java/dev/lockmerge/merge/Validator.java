package dev.lockmerge.merge;

import dev.lockmerge.model.Diagnostic;
import dev.lockmerge.model.LockDocument;
import dev.lockmerge.model.Node;
import dev.lockmerge.model.Ref;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Semantic checks for a single parsed document. */
public final class Validator {

    private Validator() {
    }

    public record IntegrityClash(String source, String version,
                                 String integrityA, String integrityB, String nodeA, String nodeB) {
    }

    public static final class Report {
        public final List<Diagnostic> diagnostics;
        public final List<Ref> danglingRefs;
        public final List<IntegrityClash> integrityClashes;

        Report(List<Diagnostic> diagnostics,
               List<Ref> danglingRefs,
               List<IntegrityClash> integrityClashes) {
            this.diagnostics = List.copyOf(diagnostics);
            this.danglingRefs = List.copyOf(danglingRefs);
            this.integrityClashes = List.copyOf(integrityClashes);
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }
    }

    public static Report validate(LockDocument document) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Node> byId = document.nodeIndex();
        Set<String> danglingKeys = new HashSet<>();
        List<Ref> dangling = new ArrayList<>();

        checkRef(document.roots(), "root", byId, danglingKeys, dangling);
        for (Node node : document.nodes()) {
            checkRef(node.children(), "child of " + node.id(), byId, danglingKeys, dangling);
        }

        // Same source coordinate + version must always map to one digest.
        Map<String, String> digestBySourceVersion = new HashMap<>();
        Map<String, String> ownerBySourceVersion = new HashMap<>();
        List<IntegrityClash> clashes = new ArrayList<>();
        for (Node node : document.nodes()) {
            String key = node.source() + "|" + node.version();
            if (node.source().isBlank() || node.integrity().isBlank()) {
                continue;
            }
            String previous = digestBySourceVersion.get(key);
            String previousOwner = ownerBySourceVersion.get(key);
            if (previous != null && !previous.equals(node.integrity())) {
                clashes.add(new IntegrityClash(node.source(), node.version(),
                        previous, node.integrity(), previousOwner, node.id()));
            } else if (previous == null) {
                digestBySourceVersion.put(key, node.integrity());
                ownerBySourceVersion.put(key, node.id());
            }
        }
        for (IntegrityClash clash : clashes) {
            diagnostics.add(Diagnostic.error(0, "INTEGRITY_CONFLICT",
                    "source '" + clash.source() + "' version " + clash.version()
                            + " claims conflicting digests (" + clash.nodeA() + " has "
                            + clash.integrityA() + ", " + clash.nodeB() + " has "
                            + clash.integrityB() + ")"));
        }
        return new Report(diagnostics, dangling, clashes);
    }

    private static void checkRef(List<Ref> refs, String role, Map<String, Node> byId,
                                 Set<String> seenKeys, List<Ref> dangling) {
        for (Ref ref : refs) {
            if (!byId.containsKey(ref.name() + "@" + ref.version())) {
                if (seenKeys.add(ref.key())) {
                    dangling.add(ref);
                }
            }
        }
    }
}
