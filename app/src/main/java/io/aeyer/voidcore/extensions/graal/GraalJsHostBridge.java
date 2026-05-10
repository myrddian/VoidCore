package io.aeyer.voidcore.extensions.graal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.voidcore.extensions.ExtensionScreenRegistration;
import io.aeyer.voidcore.extensions.host.ExtensionDocumentView;
import io.aeyer.voidcore.extensions.host.ExtensionHostContext;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unified GraalJS-visible bridge for one hosted custom screen callback.
 *
 * <p>This owns the proxied API surface so scripts see one coherent object
 * rather than a pile of adapter-local proxy builders.
 */
final class GraalJsHostBridge {

    private final ExtensionHostContext hostCtx;
    private final ObjectMapper json;

    GraalJsHostBridge(ExtensionHostContext hostCtx, ObjectMapper json) {
        this.hostCtx = hostCtx;
        this.json = json;
    }

    ProxyObject asGuestObject() {
        ProxyObject ui = proxyUi();
        ProxyObject navigation = proxyNavigation();
        ProxyObject documents = proxyDocuments();
        ProxyObject extensionsData = proxyData();
        ProxyObject effects = proxyEffects();
        ProxyObject elements = proxyElements();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("registration", proxyRegistration(hostCtx.registration()));
        root.put("session", proxySession());
        root.put("ui", ui);
        root.put("navigation", navigation);
        root.put("documents", documents);
        root.put("extensionsData", extensionsData);
        root.put("effects", effects);
        root.put("elements", elements);
        root.put("el", elements);

        root.put("banner", (ProxyExecutable) args -> {
            hostCtx.ui().banner(stringArg(args, 0));
            return null;
        });
        root.put("mainText", (ProxyExecutable) args -> {
            hostCtx.ui().mainText(stringListArg(args, 0));
            return null;
        });
        root.put("mainTree", (ProxyExecutable) args -> {
            hostCtx.ui().mainTree(elementFromGuest(valueArg(args, 0)), optionalStringArg(args, 1));
            return null;
        });
        root.put("render", (ProxyExecutable) args -> {
            hostCtx.ui().mainTree(elementFromGuest(valueArg(args, 0)), optionalStringArg(args, 1));
            return null;
        });
        root.put("promptNone", (ProxyExecutable) args -> {
            hostCtx.ui().promptNone();
            return null;
        });
        root.put("promptKeystroke", (ProxyExecutable) args -> {
            hostCtx.ui().promptKeystroke(stringArg(args, 0), stringArg(args, 1));
            return null;
        });
        root.put("promptLine", (ProxyExecutable) args -> {
            hostCtx.ui().promptLine(stringArg(args, 0), integerArg(args, 1), optionalStringArg(args, 2));
            return null;
        });
        root.put("promptPassword", (ProxyExecutable) args -> {
            hostCtx.ui().promptPassword(stringArg(args, 0), integerArg(args, 1));
            return null;
        });
        root.put("notifyMain", (ProxyExecutable) args -> {
            hostCtx.ui().notifyMain(stringArg(args, 0), stringArg(args, 1), longArg(args, 2, 2000L));
            return null;
        });
        root.put("pop", (ProxyExecutable) args -> {
            hostCtx.navigation().pop();
            return null;
        });
        root.put("push", (ProxyExecutable) args -> {
            hostCtx.navigation().pushCustom(stringArg(args, 0));
            return null;
        });
        root.put("pushCustom", (ProxyExecutable) args -> {
            hostCtx.navigation().pushCustom(stringArg(args, 0));
            return null;
        });
        root.put("pushCore", (ProxyExecutable) args -> {
            hostCtx.navigation().pushCore(parsePhase(stringArg(args, 0)));
            return null;
        });
        root.put("docById", (ProxyExecutable) args -> hostCtx.documents().byId(longArg(args, 0, 0L))
                .map(this::guestDocument)
                .orElse(null));
        root.put("docBySlug", (ProxyExecutable) args -> hostCtx.documents().bySlug(stringArg(args, 0))
                .map(this::guestDocument)
                .orElse(null));
        root.put("docsByType", (ProxyExecutable) args -> guestDocumentList(
                hostCtx.documents().listByType(stringArg(args, 0), integerOrDefault(args, 1, 20))));
        root.put("getGlobal", (ProxyExecutable) args -> hostCtx.data().getGlobal(stringArg(args, 0))
                .map(this::guestFromJson)
                .orElse(null));
        root.put("getForCurrentUser", (ProxyExecutable) args -> hostCtx.data().getForCurrentUser(stringArg(args, 0))
                .map(this::guestFromJson)
                .orElse(null));
        root.put("putGlobal", (ProxyExecutable) args -> {
            hostCtx.data().putGlobal(stringArg(args, 0), guestValueToJson(valueArg(args, 1)));
            return null;
        });
        root.put("putForCurrentUser", (ProxyExecutable) args -> {
            hostCtx.data().putForCurrentUser(stringArg(args, 0), guestValueToJson(valueArg(args, 1)));
            return null;
        });
        root.put("deleteGlobal", (ProxyExecutable) args -> {
            hostCtx.data().deleteGlobal(stringArg(args, 0));
            return null;
        });
        root.put("deleteForCurrentUser", (ProxyExecutable) args -> {
            hostCtx.data().deleteForCurrentUser(stringArg(args, 0));
            return null;
        });
        root.put("globalKeys", (ProxyExecutable) args -> guestArray(
                new ArrayList<>(hostCtx.data().globalKeys(optionalStringArg(args, 0), integerOrDefault(args, 1, 50)))));
        root.put("currentUserKeys", (ProxyExecutable) args -> guestArray(
                new ArrayList<>(hostCtx.data().currentUserKeys(optionalStringArg(args, 0), integerOrDefault(args, 1, 50)))));
        root.put("openUrl", (ProxyExecutable) args -> {
            hostCtx.effects().openUrl(stringArg(args, 0));
            return null;
        });
        root.put("setTheme", (ProxyExecutable) args -> {
            hostCtx.effects().setTheme(stringArg(args, 0));
            return null;
        });
        root.put("copyClipboard", (ProxyExecutable) args -> {
            hostCtx.effects().copyClipboard(stringArg(args, 0));
            return null;
        });
        root.put("json", (ProxyExecutable) args -> guestFromJson(guestValueToJson(valueArg(args, 0))));
        return ProxyObject.fromMap(root);
    }

