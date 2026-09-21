package lockmerge.model;

import java.util.Objects;

/** A root declaration pins an exact node name@version. */
public record RootDep(NodeKey pin) {

    public RootDep {
        Objects.requireNonNull(pin, "pin");
    }

    public String name() {
        return pin.name();
    }

    public String reference() {
        return pin.reference();
    }
}
