package au.licsys.notification.sqs;

import au.licsys.notification.activity.ActivityService;
import au.licsys.notification.config.LicsysProperties;
import au.licsys.notification.notice.NoticeService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/** Long-polls notification-inbox and activity-inbox on dedicated threads. */
@Component
public class SqsConsumers {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumers.class);

    @FunctionalInterface
    interface Handler {
        void handle(String body) throws Exception;
    }

    private final SqsClient sqs;
    private final LicsysProperties props;
    private final NoticeService notices;
    private final ActivityService activity;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;

    public SqsConsumers(SqsClient sqs, LicsysProperties props, NoticeService notices, ActivityService activity) {
        this.sqs = sqs;
        this.props = props;
        this.notices = notices;
        this.activity = activity;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.consumersEnabled()) {
            log.info("SQS consumers disabled");
            return;
        }
        startLoop(props.notificationQueue(), notices::handle);
        startLoop(props.activityQueue(), activity::ingest);
    }

    private void startLoop(String queueName, Handler handler) {
        Thread t = new Thread(() -> loop(queueName, handler), "sqs-" + queueName);
        t.setDaemon(true);
        t.start();
        threads.add(t);
    }

    private void loop(String queueName, Handler handler) {
        String url = null;
        while (running) {
            try {
                if (url == null) {
                    url = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
                    log.info("Consuming {}", url);
                }
                final String queueUrl = url;
                List<Message> msgs = sqs.receiveMessage(r -> r.queueUrl(queueUrl)
                        .maxNumberOfMessages(10).waitTimeSeconds(props.sqsWaitSeconds())).messages();
                for (Message m : msgs) {
                    try {
                        handler.handle(m.body());
                        sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
                    } catch (Exception e) {
                        // Left on the queue; SQS redelivers after the visibility timeout.
                        log.error("Failed to handle message {} from {}: {}", m.messageId(), queueName, e.toString());
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn("Polling {} failed ({}), retrying in 2s", queueName, e.toString());
                url = null;
                sleep(2000);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
    }
}