    private ProxyObject proxyUi() {
        ProxyObject elements = proxyElements();
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("banner", (ProxyExecutable) args -> {
            hostCtx.ui().banner(stringArg(args, 0));
            return null;
        });
        methods.put("mainText", (ProxyExecutable) args -> {
            hostCtx.ui().mainText(stringListArg(args, 0));
            return null;
        });
        methods.put("mainTree", (ProxyExecutable) args -> {
            hostCtx.ui().mainTree(elementFromGuest(valueArg(args, 0)), optionalStringArg(args, 1));
            return null;
        });
        methods.put("render", (ProxyExecutable) args -> {
            hostCtx.ui().mainTree(elementFromGuest(valueArg(args, 0)), optionalStringArg(args, 1));
            return null;
        });
        methods.put("promptNone", (ProxyExecutable) args -> {
            hostCtx.ui().promptNone();
            return null;
        });
        methods.put("promptKeystroke", (ProxyExecutable) args -> {
            hostCtx.ui().promptKeystroke(stringArg(args, 0), stringArg(args, 1));
            return null;
        });
        methods.put("promptLine", (ProxyExecutable) args -> {
            hostCtx.ui().promptLine(stringArg(args, 0), integerArg(args, 1), optionalStringArg(args, 2));
            return null;
        });
        methods.put("promptPassword", (ProxyExecutable) args -> {
            hostCtx.ui().promptPassword(stringArg(args, 0), integerArg(args, 1));
            return null;
        });
        methods.put("notifyMain", (ProxyExecutable) args -> {
            hostCtx.ui().notifyMain(stringArg(args, 0), stringArg(args, 1), longArg(args, 2, 2000L));
            return null;
        });
        methods.put("elements", elements);
        methods.put("el", elements);
        methods.put("json", (ProxyExecutable) args -> guestFromJson(guestValueToJson(valueArg(args, 0))));
        return ProxyObject.fromMap(methods);
    }

