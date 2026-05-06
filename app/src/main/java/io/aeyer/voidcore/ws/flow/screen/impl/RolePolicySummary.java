package io.aeyer.voidcore.ws.flow.screen.impl;

final class RolePolicySummary {

    private RolePolicySummary() {}

    static String compactLine(String roleName) {
        return switch (roleName) {
            case "ADMIN" -> "ADMIN: broad delegated control across rooms, one-liners, polls, docs, and VoidMail access";
            case "MODERATOR" -> "MODERATOR: community control across rooms, one-liners, polls, and announcements";
            default -> "";
        };
    }

    static java.util.List<String> detailLines(String roleName) {
        return switch (roleName) {
            case "ADMIN" -> java.util.List.of(
                    "  default policy:",
                    "  - manage chat rooms",
                    "  - manage the one-liners wall",
                    "  - manage polls",
                    "  - manage message boards",
                    "  - manage documents, including files and announcements",
                    "  - manage VoidMail access policy",
                    "");
            case "MODERATOR" -> java.util.List.of(
                    "  default policy:",
                    "  - manage chat rooms",
                    "  - manage the one-liners wall",
                    "  - manage polls",
                    "  - manage message boards",
                    "  - view/edit announcements",
                    "");
            default -> java.util.List.of();
        };
    }
}
