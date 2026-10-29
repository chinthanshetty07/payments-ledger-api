package in.chinthanshetty.payments.webhooks;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the dispatcher on a timer. Disabled in tests, which call the dispatcher directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "webhooks.scheduler-enabled", havingValue = "true")
public class WebhookScheduler {
    private final WebhookDispatcher dispatcher;

    public WebhookScheduler(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${webhooks.poll-ms}")
    void tick() {
        dispatcher.dispatchDue();
    }
}