    private ProxyObject proxyNavigation() {
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("pop", (ProxyExecutable) args -> {
            hostCtx.navigation().pop();
            return null;
        });
        methods.put("push", (ProxyExecutable) args -> {
            hostCtx.navigation().pushCustom(stringArg(args, 0));
            return null;
        });
        methods.put("pushCustom", (ProxyExecutable) args -> {
            hostCtx.navigation().pushCustom(stringArg(args, 0));
            return null;
        });
        methods.put("pushCore", (ProxyExecutable) args -> {
            hostCtx.navigation().pushCore(parsePhase(stringArg(args, 0)));
            return null;
        });
        return ProxyObject.fromMap(methods);
    }

    private ProxyObject proxyDocuments() {
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("byId", (ProxyExecutable) args -> hostCtx.documents().byId(longArg(args, 0, 0L))
                .map(this::guestDocument)
                .orElse(null));
        methods.put("bySlug", (ProxyExecutable) args -> hostCtx.documents().bySlug(stringArg(args, 0))
                .map(this::guestDocument)
                .orElse(null));
        methods.put("listByType", (ProxyExecutable) args -> guestDocumentList(
                hostCtx.documents().listByType(stringArg(args, 0), integerOrDefault(args, 1, 20))));
        return ProxyObject.fromMap(methods);
    }

    private ProxyObject proxyData() {
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("getGlobal", (ProxyExecutable) args -> hostCtx.data().getGlobal(stringArg(args, 0))
                .map(this::guestFromJson)
                .orElse(null));
        methods.put("getForCurrentUser", (ProxyExecutable) args -> hostCtx.data().getForCurrentUser(stringArg(args, 0))
                .map(this::guestFromJson)
                .orElse(null));
        methods.put("putGlobal", (ProxyExecutable) args -> {
            hostCtx.data().putGlobal(stringArg(args, 0), guestValueToJson(valueArg(args, 1)));
            return null;
        });
        methods.put("putForCurrentUser", (ProxyExecutable) args -> {
            hostCtx.data().putForCurrentUser(stringArg(args, 0), guestValueToJson(valueArg(args, 1)));
            return null;
        });
        methods.put("deleteGlobal", (ProxyExecutable) args -> {
            hostCtx.data().deleteGlobal(stringArg(args, 0));
            return null;
        });
        methods.put("deleteForCurrentUser", (ProxyExecutable) args -> {
            hostCtx.data().deleteForCurrentUser(stringArg(args, 0));
            return null;
        });
        methods.put("globalKeys", (ProxyExecutable) args -> guestArray(
                new ArrayList<>(hostCtx.data().globalKeys(optionalStringArg(args, 0), integerOrDefault(args, 1, 50)))));
        methods.put("currentUserKeys", (ProxyExecutable) args -> guestArray(
                new ArrayList<>(hostCtx.data().currentUserKeys(optionalStringArg(args, 0), integerOrDefault(args, 1, 50)))));
        return ProxyObject.fromMap(methods);
    }

    private ProxyObject proxyEffects() {
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("openUrl", (ProxyExecutable) args -> {
            hostCtx.effects().openUrl(stringArg(args, 0));
            return null;
        });
        methods.put("setTheme", (ProxyExecutable) args -> {
            hostCtx.effects().setTheme(stringArg(args, 0));
            return null;
        });
        methods.put("copyClipboard", (ProxyExecutable) args -> {
            hostCtx.effects().copyClipboard(stringArg(args, 0));
            return null;
        });
        return ProxyObject.fromMap(methods);
    }

