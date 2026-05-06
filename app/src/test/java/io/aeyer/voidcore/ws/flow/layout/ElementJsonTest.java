package io.aeyer.voidcore.ws.flow.layout;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ElementJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void headerRoundTrips() throws Exception {
        Element h = new Element.Header("DOCUMENT", "pattern9 (release)");
        String s = json.writeValueAsString(h);
        Element back = json.readValue(s, Element.class);
        assertThat(back).isEqualTo(h);
    }

    @Test
    void textFieldRoundTrips() throws Exception {
        Element f = new Element.TextField("title", "title:", "Pattern Nine", 200, false);
        String s = json.writeValueAsString(f);
        assertThat(json.readValue(s, Element.class)).isEqualTo(f);
    }

    @Test
    void editorRoundTrips() throws Exception {
        Element e = new Element.Editor("body", "line1\nline2", "NORMAL", "markdown", false);
        String s = json.writeValueAsString(e);
        assertThat(json.readValue(s, Element.class)).isEqualTo(e);
    }

    @Test
    void formRoundTripsWithNestedTextField() throws Exception {
        Element form = new Element.Form("doc-form",
                List.of(new Element.TextField("title", "title:", "x", null, false)),
                "title");
        String s = json.writeValueAsString(form);
        assertThat(json.readValue(s, Element.class)).isEqualTo(form);
    }

    @Test
    void keyMenuRoundTrips() throws Exception {
        Element m = new Element.KeyMenu(List.of(
                new Element.KeyMenu.KeyEntry("F", "files"),
                new Element.KeyMenu.KeyEntry("Q", "quit")));
        String s = json.writeValueAsString(m);
        assertThat(json.readValue(s, Element.class)).isEqualTo(m);
    }

    @Test
    void statusLineRoundTrips() throws Exception {
        Element s = new Element.StatusLine("NORMAL", "L 1 C 1", "md utf-8");
        assertThat(json.readValue(json.writeValueAsString(s), Element.class)).isEqualTo(s);
    }

    @Test
    void textFieldWithNullMaxLengthRoundTrips() throws Exception {
        Element f = new Element.TextField("vis", "vis:  ", "public", null, false);
        String s = json.writeValueAsString(f);
        assertThat(json.readValue(s, Element.class)).isEqualTo(f);
    }
}
