package lockmerge.model;

import java.util.List;
import java.util.Objects;

/** 包节点：名称、版本、来源、完整性摘要、可选平台条件、对子节点的精确引用。 */
public final class PackageNode {
    public final String name;
    public final String version;
    public final String source;
    public final String integrity;
    public final PlatformExpr platform; // 可为 null
    public final List<String> deps; // 精确子引用键，升序

    public PackageNode(String name, String version, String source, String integrity,
                       PlatformExpr platform, List<String> deps) {
        this.name = name;
        this.version = version;
        this.source = source;
        this.integrity = integrity;
        this.platform = platform;
        this.deps = List.copyOf(deps);
    }

    public String key() {
        return Keys.of(name, version);
    }

    public PackageNode with(String source, String integrity, PlatformExpr platform, List<String> deps) {
        return new PackageNode(name, version, source, integrity, platform, deps);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PackageNode p)) return false;
        return name.equals(p.name) && version.equals(p.version)
                && source.equals(p.source) && integrity.equals(p.integrity)
                && Objects.equals(platform, p.platform) && deps.equals(p.deps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, version, source, integrity, platform, deps);
    }
}
