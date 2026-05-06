package io.aeyer.voidcore.auth;

import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.PostRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.monitoring.VoidCoreMeters;
import io.aeyer.voidcore.netmail.NetmailRepository;
import io.aeyer.voidcore.oneliners.OnelinerRepository;
import io.aeyer.voidcore.presence.LastCallerRepository;
import io.aeyer.voidcore.presence.PresenceService;
import io.aeyer.voidcore.sysop.SysopActionRepository;
import io.aeyer.voidcore.ws.SessionRegistry;
import io.aeyer.voidcore.ws.flow.ScreenRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Clock;

/**
 * Wires DB-dependent beans only when {@code spring.datasource.url} is
 * configured. Test contexts that deliberately exclude
 * {@code DataSourceAutoConfiguration} clear that property so this whole
 * config drops out cleanly.
 *
 * <p>Repos receive a {@link DSLContext} per ADR-005a — Spring Boot's jOOQ
 * starter autoconfigures a {@code DSLContext} bound to the application
 * {@code DataSource}. {@link SysopBootstrap} keeps its
 * {@link NamedParameterJdbcTemplate} dependency for the one-shot bootstrap
 * because it pre-dates jOOQ codegen and isn't on a hot path; porting it is
 * a follow-up.
 */
@Configuration
@ConditionalOnExpression("'${spring.datasource.url:}'.length() > 0")
public class AuthConfig {

    @Bean
    public SessionRepository sessionRepository(DSLContext dsl, ObjectMapper json) {
        return new SessionRepository(dsl, json);
    }

    @Bean
    public SessionService sessionService(SessionRepository repo, SessionProperties props, Clock clock) {
        return new SessionService(repo, props, clock);
    }

    @Bean
    public LoginAttemptRepository loginAttemptRepository(DSLContext dsl) {
        return new LoginAttemptRepository(dsl);
    }

    @Bean
    public SysopBootstrap sysopBootstrap(NamedParameterJdbcTemplate jdbc,
                                         PasswordHasher hasher,
                                         SysopProperties props) {
        return new SysopBootstrap(jdbc, hasher, props);
    }

    @Bean
    public UserRepository userRepository(DSLContext dsl) {
        return new UserRepository(dsl);
    }

    @Bean
    public RoleRepository roleRepository(DSLContext dsl) {
        return new RoleRepository(dsl);
    }

    @Bean
    public CounterRepository counterRepository(DSLContext dsl) {
        return new CounterRepository(dsl);
    }

    @Bean
    public io.aeyer.voidcore.documents.SchemaRepository schemaRepository(
            DSLContext dsl, ObjectMapper json) {
        return new io.aeyer.voidcore.documents.SchemaRepository(dsl, json);
    }

    @Bean
    public io.aeyer.voidcore.documents.FrontmatterValidator frontmatterValidator() {
        return new io.aeyer.voidcore.documents.FrontmatterValidator();
    }

    @Bean
    public io.aeyer.voidcore.documents.DocumentRepository documentRepository(
            DSLContext dsl,
            ObjectMapper json,
            io.aeyer.voidcore.documents.SchemaRepository schemas,
            io.aeyer.voidcore.documents.FrontmatterValidator validator) {
        return new io.aeyer.voidcore.documents.DocumentRepository(dsl, json, schemas, validator);
    }

    @Bean
    public io.aeyer.voidcore.instance.InstanceFeatureRepository instanceFeatureRepository(DSLContext dsl) {
        return new io.aeyer.voidcore.instance.InstanceFeatureRepository(dsl);
    }

    @Bean
    public io.aeyer.voidcore.instance.InstanceFeatureService instanceFeatureService(
            io.aeyer.voidcore.instance.InstanceFeatureRepository repo,
            io.aeyer.voidcore.instance.InstanceFeatureProperties props) {
        return new io.aeyer.voidcore.instance.InstanceFeatureService(repo, props);
    }

    @Bean
    public LastCallerRepository lastCallerRepository(DSLContext dsl) {
        return new LastCallerRepository(dsl);
    }

    @Bean
    public OnelinerRepository onelinerRepository(DSLContext dsl) {
        return new OnelinerRepository(dsl);
    }

    @Bean
    public ChatRepository chatRepository(DSLContext dsl) {
        return new ChatRepository(dsl);
    }

    @Bean
    public io.aeyer.voidcore.acl.AclRepository aclRepository(DSLContext dsl) {
        return new io.aeyer.voidcore.acl.AclRepository(dsl);
    }

    @Bean
    public io.aeyer.voidcore.acl.AclService aclService(io.aeyer.voidcore.acl.AclRepository repo,
                                                   RoleRepository roles) {
        return new io.aeyer.voidcore.acl.AclService(repo, roles);
    }

    @Bean
    public NetmailRepository netmailRepository(DSLContext dsl) {
        return new NetmailRepository(dsl);
    }

    @Bean
    public SysopActionRepository sysopActionRepository(DSLContext dsl, ObjectMapper json, VoidCoreMeters meters) {
        return new SysopActionRepository(dsl, json, meters);
    }

