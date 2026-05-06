package io.aeyer.voidcore.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sysop bootstrap config per SPEC §3 ("Bootstrap nuance"). Both fields are
 * blank by default; if either is empty at first boot the bootstrap is a
 * no-op (so dev shells without env vars don't crash).
 */
@ConfigurationProperties(prefix = "voidcore.sysop")
public record SysopProperties(String handle, String initialPassword) {

    public boolean configured() {
        return handle != null && !handle.isBlank()
                && initialPassword != null && !initialPassword.isBlank();
    }
}
