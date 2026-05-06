package io.aeyer.voidcore.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure value tests for {@link LoginSummary}. The {@link
 * LoginSummaryService} that builds these from repos is covered by
 * the integration test suite (it needs a live DB).
 */
class LoginSummaryTest {

    @Test
    void emptyIsEmpty() {
        LoginSummary s = LoginSummary.empty();
        assertThat(s.isEmpty()).isTrue();
        assertThat(s.total()).isZero();
    }

    @Test
    void anyNonZeroIsNotEmpty() {
        assertThat(new LoginSummary(1, 0, 0, 0, 0).isEmpty()).isFalse();
        assertThat(new LoginSummary(0, 1, 0, 0, 0).isEmpty()).isFalse();
        assertThat(new LoginSummary(0, 0, 1, 0, 0).isEmpty()).isFalse();
        assertThat(new LoginSummary(0, 0, 0, 1, 0).isEmpty()).isFalse();
        assertThat(new LoginSummary(0, 0, 0, 0, 1).isEmpty()).isFalse();
    }

    @Test
    void totalSumsAllCounts() {
        LoginSummary s = new LoginSummary(2, 3, 5, 7, 11);
        assertThat(s.total()).isEqualTo(2 + 3 + 5 + 7 + 11);
    }
}
