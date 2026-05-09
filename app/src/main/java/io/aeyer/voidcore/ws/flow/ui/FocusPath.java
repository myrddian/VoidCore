package io.aeyer.voidcore.ws.flow.ui;

import io.aeyer.voidcore.ws.flow.layout.Element;

import java.util.List;
import java.util.Optional;

/**
 * Helpers for locating focusable widgets in an {@link Element} tree.
 *
 * <p>Pure functions — no state. The {@code ScreenApp} owns the focus
 * path string and mutates it in response to FocusMove events.
 */
public final class FocusPath {

    private FocusPath() {}

    /**
     * Walk the tree depth-first and return the {@code id} of the first
     * focusable widget, or empty if none. Inside a {@link Element.Form}
     * the {@code focusedChildId} wins if present.
     */
    public static Optional<String> firstFocusable(Element root) {
        return switch (root) {
            case Element.TextField tf -> Optional.of(tf.id());
            case Element.Editor    ed -> Optional.of(ed.id());
            case Element.Shell     s  -> firstFocusable(s.body());
            case Element.Form      f  -> {
                if (f.focusedChildId() != null) yield Optional.of(f.focusedChildId());
                yield walkChildren(f.children());
            }
            case Element.VStack    v  -> walkChildren(v.children());
            case Element.AnsiBlock a  -> Optional.empty();
            case Element.Padded    p  -> firstFocusable(p.child());
            case Element.Styled    s  -> firstFocusable(s.child());
            default -> Optional.empty();
        };
    }

    private static Optional<String> walkChildren(List<Element> kids) {
        for (Element k : kids) {
            Optional<String> id = firstFocusable(k);
            if (id.isPresent()) return id;
        }
        return Optional.empty();
    }
}
