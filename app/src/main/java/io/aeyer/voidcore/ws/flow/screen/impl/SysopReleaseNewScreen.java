package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.screen.form.FieldKind;
import io.aeyer.voidcore.ws.flow.screen.form.WizardFormApp;
import io.aeyer.voidcore.ws.flow.screen.form.WizardStep;
import io.aeyer.voidcore.ws.flow.view.DocumentView;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@ScreenAppComponent
public class SysopReleaseNewScreen extends WizardFormApp<SysopReleaseNewScreen.Draft> {

    static final class Draft {
        String filename, title, artist, label, catalog, genre, url, nfo;
        Short year;
    }

    private final DocumentRepository docs;
    private final AclService acl;

    public SysopReleaseNewScreen(DocumentRepository docs, AclService acl) {
        this.docs = docs;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_RELEASE_NEW; }
    @Override public String name() { return "sysop-release-new"; }
    @Override protected String appKey(BbsContext ctx) { return "sysop-release-new"; }
    @Override protected Draft newState(BbsContext ctx) { return new Draft(); }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!canCreate(ctx)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have permission to create releases", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        return super.onEnter(ctx);
    }

    @Override
    protected List<WizardStep<Draft>> steps(BbsContext ctx) {
        return List.of(
            new WizardStep<>("Filename", FieldKind.SINGLE_LINE,
                (d, v) -> d.filename = v,
                v -> {
                    if (v == null || v.isEmpty() || v.contains(" ")
                        || v.contains("/") || v.length() > 32) {
                        return Optional.of("filename: 1-32 chars, no spaces or slashes");
                    }
                    return Optional.empty();
                }),
            new WizardStep<>("Title", FieldKind.SINGLE_LINE,
                (d, v) -> d.title = v,
                v -> v == null || v.isBlank()
                    ? Optional.of("title cannot be empty") : Optional.empty()),
            WizardStep.ofKind("Artist",  FieldKind.SINGLE_LINE, (d, v) -> d.artist  = blank(v)),
            new WizardStep<>("Year", FieldKind.SINGLE_LINE,
                (d, v) -> {
                    if (v == null || v.isBlank()) { d.year = null; return; }
                    try { d.year = Short.parseShort(v.trim()); }
                    catch (NumberFormatException e) { d.year = null; }
                },
                v -> {
                    if (v == null || v.isBlank()) return Optional.empty();
                    try { Short.parseShort(v.trim()); return Optional.empty(); }
                    catch (NumberFormatException e) { return Optional.of("year must be a number"); }
                }),
            WizardStep.ofKind("Label",   FieldKind.SINGLE_LINE, (d, v) -> d.label   = blank(v)),
            WizardStep.ofKind("Catalog", FieldKind.SINGLE_LINE, (d, v) -> d.catalog = blank(v)),
            WizardStep.ofKind("Genre",   FieldKind.SINGLE_LINE, (d, v) -> d.genre   = blank(v)),
            WizardStep.ofKind("Url",     FieldKind.SINGLE_LINE, (d, v) -> d.url     = blank(v)),
            WizardStep.ofKind("Nfo",     FieldKind.MULTI_LINE,  (d, v) -> d.nfo     = v == null ? "" : v)
        );
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v; }

    @Override
    protected void onSubmit(BbsContext ctx, Draft d) {
        Long uid = ctx.session().userId();
        if (uid == null) {
            throw new IllegalStateException("sysop release creation requires an authenticated user");
        }
        String nfo = d.nfo == null ? "" : d.nfo;
        long sizeBytes = nfo.getBytes(StandardCharsets.UTF_8).length;
        long fileId = docs.insertWithTypeSlug(
                uniqueSlugFor(d.filename, d.title),
                d.title,
                "release",
                nfo,
                buildFrontmatter(ctx, d, sizeBytes),
                List.of(),
                uid,
                io.aeyer.voidcore.documents.Visibility.PUBLIC,
                io.aeyer.voidcore.documents.Status.PUBLISHED);
        acl.grantRoleIfPresent(AclResourceType.DOCUMENT, fileId, AclPermission.MANAGE, "ADMIN");
        int nfoLines = nfo.isEmpty() ? 0 : nfo.split("\n", -1).length;
        ctx.audit("new_release",
            ctx.services().json().createObjectNode()
                .put("release_id", fileId)
                .put("filename", d.filename)
                .put("title", d.title)
                .put("artist", d.artist)
                .put("year", d.year == null ? null : d.year.intValue())
                .put("label", d.label)
                .put("catalog_number", d.catalog)
                .put("genre", d.genre)
                .put("external_url", d.url)
                .put("nfo_lines", nfoLines));
        ctx.publish(DocumentView.TOPIC);
        ctx.send(Frames.notify("notifications",
            "release added: " + d.filename, "info", 3000));
    }

    private ObjectNode buildFrontmatter(BbsContext ctx, Draft d, long sizeBytes) {
        ObjectNode fm = ctx.services().json().createObjectNode();
        fm.put("filename", d.filename);
        fm.put("size_bytes", sizeBytes);
        fm.put("download_count", 0);
        if (d.url != null) fm.put("external_url", d.url);
        if (d.year != null) fm.put("year", d.year);
        if (d.artist != null) fm.put("artist", d.artist);
        if (d.label != null) fm.put("label", d.label);
        if (d.catalog != null) fm.put("catalog_number", d.catalog);
        if (d.genre != null) fm.put("genre", d.genre);
        return fm;
    }

    private String uniqueSlugFor(String filename, String title) {
        String base = slugify(baseName(filename));
        if (base.isBlank()) base = slugify(title);
        if (base.isBlank()) base = "release";
        return docs.findBySlug(base).isPresent()
                ? base + "-" + System.currentTimeMillis()
                : base;
    }

    private static String baseName(String filename) {
        if (filename == null) return "";
        return filename.replaceFirst("\\.[^.]+$", "");
    }

    private static String slugify(String raw) {
        if (raw == null) return "";
        String slug = raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug;
    }

    private boolean canCreate(BbsContext ctx) {
        return ctx.isSysop() || ctx.services().documents().list().stream()
                .filter(doc -> "release".equals(doc.typeSlug()))
                .anyMatch(doc -> acl.can(ctx.session(), AclResourceType.DOCUMENT, doc.id(), AclPermission.MANAGE));
    }
}
