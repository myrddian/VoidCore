package io.aeyer.voidcore.monitoring;

import io.micrometer.core.instrument.Counter;

/**
 * Pre-resolved Micrometer counters for the call-out paths. Call sites
 * inject this and call the typed helpers — keeps the meter-registry
 * lookup out of hot paths and means the meter names live in exactly
 * one place ({@link MetricsConfig}).
 */
public class VoidCoreMeters {

    private final Counter loginSuccess;
    private final Counter loginFailure;
    private final Counter rateLimitedLogin;
    private final Counter rateLimitedRegistration;
    private final Counter rateLimitedPost;
    private final Counter sysopActions;

    public VoidCoreMeters(Counter loginSuccess, Counter loginFailure,
                       Counter rateLimitedLogin, Counter rateLimitedRegistration,
                       Counter rateLimitedPost, Counter sysopActions) {
        this.loginSuccess = loginSuccess;
        this.loginFailure = loginFailure;
        this.rateLimitedLogin = rateLimitedLogin;
        this.rateLimitedRegistration = rateLimitedRegistration;
        this.rateLimitedPost = rateLimitedPost;
        this.sysopActions = sysopActions;
    }

    public void recordLogin(boolean success)       { (success ? loginSuccess : loginFailure).increment(); }
    public void recordLoginRateLimited()           { rateLimitedLogin.increment(); }
    public void recordRegistrationRateLimited()    { rateLimitedRegistration.increment(); }
    public void recordPostRateLimited()            { rateLimitedPost.increment(); }
    public void recordSysopAction()                { sysopActions.increment(); }
}