    private ProxyObject proxyElements() {
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("vstack", (ProxyExecutable) args -> elementSpec(Map.of(
                "kind", "vstack",
                "children", guestArray(childSpecs(valueArg(args, 0))),
                "gap", integerOrDefault(args, 1, 0))));
        methods.put("text", (ProxyExecutable) args -> elementSpec(mapOf(
                "kind", "text",
                "content", stringArg(args, 0),
                "style", optionalStringArg(args, 1) == null ? "default" : optionalStringArg(args, 1))));
        methods.put("para", (ProxyExecutable) args -> elementSpec(mapOf(
                "kind", "para",
                "content", stringArg(args, 0),
                "style", optionalStringArg(args, 1) == null ? "default" : optionalStringArg(args, 1))));
        methods.put("rule", (ProxyExecutable) args -> elementSpec(Map.of("kind", "rule")));
        methods.put("spacer", (ProxyExecutable) args -> elementSpec(Map.of(
                "kind", "spacer",
                "rows", integerOrDefault(args, 0, 1))));
        methods.put("padded", (ProxyExecutable) args -> elementSpec(Map.of(
                "kind", "padded",
                "child", valueArg(args, 0),
                "leftCols", integerOrDefault(args, 1, 0))));
        methods.put("styled", (ProxyExecutable) args -> elementSpec(Map.of(
                "kind", "styled",
                "child", valueArg(args, 0),
                "style", stringArg(args, 1))));
        methods.put("header", (ProxyExecutable) args -> elementSpec(mapOf(
                "kind", "header",
                "title", stringArg(args, 0),
                "rightAnnotation", optionalStringArg(args, 1))));
        methods.put("statusLine", (ProxyExecutable) args -> elementSpec(mapOf(
                "kind", "statusLine",
                "mode", stringArg(args, 0),
                "left", stringArg(args, 1),
                "right", stringArg(args, 2))));
        methods.put("keyEntry", (ProxyExecutable) args -> elementSpec(Map.of(
                "key", stringArg(args, 0),
                "label", stringArg(args, 1))));
        methods.put("keyMenu", (ProxyExecutable) args -> elementSpec(Map.of(
                "kind", "keyMenu",
                "entries", guestArray(childSpecs(valueArg(args, 0))))));
        methods.put("textField", (ProxyExecutable) args -> elementSpec(mapOf(
                "kind", "textField",
                "id", stringArg(args, 0),
                "label", stringArg(args, 1),
                "value", optionalStringArg(args, 2) == null ? "" : optionalStringArg(args, 2),
                "maxLength", integerArg(args, 3),
                "readOnly", booleanArg(args, 4, false))));
        methods.put("editor", (ProxyExecutable) args -> elementSpec(mapOf(
                "kind", "editor",
                "id", stringArg(args, 0),
                "content", optionalStringArg(args, 1) == null ? "" : optionalStringArg(args, 1),
                "mode", optionalStringArg(args, 2) == null ? "plain" : optionalStringArg(args, 2),
                "syntaxMode", optionalStringArg(args, 3),
                "readOnly", booleanArg(args, 4, false))));
        methods.put("form", (ProxyExecutable) args -> elementSpec(mapOf(
                "kind", "form",
                "id", stringArg(args, 0),
                "children", guestArray(childSpecs(valueArg(args, 1))),
                "focusedChildId", optionalStringArg(args, 2))));
        return ProxyObject.fromMap(methods);
    }

