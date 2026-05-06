package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.auth.UserRepository;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for a {@link ScreenApp} (or other stateful {@link Screen})
 * that needs a fresh instance per session push. Combines:
 * <ul>
 *   <li>{@link Component} — Spring discovers it during component scan;</li>
 *   <li>{@link Scope SCOPE_PROTOTYPE} — Spring mints a fresh instance every
 *       time the navigator router asks for one (i.e. per push), so two
 *       sessions in the same screen don't share mutable state;</li>
 *   <li>{@link ConditionalOnBean ConditionalOnBean(UserRepository.class)} —
 *       same DB-less smoke-test gating as {@link ScreenComponent}.</li>
 * </ul>
 *
 * <p><strong>When to use:</strong> any {@code Screen} implementation that
 * carries mutable per-session state in instance fields. That covers:
 * <ul>
 *   <li>Direct {@code ScreenApp} subclasses (e.g. {@code DocumentScreen})
 *       which carry {@code uiState}, restored-toast tracking, etc.</li>
 *   <li>{@code MenuFormApp} / {@code WizardFormApp} subclasses which carry
 *       wizard step index, edit-mode field letter, draft accumulators.</li>
 * </ul>
 *
 * <p><strong>When NOT to use:</strong> stateless {@code Screen}
 * implementations (list views, menus, read-only screens) — those use
 * {@link ScreenComponent} and stay singletons. Marking a stateless screen
 * as prototype is harmless but wasteful.
 *
 * <p>The router holds a {@code Map<Phase, ObjectProvider<? extends Screen>>}
 * and resolves through it on every push. {@code ObjectProvider.getObject()}
 * returns the cached singleton for {@code @ScreenComponent} beans, or a
 * fresh instance for {@code @ScreenAppComponent} beans — Spring's standard
 * scope semantics, no per-Phase branching needed in the router.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@ConditionalOnBean(UserRepository.class)
public @interface ScreenAppComponent {
}
