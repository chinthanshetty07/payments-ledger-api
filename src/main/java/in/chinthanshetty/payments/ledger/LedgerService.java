package in.chinthanshetty.payments.ledger;

import in.chinthanshetty.payments.Ids;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Double-entry ledger. Balances are never stored as a number that can drift: they are the sum of
 * immutable entries, and every posted transaction must sum to zero in each currency.
 */
@Service
public class LedgerService {
    public static final String CARD_CLEARING = "network:card_clearing";
    public static final String FEES = "platform:fees";

    public record Entry(String account, String currency, long amount) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public LedgerService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static String merchantBalance(String merchantId) {
        return "merchant:" + merchantId + ":balance";
    }

    /** Must run inside the caller's transaction, so money moves only if the state change commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String post(String type, String reference, List<Entry> entries) {
        assertBalanced(entries);
        String txnId = Ids.next("txn");
        jdbc.sql("INSERT INTO ledger_transactions (id, type, reference, created_at) VALUES (?, ?, ?, ?)")
                .params(txnId, type, reference, Timestamp.from(clock.instant()))
                .update();
        for (Entry e : entries) {
            jdbc.sql("INSERT INTO ledger_entries (transaction_id, account, currency, amount) VALUES (?, ?, ?, ?)")
                    .params(txnId, e.account(), e.currency(), e.amount())
                    .update();
        }
        return txnId;
    }

    static void assertBalanced(List<Entry> entries) {
        if (entries.size() < 2) {
            throw new IllegalArgumentException("A ledger transaction needs at least two entries");
        }
        Map<String, Long> sums = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.amount() == 0) {
                throw new IllegalArgumentException("Zero-amount entry for " + e.account());
            }
            sums.merge(e.currency(), e.amount(), Math::addExact);
        }
        sums.forEach((currency, sum) -> {
            if (sum != 0) {
                throw new IllegalArgumentException("Unbalanced " + currency + " transaction: entries sum to " + sum);
            }
        });
    }

    public Map<String, Long> balances(String account) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("SELECT currency, SUM(amount) AS total FROM ledger_entries WHERE account = ? GROUP BY currency ORDER BY currency")
                .param(account)
                .query((rs, n) -> out.put(rs.getString("currency"), rs.getLong("total")))
                .list();
        return out;
    }

    /** Independent check: every transaction balances and the whole ledger nets to zero per currency. */
    public Map<String, Object> reconcile() {
        List<String> unbalanced = jdbc.sql("""
                SELECT transaction_id FROM ledger_entries
                GROUP BY transaction_id, currency HAVING SUM(amount) <> 0""")
                .query(String.class).list();
        List<Map<String, Object>> totals = new ArrayList<>();
        jdbc.sql("SELECT currency, SUM(amount) AS total, COUNT(*) AS entries FROM ledger_entries GROUP BY currency ORDER BY currency")
                .query((rs, n) -> totals.add(Map.of("currency", rs.getString("currency"),
                        "net", rs.getLong("total"), "entries", rs.getLong("entries"))))
                .list();
        long transactions = jdbc.sql("SELECT COUNT(*) FROM ledger_transactions").query(Long.class).single();
        boolean ok = unbalanced.isEmpty() && totals.stream().allMatch(t -> (long) t.get("net") == 0L);
        return Map.of("ok", ok, "transactions_checked", transactions, "unbalanced_transactions", unbalanced,
                "currency_totals", totals);
    }
}
