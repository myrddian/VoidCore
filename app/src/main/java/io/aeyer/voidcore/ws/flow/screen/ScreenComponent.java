package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.auth.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for a {@link Screen} implementation. Combines
 * {@link Component} with {@link ConditionalOnBean ConditionalOnBean(UserRepository.class)}
 * so the bean only registers when the datasource-backed wiring is
 * present.
 *
 * <p>Why: every {@code Screen} impl ultimately needs at least one
 * DB-backed repository (most directly, all transitively through
 * {@code BbsServices}). Those repos are conditional on
 * {@code spring.datasource.url} via {@code AuthConfig}'s class-level
 * {@code @ConditionalOnExpression}. Without this matching condition,
 * the DB-less smoke test ({@code VoidCoreApplicationTests}) tries to
 * instantiate every Screen and fails because their repo dependencies
 * don't exist in that profile.
 *
 * <p>Apply to every {@code @Component}-style Screen impl in
 * {@code io.aeyer.voidcore.ws.flow.screen.impl}. Replaces a bare
 * {@code @Component}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@ConditionalOnBean(UserRepository.class)
public @interface ScreenComponent {
}
