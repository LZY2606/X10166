package com.lockmerge.lockfile;

import com.lockmerge.lockfile.Model.Lockfile;
import com.lockmerge.lockfile.Model.NameVersion;
import com.lockmerge.lockfile.Model.PackageNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Canonical printer: stable sorted output (roots, packages, depends all sorted). */
public final class LockfilePrinter {

    private LockfilePrinter() {}

    public static String print(Lockfile lockfile) {
        StringBuilder sb = new StringBuilder();
        sb.append("lockfile v1\n");

        List<NameVersion> roots = new ArrayList<>(lockfile.roots);
        roots.sort(Comparator.naturalOrder());
        if (!roots.isEmpty()) {
            sb.append('\n');
            for (NameVersion root : roots) {
                sb.append("root ").append(root).append('\n');
            }
        }

        List<PackageNode> nodes = new ArrayList<>(lockfile.packages.values());
        nodes.sort(Comparator.comparing(PackageNode::key));
        for (PackageNode node : nodes) {
            sb.append('\n');
            sb.append("package ").append(node.key()).append(" {\n");
            if (node.source != null) sb.append("  source ").append(node.source).append('\n');
            if (node.integrity != null) sb.append("  integrity ").append(node.integrity).append('\n');
            if (node.platform != null) {
                sb.append("  platform ").append(node.platform.canonical()).append('\n');
            }
            List<NameVersion> deps = new ArrayList<>(node.depends);
            deps.sort(Comparator.naturalOrder());
            for (NameVersion dep : deps) {
                sb.append("  depends ").append(dep).append('\n');
            }
            sb.append("}\n");
        }
        return sb.toString();
    }
}
