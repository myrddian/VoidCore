package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.ReleaseFrontmatter;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.form.FieldKind;
import io.aeyer.voidcore.ws.flow.screen.form.FormField;
import io.aeyer.voidcore.ws.flow.screen.form.MenuAction;
import io.aeyer.voidcore.ws.flow.screen.form.MenuFormApp;
import io.aeyer.voidcore.ws.flow.view.DocumentView;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sysop · file edit. Replaces SysopFileEditMenuScreen + 8 single-field
 * edit screens (Title, Artist, Year, Label, Catalog, Genre, Url, Nfo)
 * with one MenuFormApp.
 *
 * <p>State machine inherited from MenuFormApp: VIEW shows the file's
 * metadata; [E] enters EDIT_MENU; letter selects a field; field commit
 * persists via the repository, audits, publishes DocumentView, and returns
 * to EDIT_MENU. [D]elete pushes SYSOP_FILE_DELETE_CONFIRM (which still
 * exists as a separate screen — out of scope here).
 */
@ScreenAppComponent
public class SysopReleaseEditScreen extends MenuFormApp<DocumentRow> {

    private final DocumentRepository docs;
    private final AclService acl;

    public SysopReleaseEditScreen(DocumentRepository docs, AclService acl) {
        this.docs = docs;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_RELEASE_EDIT; }
    @Override public String name() { return "sysop-release-edit"; }

    @Override
    protected String appKey(BbsContext ctx) {
        Long id = ctx.session().selectedSysopId();
        return id == null ? "sysop-release-edit:none" : "sysop-release-edit:" + id;
    }

    @Override
    protected DocumentRow loadState(BbsContext ctx) {
        Long id = ctx.session().selectedSysopId();
        if (id == null) return null;
        DocumentRow doc = docs.findReleaseByIdOrLegacyFileId(id).orElse(null);
        if (doc == null) return null;
        return ctx.isSysop() || acl.can(ctx.session(), AclResourceType.DOCUMENT, doc.id(), AclPermission.MANAGE)
                ? doc
                : null;
    }

    @Override
    protected String bannerLabel(BbsContext ctx, DocumentRow f) {
        return "SYSOP/FILE · " + filename(f) + "  [#" + f.id() + "]";
    }

    @Override
    protected Element headerElement(BbsContext ctx, DocumentRow f) {
        return new Element.Header("EDIT FILE: " + filename(f), null);
    }

    @Override
    protected void onQuit(BbsContext ctx) {
        ctx.session().setSelectedSysopId(null);
    }

    @Override
    protected List<FormField<DocumentRow>> fields(BbsContext ctx, DocumentRow f) {
        return List.of(
            new FormField<>("T", "Title",   DocumentRow::title,         FieldKind.SINGLE_LINE,
                editor((c, fr, v) -> commitTitle(c, fr, v))),
            new FormField<>("A", "Artist",  fr -> release(fr).artist(),        FieldKind.SINGLE_LINE,
                editor((c, fr, v) -> commitArtist(c, fr, v))),
            new FormField<>("Y", "Year",
                fr -> release(fr).year() == null ? null : release(fr).year().toString(),
                FieldKind.SINGLE_LINE,
                editor((c, fr, v) -> commitYear(c, fr, v))),
            new FormField<>("L", "Label",   fr -> release(fr).label(),         FieldKind.SINGLE_LINE,
                editor((c, fr, v) -> commitLabel(c, fr, v))),
            new FormField<>("C", "Catalog", fr -> release(fr).catalogNumber(), FieldKind.SINGLE_LINE,
                editor((c, fr, v) -> commitCatalog(c, fr, v))),
            new FormField<>("G", "Genre",   fr -> release(fr).genre(),         FieldKind.SINGLE_LINE,
                editor((c, fr, v) -> commitGenre(c, fr, v))),
            new FormField<>("U", "Url",     fr -> release(fr).externalUrl(),   FieldKind.SINGLE_LINE,
                editor((c, fr, v) -> commitUrl(c, fr, v))),
            new FormField<>("N", "Nfo",     DocumentRow::body,       FieldKind.MULTI_LINE,
                editor((c, fr, v) -> commitNfo(c, fr, v)))
        );
    }

