package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.FilterExpressionParser;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Free-form search / filter expression line prompt (PR-6,
 * SPEC-documents §4.5). User types things like:
 *
 * <pre>kind:howto tag:samples by:SYSOP -tag:beta kick drum</pre>
 *
 * <p>Submit applies the expression onto the current session filter
 * via {@link FilterExpressionParser} and {@code replaceTopAndEnter
 * (DOCS_RESULTS)}. Empty submit cancels (pops). Any warnings from
 * the parser surface as notify messages but the rest of the
 * expression still applies — so a typo on one facet doesn't
 * abandon the whole search.
 */
@ScreenComponent
public class DocsSearchPromptScreen implements Screen {

    private static final InputPrompt PROMPT = new InputPrompt(
            "line",
            "cd/filter expr (e.g. kind:howto tag:samples kick drum):",
            500, null, null);

    private final UserRepository users;

    public DocsSearchPromptScreen(UserRepository users) { this.users = users; }

    @Override public Phase phase() { return Phase.DOCS_SEARCH_PROMPT; }
    @Override public String name() { return "docs-search-prompt"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.INFO_DOCS, "info / docs")) {
            return Transition.None.INSTANCE;
        }
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String text) {
        if (text == null || text.isBlank()) {
            // Empty submission cancels — same as [Esc].
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        DocumentFilter base = DocsCommon.currentFilter(ctx.session());
        FilterExpressionParser parser = new FilterExpressionParser(users);
        List<String> warnings = new ArrayList<>();
        DocumentFilter next = parser.parse(text, base, warnings::add);
        if (next.equals(base)) {
            // Nothing changed — every token was a warning. Surface
            // the first one so the user knows what went wrong, then
            // bounce back so they can try again.
            ctx.send(Frames.notify("notifications",
                    warnings.isEmpty()
                            ? "filter unchanged"
                            : warnings.get(0),
                    "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        DocsCommon.writeFilter(ctx.session(), next);
        ctx.session().setDocsResultsPage(0);
        // If parser still produced warnings (e.g. one bad facet
        // alongside good ones), surface the first as a notify but
        // proceed with the partial application.
        if (!warnings.isEmpty()) {
            ctx.send(Frames.notify("notifications",
                    warnings.get(0), "warn", 3000));
        }
        ctx.replaceTopAndEnter(Phase.DOCS_RESULTS);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.pop();
        return Transition.None.INSTANCE;
    }
}
