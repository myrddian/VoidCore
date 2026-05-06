package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.flow.ui.AppStateRepository;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

import java.util.List;
import java.util.Optional;

/**
 * Unified document view+edit screen. Replaces DOCUMENT_VIEW,
 * DOCUMENT_EDIT_*, DOCS_NEW_* (14 phase-screens collapse into one).
 *
 * <h3>State machine</h3>
 * <pre>
 *   VIEW ──E──▶ EDIT_MENU ──T──▶ EDITING_TITLE
 *     │             │       ──G──▶ EDITING_TAGS
 *     │             │       ──B──▶ EDITING_BODY
 *     │             │       ──V──▶ (vis cycle, stays in EDIT_MENU)
 *     │             └──Q──▶ VIEW
 *     └──Q/Esc──▶ (popAndExit)
 * </pre>
 *
 * <p>The active document is identified by
 * {@code session.currentDocumentId()} — set by the intent handler
 * before pushing this screen. If it is null on enter, the screen
 * bounces immediately.
 */
@ScreenAppComponent
public class DocumentScreen extends ScreenApp {

    /** State machine for the document editor UX. */
    private enum UiState { VIEW, EDIT_MENU, EDITING_TITLE, EDITING_TAGS, EDITING_BODY }

    private final DocumentRepository docs;
    private final AppStateRepository appStateRepo;
    /** Tracks for which doc id we've already shown the "(restored from snapshot)" toast. */
    private Long restoredToastShownForDocId;
    /** Current state; reset to VIEW on every onEnter. */
    private UiState uiState = UiState.VIEW;

    public DocumentScreen(DocumentRepository docs, AppStateRepository appStateRepo) {
        this.docs = docs;
        this.appStateRepo = appStateRepo;
    }

    @Override public Phase phase() { return Phase.DOCUMENT_SCREEN; }
    @Override public String name() { return "document-screen"; }

    @Override
    protected String appKey(BbsContext ctx) {
        Long id = ctx.session().currentDocumentId();
        return id == null ? "doc:none" : "doc:" + id;
    }

    @Override
    protected String bannerLabel(BbsContext ctx) {
        Long id = ctx.session().currentDocumentId();
        if (id == null) return "DOCUMENT";
        // Title is what the user thinks of as the document name. The
        // numeric id is the stable PK for cross-reference; slug exists
        // but is opaque ("untitled-1777778433957") and unhelpful in
        // the banner. Format: "DOCUMENT · <title> [#42] (article)".
        return docs.findById(id)
            .map(d -> {
                String title = d.title() == null || d.title().isBlank()
                        ? "(untitled)"
                        : d.title();
                return "DOCUMENT · " + title
                        + "  [#" + d.id() + "]"
                        + "  (" + d.kind().wireValue() + ")";
            })
            .orElse("DOCUMENT");
    }

