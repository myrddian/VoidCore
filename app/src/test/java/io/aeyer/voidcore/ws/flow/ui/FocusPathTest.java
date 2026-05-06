package io.aeyer.voidcore.ws.flow.ui;

import io.aeyer.voidcore.ws.flow.layout.Element;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FocusPathTest {

    @Test
    void firstFocusableSkipsLayoutPrimitives() {
        Element tree = new Element.VStack(List.of(
                new Element.Header("X", null),
                new Element.Spacer(1),
                new Element.TextField("title", "t:", "v", null, false),
                new Element.Editor("body", "", "NORMAL", "markdown", false)
        ), 0);
        Optional<String> id = FocusPath.firstFocusable(tree);
        assertThat(id).contains("title");
    }

    @Test
    void firstFocusableUsesFormFocusedChild() {
        Element form = new Element.Form("f", List.of(
                new Element.TextField("a", "a:", "", null, false),
                new Element.TextField("b", "b:", "", null, false)
        ), "b");
        assertThat(FocusPath.firstFocusable(form)).contains("b");
    }

    @Test
    void firstFocusableReturnsEmptyForNoFocusable() {
        Element tree = new Element.VStack(List.of(
                new Element.Header("X", null), new Element.Spacer(1)), 0);
        assertThat(FocusPath.firstFocusable(tree)).isEmpty();
    }
}
