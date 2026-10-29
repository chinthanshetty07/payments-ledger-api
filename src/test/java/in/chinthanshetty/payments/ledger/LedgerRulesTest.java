package in.chinthanshetty.payments.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.chinthanshetty.payments.intents.Fees;
import in.chinthanshetty.payments.ledger.LedgerService.Entry;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LedgerRulesTest {

    @Test
    void balancedTransactionsPass() {
        assertThatCode(() -> LedgerService.assertBalanced(List.of(
                new Entry("a", "inr", -100), new Entry("b", "inr", 70), new Entry("c", "inr", 30)))).doesNotThrowAnyException();
    }

    @Test
    void unbalancedOrDegenerateTransactionsAreRefused() {
        assertThatThrownBy(() -> LedgerService.assertBalanced(List.of(new Entry("a", "inr", -100), new Entry("b", "inr", 99))))
                .hasMessageContaining("Unbalanced inr");
        assertThatThrownBy(() -> LedgerService.assertBalanced(List.of(new Entry("a", "inr", -100), new Entry("b", "usd", 100))))
                .hasMessageContaining("Unbalanced");
        assertThatThrownBy(() -> LedgerService.assertBalanced(List.of(new Entry("a", "inr", 0), new Entry("b", "inr", 0))))
                .hasMessageContaining("Zero-amount");
        assertThatThrownBy(() -> LedgerService.assertBalanced(List.of(new Entry("a", "inr", 1))))
                .hasMessageContaining("at least two");
    }

    @Test
    void feesRoundHalfUpAndNeverExceedTheAmount() {
        assertThat(Fees.forCapture(10_000)).isEqualTo(320);
        assertThat(Fees.forCapture(1_017)).isEqualTo(59); // 29.493 -> 29, + 30
        assertThat(Fees.forCapture(1_018)).isEqualTo(60); // 29.522 -> 30, + 30
        assertThat(Fees.forCapture(50)).isEqualTo(31);
        assertThat(Fees.forCapture(20)).isEqualTo(20);
    }
}
