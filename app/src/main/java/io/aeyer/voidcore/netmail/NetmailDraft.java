package io.aeyer.voidcore.netmail;

/**
 * Per-session in-flight compose form for VoidMail. Carried on
 * {@code VoidCoreSession.netmailDraft} so the composer can resume an
 * abandoned draft or pre-fill reply / forward flows.
 */
public record NetmailDraft(String toHandle, String subject, String body) {
    public static NetmailDraft empty() { return new NetmailDraft(null, null, null); }
    public NetmailDraft withTo(String to) { return new NetmailDraft(to, subject, body); }
    public NetmailDraft withSubject(String s) { return new NetmailDraft(toHandle, s, body); }
    public NetmailDraft withBody(String b) { return new NetmailDraft(toHandle, subject, b); }

    public boolean isEmpty() {
        return blank(toHandle) && blank(subject) && blank(body);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