    @Override
    protected List<MenuAction<DocumentRow>> menuActions(BbsContext ctx, DocumentRow f) {
        return List.of(new MenuAction<>("D", "delete this file", "bright_red",
            (c, fr) -> c.push(Phase.SYSOP_RELEASE_DELETE_CONFIRM)));
    }

    // ─── Commit handlers ─────────────────────────────────────────────
    // Each mirrors the audit shape and side-effects of the corresponding
    // legacy single-field screen exactly (verified against onLine() bodies).

    private String commitTitle(BbsContext ctx, DocumentRow f, String v) {
        if (v == null || v.isBlank()) return "title cannot be empty";
        docs.updateTitle(f.id(), v);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_release_title",
            ctx.services().json().createObjectNode().put("release_id", f.id()).put("title", v));
        return null;
    }

    private String commitArtist(BbsContext ctx, DocumentRow f, String v) {
        if (v == null) v = "";
        docs.updateFrontmatterText(f.id(), "artist", v.isBlank() ? null : v);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_release_artist",
            ctx.services().json().createObjectNode().put("release_id", f.id()).put("artist", v));
        return null;
    }

    private String commitYear(BbsContext ctx, DocumentRow f, String v) {
        Short year = parseYear(v);
        if (v != null && !v.isBlank() && year == null) {
            return "year must be 4 digits between 1900 and 2100";
        }
        docs.updateFrontmatterNumber(f.id(), "year", year);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_release_year",
            ctx.services().json().createObjectNode().put("release_id", f.id())
                .put("year", year == null ? null : year.intValue()));
        return null;
    }

    private String commitLabel(BbsContext ctx, DocumentRow f, String v) {
        if (v == null) v = "";
        docs.updateFrontmatterText(f.id(), "label", v.isBlank() ? null : v);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_release_label",
            ctx.services().json().createObjectNode().put("release_id", f.id()).put("label", v));
        return null;
    }

    private String commitCatalog(BbsContext ctx, DocumentRow f, String v) {
        if (v == null) v = "";
        docs.updateFrontmatterText(f.id(), "catalog_number", v.isBlank() ? null : v);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_release_catalog",
            ctx.services().json().createObjectNode().put("release_id", f.id()).put("catalog_number", v));
        return null;
    }

    private String commitGenre(BbsContext ctx, DocumentRow f, String v) {
        if (v == null) v = "";
        docs.updateFrontmatterText(f.id(), "genre", v.isBlank() ? null : v);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_release_genre",
            ctx.services().json().createObjectNode().put("release_id", f.id()).put("genre", v));
        return null;
    }

    private String commitUrl(BbsContext ctx, DocumentRow f, String v) {
        docs.updateFrontmatterText(f.id(), "external_url", v == null || v.isBlank() ? null : v);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_release_url",
            ctx.services().json().createObjectNode().put("release_id", f.id()).put("url", v));
        return null;
    }

    private String commitNfo(BbsContext ctx, DocumentRow f, String v) {
        if (v == null) v = "";
        docs.updateBody(f.id(), v);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_release_nfo",
            ctx.services().json().createObjectNode().put("release_id", f.id())
                .put("body_bytes", v.getBytes(StandardCharsets.UTF_8).length));
        return null;
    }

    private static Short parseYear(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 1900 || n > 2100) return null;
            return (short) n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Helper: wrap a (ctx, state, value) lambda as a FieldEditor. */
    @FunctionalInterface
    private interface CommitFn { String apply(BbsContext ctx, DocumentRow f, String v); }
    private static FormField.FieldEditor<DocumentRow> editor(CommitFn fn) {
        return new FormField.FieldEditor<>() {
            @Override public String onCommit(BbsContext c, DocumentRow f, String v) { return fn.apply(c, f, v); }
        };
    }

    private static ReleaseFrontmatter release(DocumentRow row) {
        return ReleaseFrontmatter.from(row.frontmatter());
    }

    private static String filename(DocumentRow row) {
        String filename = release(row).filename();
        return filename == null ? row.slug() : filename;
    }
}