    @Bean
    public MessageBaseRepository messageBaseRepository(DSLContext dsl) {
        return new MessageBaseRepository(dsl);
    }

    @Bean
    public ThreadRepository threadRepository(DSLContext dsl) {
        return new ThreadRepository(dsl);
    }

    @Bean
    public PostRepository postRepository(DSLContext dsl, ThreadRepository threads) {
        return new PostRepository(dsl, threads);
    }

    // === v1 punch-list bundle (#86 / #87 / #88 / #89 / #91 / #93) ===========
    // Repos declared as explicit @Bean methods (not @Component-scanned)
    // matching the project convention for DB-backed repos. The
    // @Component + @ConditionalOnBean(DSLContext.class) pattern these
    // originally used was fragile — Spring's evaluation order didn't
    // always satisfy the conditional in time, breaking startup of
    // dependents like AchievementsScreen.

    @Bean
    public io.aeyer.voidcore.atmosphere.FortuneRepository fortuneRepository(DSLContext dsl) {
        return new io.aeyer.voidcore.atmosphere.FortuneRepository(dsl);
    }

    @Bean
    public io.aeyer.voidcore.social.AchievementRepository achievementRepository(DSLContext dsl) {
        return new io.aeyer.voidcore.social.AchievementRepository(dsl);
    }

    @Bean
    public io.aeyer.voidcore.social.ActivityEventRepository activityEventRepository(
            DSLContext dsl, ObjectMapper json) {
        return new io.aeyer.voidcore.social.ActivityEventRepository(dsl, json);
    }

    @Bean
    public io.aeyer.voidcore.social.ReactionRepository reactionRepository(DSLContext dsl) {
        return new io.aeyer.voidcore.social.ReactionRepository(dsl);
    }

    @Bean
    public io.aeyer.voidcore.social.WatchListRepository watchListRepository(DSLContext dsl) {
        return new io.aeyer.voidcore.social.WatchListRepository(dsl);
    }

    @Bean
    public io.aeyer.voidcore.social.AchievementAwardingService achievementAwardingService(
            io.aeyer.voidcore.social.AchievementRepository repo) {
        return new io.aeyer.voidcore.social.AchievementAwardingService(repo);
    }

    @Bean
    public io.aeyer.voidcore.social.SocialEventService socialEventService(
            io.aeyer.voidcore.social.ActivityEventRepository events,
            io.aeyer.voidcore.social.AchievementAwardingService awarder,
            UserRepository users) {
        return new io.aeyer.voidcore.social.SocialEventService(events, awarder, users);
    }

    @Bean
    public io.aeyer.voidcore.polls.PollRepository pollRepository(DSLContext dsl) {
        return new io.aeyer.voidcore.polls.PollRepository(dsl);
    }

    @Bean
    public io.aeyer.voidcore.doors.DoorStateRepository doorStateRepository(DSLContext dsl, ObjectMapper json) {
        return new io.aeyer.voidcore.doors.DoorStateRepository(dsl, json);
    }

    @Bean
    public io.aeyer.voidcore.ws.flow.ui.AppStateRepository appStateRepository(
            DSLContext dsl, ObjectMapper json) {
        return new io.aeyer.voidcore.ws.flow.ui.AppStateRepository(dsl, json);
    }

    @Bean
    public AuthService authService(UserRepository users,
                                   SessionService sessions,
                                   PasswordHasher hasher,
                                   RateLimiter rateLimiter,
                                   LoginAttemptRepository attempts,
                                   CounterRepository counters,
                                   LastCallerRepository lastCallers,
                                   VoidCoreMeters meters) {
        return new AuthService(users, sessions, hasher, rateLimiter, attempts,
                counters, lastCallers, meters);
    }

    @Bean
    public ScreenRouter screenRouter(AuthService auth,
                                     SessionService sessions,
                                     UserRepository users,
                                     NetmailRepository netmail,
                                     MessageBaseRepository messageBases,
                                     ThreadRepository threads,
                                     PresenceService presence,
                                     ObjectMapper json,
                                     SessionRegistry wsSessions,
                                     io.aeyer.voidcore.ws.flow.screen.BbsServices bbsServices,
                                     io.aeyer.voidcore.ws.flow.bus.MessageBus bus,
                                     io.aeyer.voidcore.ws.flow.screen.NavigationState navState,
                                     org.springframework.context.ApplicationContext appCtx,
                                     java.util.List<io.aeyer.voidcore.ws.flow.screen.Screen> screens) {
        // ApplicationContext is needed so the router can resolve a
        // per-Phase ObjectProvider that respects each screen's Spring
        // scope — singletons keep returning the same instance, while
        // prototype-scoped @ScreenAppComponent beans mint a fresh
        // instance on every navigator push (the fix for the multi-
        // session state-leak bug).
        return new ScreenRouter(auth, sessions, users,
                netmail, messageBases, threads, presence, json, wsSessions,
                bbsServices, bus, navState, appCtx, screens);
    }
}