    private ProxyObject proxyRegistration(ExtensionScreenRegistration registration) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("extensionSlug", registration.extensionSlug());
        values.put("extensionLabel", registration.extensionLabel());
        values.put("extensionVersion", registration.extensionVersion());
        values.put("screenName", registration.screenName());
        values.put("screenLabel", registration.screenLabel());
        values.put("entrypoint", registration.entrypoint());
        return ProxyObject.fromMap(values);
    }

    private ProxyObject proxySession() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("authenticated", hostCtx.session().authenticated());
        values.put("userId", hostCtx.session().userId());
        values.put("handle", hostCtx.session().handle());
        values.put("sysop", hostCtx.session().sysop());
        values.put("currentRoute", hostCtx.session().currentRoute());
        return ProxyObject.fromMap(values);
    }

    private ProxyObject guestDocument(ExtensionDocumentView doc) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", doc.id());
        values.put("slug", doc.slug());
        values.put("title", doc.title());
        values.put("typeSlug", doc.typeSlug());
        values.put("typeVersion", doc.typeVersion());
        values.put("rev", doc.rev());
        values.put("body", doc.body());
        values.put("frontmatter", guestFromJson(doc.frontmatter()));
        List<Object> tags = new ArrayList<>();
        if (doc.tags() != null) {
            tags.addAll(doc.tags());
        }
        values.put("tags", guestArray(tags));
        values.put("authorId", doc.authorId());
        values.put("visibility", doc.visibility());
        values.put("status", doc.status());
        values.put("createdAt", doc.createdAt() == null ? null : doc.createdAt().toString());
        values.put("updatedAt", doc.updatedAt() == null ? null : doc.updatedAt().toString());
        return ProxyObject.fromMap(values);
    }

    private ProxyArray guestDocumentList(List<ExtensionDocumentView> docs) {
        List<Object> values = new ArrayList<>();
        for (ExtensionDocumentView doc : docs) {
            values.add(guestDocument(doc));
        }
        return guestArray(values);
    }

    private Object guestFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isFloatingPointNumber()) return node.doubleValue();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode child : node) {
                values.add(guestFromJson(child));
            }
            return guestArray(values);
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), guestFromJson(entry.getValue())));
            return ProxyObject.fromMap(values);
        }
        return node.toString();
    }

    private JsonNode guestValueToJson(Value value) {
        if (value == null || value.isNull()) {
            return NullNode.instance;
        }
        if (value.isBoolean()) {
            return json.getNodeFactory().booleanNode(value.asBoolean());
        }
        if (value.isNumber()) {
            if (value.fitsInInt()) return json.getNodeFactory().numberNode(value.asInt());
            if (value.fitsInLong()) return json.getNodeFactory().numberNode(value.asLong());
            if (value.fitsInDouble()) return json.getNodeFactory().numberNode(value.asDouble());
        }
        if (value.isString()) {
            return json.getNodeFactory().textNode(value.asString());
        }
        if (value.hasArrayElements()) {
            ArrayNode array = json.createArrayNode();
            for (long i = 0; i < value.getArraySize(); i++) {
                array.add(guestValueToJson(value.getArrayElement(i)));
            }
            return array;
        }
        if (value.hasMembers()) {
            ObjectNode object = json.createObjectNode();
            for (String key : value.getMemberKeys()) {
                Value member = value.getMember(key);
                if (member != null && member.canExecute()) continue;
                object.set(key, guestValueToJson(member));
            }
            return object;
        }
        throw new IllegalArgumentException("unsupported JS value for JSON conversion");
    }

    private Element elementFromGuest(Value value) {
        String kind = memberString(value, "kind");
        return switch (kind) {
            case "vstack" -> new Element.VStack(children(value, "children"), integerMember(value, "gap", 0));
            case "text" -> new Element.Text(memberString(value, "content"), memberString(value, "style", "default"));
            case "para" -> new Element.Para(memberString(value, "content"), memberString(value, "style", "default"));
            case "rule" -> new Element.Rule();
            case "spacer" -> new Element.Spacer(integerMember(value, "rows", 1));
            case "padded" -> new Element.Padded(elementFromGuest(memberValue(value, "child")), integerMember(value, "leftCols", 0));
            case "styled" -> new Element.Styled(elementFromGuest(memberValue(value, "child")), memberString(value, "style"));
            case "header" -> new Element.Header(memberString(value, "title"), optionalMemberString(value, "rightAnnotation"));
            case "statusLine" -> new Element.StatusLine(
                    memberString(value, "mode"),
                    memberString(value, "left"),
                    memberString(value, "right"));
            case "keyMenu" -> new Element.KeyMenu(keyEntries(value));
            case "textField" -> new Element.TextField(
                    memberString(value, "id"),
                    memberString(value, "label"),
                    memberString(value, "value", ""),
                    optionalMemberInteger(value, "maxLength"),
                    booleanMember(value, "readOnly", false));
            case "editor" -> new Element.Editor(
                    memberString(value, "id"),
                    memberString(value, "content", ""),
                    memberString(value, "mode", "plain"),
                    optionalMemberString(value, "syntaxMode"),
                    booleanMember(value, "readOnly", false));
            case "form" -> new Element.Form(
                    memberString(value, "id"),
                    children(value, "children"),
                    optionalMemberString(value, "focusedChildId"));
            default -> throw new IllegalArgumentException("unsupported element kind: " + kind);
        };
    }

    private List<Element> children(Value value, String key) {
        Value children = memberValue(value, key);
        List<Element> out = new ArrayList<>();
        for (long i = 0; i < children.getArraySize(); i++) {
            out.add(elementFromGuest(children.getArrayElement(i)));
        }
        return out;
    }

    private List<Element.KeyMenu.KeyEntry> keyEntries(Value value) {
        Value entries = memberValue(value, "entries");
        List<Element.KeyMenu.KeyEntry> out = new ArrayList<>();
        for (long i = 0; i < entries.getArraySize(); i++) {
            Value entry = entries.getArrayElement(i);
            out.add(new Element.KeyMenu.KeyEntry(
                    memberString(entry, "key"),
                    memberString(entry, "label")));
        }
        return out;
    }

    private List<Object> childSpecs(Value value) {
        List<Object> out = new ArrayList<>();
        for (long i = 0; i < value.getArraySize(); i++) {
            out.add(value.getArrayElement(i));
        }
        return out;
    }

    private ProxyArray guestArray(List<?> values) {
        return ProxyArray.fromList(new ArrayList<>(values));
    }

    private ProxyObject elementSpec(Map<String, Object> values) {
        return ProxyObject.fromMap(values);
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            out.put((String) entries[i], entries[i + 1]);
        }
        return out;
    }

    private static String stringArg(Value[] args, int index) {
        return valueArg(args, index).asString();
    }

    private static String optionalStringArg(Value[] args, int index) {
        if (index >= args.length || args[index] == null || args[index].isNull()) return null;
        return args[index].asString();
    }

    private static Integer integerArg(Value[] args, int index) {
        if (index >= args.length || args[index] == null || args[index].isNull()) return null;
        return args[index].asInt();
    }

    private static int integerOrDefault(Value[] args, int index, int fallback) {
        Integer value = integerArg(args, index);
        return value == null ? fallback : value;
    }

    private static long longArg(Value[] args, int index, long fallback) {
        if (index >= args.length || args[index] == null || args[index].isNull()) return fallback;
        return args[index].asLong();
    }

    private static boolean booleanArg(Value[] args, int index, boolean fallback) {
        if (index >= args.length || args[index] == null || args[index].isNull()) return fallback;
        return args[index].asBoolean();
    }

    private static List<String> stringListArg(Value[] args, int index) {
        Value value = valueArg(args, index);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < value.getArraySize(); i++) {
            out.add(value.getArrayElement(i).asString());
        }
        return out;
    }

    private static Value valueArg(Value[] args, int index) {
        if (index >= args.length || args[index] == null) {
            throw new IllegalArgumentException("missing argument at index " + index);
        }
        return args[index];
    }

    private static Phase parsePhase(String token) {
        String normalized = token.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Phase.valueOf(normalized);
    }

    private static Value memberValue(Value value, String name) {
        Value member = value.getMember(name);
        if (member == null || member.isNull()) {
            throw new IllegalArgumentException("missing member: " + name);
        }
        return member;
    }

    private static String memberString(Value value, String name) {
        return memberValue(value, name).asString();
    }

    private static String memberString(Value value, String name, String fallback) {
        Value member = value.getMember(name);
        return member == null || member.isNull() ? fallback : member.asString();
    }

    private static String optionalMemberString(Value value, String name) {
        Value member = value.getMember(name);
        return member == null || member.isNull() ? null : member.asString();
    }

    private static Integer optionalMemberInteger(Value value, String name) {
        Value member = value.getMember(name);
        return member == null || member.isNull() ? null : member.asInt();
    }

    private static int integerMember(Value value, String name, int fallback) {
        Value member = value.getMember(name);
        return member == null || member.isNull() ? fallback : member.asInt();
    }

    private static boolean booleanMember(Value value, String name, boolean fallback) {
        Value member = value.getMember(name);
        return member == null || member.isNull() ? fallback : member.asBoolean();
    }
}