    // -------------------------------------------------------------------------
    // Framework entry point
    // -------------------------------------------------------------------------

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.INFO_DOCS, "info / docs")) {
            return Transition.None.INSTANCE;
        }
        if (ctx.session().currentDocumentId() == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        uiState = UiState.VIEW;
        return super.onEnter(ctx);   // framework handles tree compose + repaint + InputPrompt
    }

    // -------------------------------------------------------------------------
    // InputPrompt — state-driven
    // -------------------------------------------------------------------------

    @Override
    protected ServerMessage.InputPrompt defaultInputPrompt(BbsContext ctx) {
        return inputPromptForState();
    }

    private ServerMessage.InputPrompt inputPromptForState() {
        return switch (uiState) {
            case VIEW -> new ServerMessage.InputPrompt(
                "keystroke",
                "doc:  [E] edit   [Q] quit",
                null, "EQ", null);
            case EDIT_MENU -> new ServerMessage.InputPrompt(
                "keystroke",
                "edit:  [T] title   [G] tags   [V] cycle vis   [B] body   [Q] back",
                null, "TGVBQ", null);
            case EDITING_TITLE -> new ServerMessage.InputPrompt(
                "none", null, null, null, null);
            case EDITING_TAGS -> new ServerMessage.InputPrompt(
                "none", null, null, null, null);
            case EDITING_BODY -> new ServerMessage.InputPrompt(
                "none", null, null, null, null);
        };
    }

    // -------------------------------------------------------------------------
    // Layout composition — state-driven
    // -------------------------------------------------------------------------

    @Override
    protected Element compose(BbsContext ctx) {
        Long id = ctx.session().currentDocumentId();
        if (id == null) return blank();
        Optional<DocumentRow> maybe = docs.findById(id);
        if (maybe.isEmpty()) return blank();
        DocumentRow doc = maybe.get();
        boolean canEdit = ctx.services().documents().canEdit(ctx.session(), doc);

        String body = doc.body() == null ? "" : doc.body();
        String token = ctx.session().sessionToken();
        if (token != null) {
            Optional<com.fasterxml.jackson.databind.node.ObjectNode> snap =
                appStateRepo.read(token, "doc:" + id);
            if (snap.isPresent()) {
                try {
                    java.time.OffsetDateTime snapAt =
                        java.time.OffsetDateTime.parse(
                            snap.get().path("snapshot_at").asText());
                    if (doc.updatedAt() != null && snapAt.isAfter(doc.updatedAt())) {
                        body = snap.get().path("body_snapshot").asText("");
                        // Fire the toast only once per doc-entry, not on every recompose.
                        if (restoredToastShownForDocId == null
                                || !restoredToastShownForDocId.equals(id)) {
                            ctx.send(Frames.notify("notifications",
                                "(restored from snapshot)", "info", 3000));
                            restoredToastShownForDocId = id;
                        }
                    }
                } catch (Exception ignored) {
                    /* malformed snapshot — fall through to DB body */
                }
            }
        }

        // Determine editor mode and focused field based on UI state.
        String editorMode;
        String focusedChildId;
        switch (uiState) {
            case EDITING_BODY -> {
                editorMode = "NORMAL";
                focusedChildId = "body";
            }
            case EDITING_TITLE -> {
                editorMode = "READ_ONLY";
                focusedChildId = "title";
            }
            case EDITING_TAGS -> {
                editorMode = "READ_ONLY";
                focusedChildId = "tags";
            }
            default -> {
                // VIEW and EDIT_MENU: body is read-only, no field focused
                editorMode = "READ_ONLY";
                focusedChildId = null;
            }
        }

        return new Element.VStack(java.util.List.of(
            new Element.Form("doc-form", java.util.List.of(
                new Element.TextField("title", "title:", doc.title(), 200,
                    !canEdit || uiState != UiState.EDITING_TITLE),
                new Element.TextField("kind",  "kind: ",
                    doc.kind() == null ? "" : doc.kind().wireValue(),
                    null, true),                      // always readOnly
                new Element.TextField("tags",  "tags: ",
                    String.join(", ", doc.tags() == null ? java.util.List.of() : doc.tags()),
                    100, !canEdit || uiState != UiState.EDITING_TAGS),
                new Element.TextField("vis",   "vis:  ",
                    doc.visibility().wireValue(), null, !canEdit),
                new Element.Editor("body", body,
                    editorMode,
                    "markdown",
                    !canEdit)          // readOnly = no edit permission (permission gate)
            ), focusedChildId)
        ), 0);
    }

    // -------------------------------------------------------------------------
    // Keystroke handling — server-side (VIEW and EDIT_MENU states)
    // -------------------------------------------------------------------------

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        Long id = ctx.session().currentDocumentId();
        if (id == null) return Transition.None.INSTANCE;

        switch (uiState) {
            case VIEW -> {
                switch (k) {
                    case "E" -> {
                        if (canCurrentUserEdit(ctx, id)) {
                            uiState = UiState.EDIT_MENU;
                            repaintNow(ctx);
                            ctx.send(inputPromptForState());
                        } else {
                            ctx.send(Frames.notify("notifications",
                                "read-only — you don't have edit permission",
                                "warn", 2500));
                        }
                    }
                    case "Q" -> {
                        ctx.session().setCurrentDocumentId(null);
                        popAndExit(ctx);
                    }
                }
            }
            case EDIT_MENU -> {
                switch (k) {
                    case "T" -> {
                        uiState = UiState.EDITING_TITLE;
                        repaintNow(ctx);
                        ctx.send(inputPromptForState());
                    }
                    case "G" -> {
                        uiState = UiState.EDITING_TAGS;
                        repaintNow(ctx);
                        ctx.send(inputPromptForState());
                    }
                    case "V" -> {
                        // Cycle visibility immediately and stay in EDIT_MENU.
                        DocumentRow doc = docs.findById(id).orElse(null);
                        if (doc != null) {
                            handleVisCycle(ctx, doc, id);
                            repaintNow(ctx);
                        }
                    }
                    case "B" -> {
                        uiState = UiState.EDITING_BODY;
                        repaintNow(ctx);
                        ctx.send(inputPromptForState());
                    }
                    case "Q" -> {
                        uiState = UiState.VIEW;
                        repaintNow(ctx);
                        ctx.send(inputPromptForState());
                    }
                }
            }
            case EDITING_TITLE, EDITING_TAGS, EDITING_BODY -> {
                // No-op: keystrokes in these states are handled by the
                // client-side widget. Server only sees AppEvents from them.
            }
        }
        return Transition.None.INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Esc / cancel handling
    // -------------------------------------------------------------------------

    @Override
    public Transition onCancel(BbsContext ctx) {
        switch (uiState) {
            case VIEW -> {
                ctx.session().setCurrentDocumentId(null);
                popAndExit(ctx);
            }
            case EDIT_MENU -> {
                uiState = UiState.VIEW;
                repaintNow(ctx);
                ctx.send(inputPromptForState());
            }
            case EDITING_TITLE, EDITING_TAGS -> {
                // TextField Esc is routed as an AppEvent, which we
                // handle separately. This branch shouldn't be hit
                // because InputPrompt is "none" during field editing.
                // This branch shouldn't be hit because InputPrompt is
                // Defensive fallback to EDIT_MENU.
                uiState = UiState.EDIT_MENU;
                repaintNow(ctx);
                ctx.send(inputPromptForState());
            }
            case EDITING_BODY -> {
                // editor.cancel handles this; defensive fallback.
                uiState = UiState.EDIT_MENU;
                repaintNow(ctx);
                ctx.send(inputPromptForState());
            }
        }
        return Transition.None.INSTANCE;
    }

    // -------------------------------------------------------------------------
    // AppEvent routing
    // -------------------------------------------------------------------------

    @Override
    protected void onEvent(BbsContext ctx, AppEvent ev) {
        switch (ev) {
            case AppEvent.FieldCommit fc      -> handleFieldCommit(ctx, fc);
            case AppEvent.FieldCancel fc      -> handleFieldCancel(ctx, fc);
            case AppEvent.EditorCommit ec     -> handleEditorCommit(ctx, ec);
            case AppEvent.EditorCancel ec     -> handleEditorCancel(ctx, ec);
            case AppEvent.EditorSnapshot es   -> handleEditorSnapshot(ctx, es);
            case AppEvent.FocusMove fm        -> handleFocusMove(ctx, fm);
        }
    }

    private void handleFieldCommit(BbsContext ctx, AppEvent.FieldCommit fc) {
        Long id = ctx.session().currentDocumentId();
        if (id == null) return;
        DocumentRow doc = docs.findById(id).orElse(null);
        if (doc == null) return;
        if (!ctx.services().documents().canEdit(ctx.session(), doc)) {
            ctx.send(Frames.notify("notifications", "read-only", "warn", 2000));
            return;
        }
        switch (fc.widgetId()) {
            case "title" -> {
                handleTitleCommit(ctx, doc, id, fc.value());
                // Return to EDIT_MENU after field commit; re-emit prompt so
                // the InputController switches back to keystroke mode.
                uiState = UiState.EDIT_MENU;
                ctx.send(inputPromptForState());
            }
            case "tags"  -> {
                handleTagsCommit(ctx, doc, id, fc.value());
                uiState = UiState.EDIT_MENU;
                ctx.send(inputPromptForState());
            }
            case "vis"   -> handleVisCycle(ctx, doc, id);
            default      -> ctx.send(Frames.notify("notifications",
                    "(unknown field: " + fc.widgetId() + ")", "warn", 2000));
        }
    }

    private void handleFocusMove(BbsContext ctx, AppEvent.FocusMove fm) {
        // TextField Tab still sends focus.move. For v1, the EDIT_MENU
        // remains the navigation hub instead of cycling field focus.
        if (uiState == UiState.EDITING_TITLE || uiState == UiState.EDITING_TAGS) {
            uiState = UiState.EDIT_MENU;
            ctx.send(inputPromptForState());
        }
    }

    private void handleFieldCancel(BbsContext ctx, AppEvent.FieldCancel fc) {
        if (uiState == UiState.EDITING_TITLE || uiState == UiState.EDITING_TAGS) {
            uiState = UiState.EDIT_MENU;
            ctx.send(inputPromptForState());
        }
    }

    private void handleTitleCommit(BbsContext ctx, DocumentRow doc, long id, String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            ctx.send(Frames.notify("notifications",
                "title cannot be empty", "alert", 3000));
            return;
        }
        if (t.length() > 200) t = t.substring(0, 200);
        docs.updateTitle(id, t);
        ctx.audit("document.update.title",
            ctx.services().json().createObjectNode()
                .put("id", id).put("new_title_len", t.length()));
        ctx.publish(DocumentView.TOPIC);
        ctx.send(Frames.notify("notifications", "title updated", "info", 1500));
    }

    private void handleTagsCommit(BbsContext ctx, DocumentRow doc, long id, String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        java.util.List<String> tags = trimmed.isEmpty()
            ? java.util.List.of()
            : java.util.Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .map(s -> s.length() > 30 ? s.substring(0, 30) : s)
                .distinct()
                .limit(20)
                .toList();
        docs.updateTags(id, tags);
        ctx.audit("document.update.tags",
            ctx.services().json().createObjectNode()
                .put("id", id).put("count", tags.size()));
        ctx.publish(DocumentView.TOPIC);
        ctx.send(Frames.notify("notifications",
            tags.size() + " tag" + (tags.size() == 1 ? "" : "s") + " saved",
            "info", 1500));
    }

    private void handleVisCycle(BbsContext ctx, DocumentRow doc, long id) {
        io.aeyer.voidcore.documents.Visibility next = switch (doc.visibility()) {
            case PUBLIC  -> io.aeyer.voidcore.documents.Visibility.PRIVATE;
            case PRIVATE -> io.aeyer.voidcore.documents.Visibility.PUBLIC;
        };
        docs.updateVisibility(id, next);
        ctx.audit("document.update.visibility",
            ctx.services().json().createObjectNode()
                .put("id", id)
                .put("from", doc.visibility().wireValue())
                .put("to", next.wireValue()));
        ctx.publish(DocumentView.TOPIC);
        ctx.send(Frames.notify("notifications",
            "visibility → " + next.wireValue(), "info", 2000));
    }

    private void handleEditorCommit(BbsContext ctx, AppEvent.EditorCommit ec) {
        Long id = ctx.session().currentDocumentId();
        if (id == null) return;
        DocumentRow doc = docs.findById(id).orElse(null);
        if (doc == null) return;
        if (!ctx.services().documents().canEdit(ctx.session(), doc)) {
            ctx.send(Frames.notify("notifications", "read-only", "warn", 2000));
            return;
        }
        String body = ec.content() == null ? "" : ec.content();
        docs.updateBody(id, body);
        int byteCount = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        ctx.audit("document.update.body",
            ctx.services().json().createObjectNode()
                .put("id", id).put("body_bytes", byteCount));
        ctx.publish(DocumentView.TOPIC);
        // Wipe the snapshot — canonical is in DB now.
        String token = ctx.session().sessionToken();
        if (token != null) appStateRepo.wipe(token, currentAppKey());
        ctx.send(Frames.notify("notifications", "saved", "info", 1500));
        if ("save_quit".equals(ec.action())) {
            ctx.session().setCurrentDocumentId(null);
            popAndExit(ctx);
        } else {
            // save (not save_quit) — return to EDIT_MENU
            uiState = UiState.EDIT_MENU;
            ctx.send(inputPromptForState());
        }
    }

    private void handleEditorCancel(BbsContext ctx, AppEvent.EditorCancel ec) {
        String token = ctx.session().sessionToken();
        if (!ec.force()) {
            boolean dirty = token != null
                && appStateRepo.read(token, currentAppKey()).isPresent();
            if (dirty) {
                ctx.send(Frames.notify("notifications",
                    "unsaved changes — :q! to discard",
                    "warn", 4000));
                return;
            }
            // Clean buffer: Esc in EDITING_BODY returns to EDIT_MENU, not pop.
            if (uiState == UiState.EDITING_BODY) {
                uiState = UiState.EDIT_MENU;
                ctx.send(inputPromptForState());
                return;
            }
        }
        // force=true or not EDITING_BODY clean: wipe snapshot and exit.
        if (token != null) appStateRepo.wipe(token, currentAppKey());
        ctx.session().setCurrentDocumentId(null);
        popAndExit(ctx);
    }

    private void handleEditorSnapshot(BbsContext ctx, AppEvent.EditorSnapshot es) {
        Long id = ctx.session().currentDocumentId();
        if (id == null) return;
        String token = ctx.session().sessionToken();
        if (token == null) return;
        com.fasterxml.jackson.databind.node.ObjectNode payload =
            ctx.services().json().createObjectNode()
                .put("body_snapshot", es.content() == null ? "" : es.content())
                .put("snapshot_at", java.time.OffsetDateTime.now().toString());
        appStateRepo.write(token, currentAppKey(), payload);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean canCurrentUserEdit(BbsContext ctx, long id) {
        return docs.findById(id)
            .map(d -> ctx.services().documents().canEdit(ctx.session(), d))
            .orElse(false);
    }

    private Element blank() {
        return new Element.VStack(List.of(
            new Element.Header("DOCUMENT", "(no document selected)"),
            new Element.Spacer(1),
            new Element.Text("Press Q to return.", "dark_grey")), 0);
    }

}
