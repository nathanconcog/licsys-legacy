package au.licsys.notification.web;

import au.licsys.notification.activity.ActivityEvent;
import au.licsys.notification.activity.ActivityService;
import au.licsys.notification.activity.SseHub;
import au.licsys.notification.notice.Notice;
import au.licsys.notification.notice.NoticeService;
import au.licsys.notification.store.NotificationStore;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class NotificationController {

    private final NoticeService notices;
    private final ActivityService activity;
    private final SseHub hub;
    private final NotificationStore store;

    public NotificationController(NoticeService notices, ActivityService activity, SseHub hub,
            NotificationStore store) {
        this.notices = notices;
        this.activity = activity;
        this.hub = hub;
        this.store = store;
    }

    @GetMapping("/notices")
    public List<Notice> notices(@RequestParam(required = false) String driverId) {
        return notices.list(driverId);
    }

    @GetMapping("/activity")
    public List<ActivityEvent> activity(@RequestParam(defaultValue = "100") int limit) {
        return activity.recent(limit);
    }

    @GetMapping(path = "/activity/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }

    @PostMapping("/admin/reset")
    public Map<String, Object> reset() {
        store.deleteAll();
        return Map.of("reset", true);
    }
}
