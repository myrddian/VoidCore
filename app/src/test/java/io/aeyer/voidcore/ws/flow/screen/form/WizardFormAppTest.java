package io.aeyer.voidcore.ws.flow.screen.form;

import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WizardFormAppTest {

    static final class Acc {
        String subject;
        String body;
        java.util.List<String> options = new java.util.ArrayList<>();
        boolean submitted;
    }

    static final class FakeWizard extends WizardFormApp<Acc> {
        Acc latest;

        @Override public Phase phase() { return Phase.MENU; }
        @Override public String name() { return "fake-wiz"; }
        @Override protected String appKey(BbsContext ctx) { return "fake-wiz:1"; }
        @Override protected Acc newState(BbsContext ctx) { Acc a = new Acc(); latest = a; return a; }
        @Override
        protected List<WizardStep<Acc>> steps(BbsContext ctx) {
            return List.of(
                WizardStep.ofKind("Subject", FieldKind.SINGLE_LINE, (a, v) -> a.subject = v),
                WizardStep.ofKind("Body",    FieldKind.MULTI_LINE,  (a, v) -> a.body = v)
            );
        }
        @Override protected void onSubmit(BbsContext ctx, Acc state) { state.submitted = true; }
    }

    BbsContext ctx;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        ctx = mock(BbsContext.class);
        sent = new ArrayList<>();
        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));
    }

    @Test
    void enter_starts_at_step_0() {
        FakeWizard w = new FakeWizard();
        w.onEnter(ctx);
        assertThat(w.currentStepIndex()).isEqualTo(0);
    }

    @Test
    void field_commit_advances_to_next_step() {
        FakeWizard w = new FakeWizard();
        w.onEnter(ctx);
        w.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Hello"));
        assertThat(w.currentStepIndex()).isEqualTo(1);
        assertThat(w.latest.subject).isEqualTo("Hello");
    }

    @Test
    void editor_commit_at_final_step_calls_onSubmit_and_pops() {
        FakeWizard w = new FakeWizard();
        w.onEnter(ctx);
        w.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Subject"));
        w.onAppEvent(ctx, new AppEvent.EditorCommit("step", "Body content", "save"));
        assertThat(w.latest.submitted).isTrue();
        verify(ctx).pop();
    }

    @Test
    void cancel_at_step_0_pops_and_calls_onAbandon() {
        FakeWizard w = new FakeWizard();
        w.onEnter(ctx);
        w.onCancel(ctx);
        verify(ctx).pop();
    }

    @Test
    void variable_list_step_blank_submission_with_min_size_advances() {
        // Anonymous subclass with VARIABLE_LIST step requiring 2..9 and override of countListEntries.
        WizardFormApp<Acc> w = new WizardFormApp<>() {
            Acc s;
            @Override public Phase phase() { return Phase.MENU; }
            @Override public String name() { return "varlist"; }
            @Override protected String appKey(BbsContext ctx) { return "varlist:1"; }
            @Override protected Acc newState(BbsContext ctx) { s = new Acc(); return s; }
            @Override
            protected List<WizardStep<Acc>> steps(BbsContext ctx) {
                return List.of(new WizardStep<>(
                    "Options", FieldKind.VARIABLE_LIST,
                    (a, v) -> a.options.add(v),
                    sizeStr -> {
                        int n = Integer.parseInt(sizeStr);
                        if (n < 2) return Optional.of("need at least 2 options");
                        if (n > 9) return Optional.of("max 9 options");
                        return Optional.empty();
                    }
                ));
            }
            @Override protected int countListEntries() { return s == null ? 0 : s.options.size(); }
            @Override protected void onSubmit(BbsContext ctx, Acc state) { state.submitted = true; }
        };
        w.onEnter(ctx);
        w.onAppEvent(ctx, new AppEvent.FieldCommit("step", "first"));
        w.onAppEvent(ctx, new AppEvent.FieldCommit("step", "second"));
        w.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));   // blank ends loop
        verify(ctx).pop();
    }
}
