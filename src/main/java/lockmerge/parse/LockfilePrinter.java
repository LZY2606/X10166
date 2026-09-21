package lockmerge.parse;

import lockmerge.model.LockGraph;
import lockmerge.model.PackageNode;

/** 稳定排序的确定性打印器：根与包按字典序，字段固定顺序，子引用升序。 */
public final class LockfilePrinter {

    public static String print(LockGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("lockfile 1\n");
        for (String root : graph.roots) {
            sb.append('\n');
            sb.append("root ").append(quote(root)).append('\n');
        }
        for (PackageNode node : graph.packages.values()) {
            sb.append('\n');
            sb.append("package ").append(quote(node.key())).append(" {\n");
            sb.append("  source ").append(quote(node.source)).append('\n');
            sb.append("  integrity ").append(quote(node.integrity)).append('\n');
            if (node.platform != null) {
                sb.append("  platform ").append(quote(node.platform.canonical())).append('\n');
            }
            for (String dep : node.deps) {
                sb.append("  dep ").append(quote(dep)).append('\n');
            }
            sb.append("}\n");
        }
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
