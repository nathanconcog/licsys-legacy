package au.licsys.notification;

import au.licsys.notification.activity.ActivityEvent;
import au.licsys.notification.notice.Notice;
import au.licsys.notification.store.NotificationStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryNotificationStore implements NotificationStore {
    final Map<String, ActivityEvent> activity = new ConcurrentHashMap<>();
    final Map<String, Notice> notices = new ConcurrentHashMap<>();

    @Override
    public boolean saveActivityIfAbsent(ActivityEvent e) {
        return activity.putIfAbsent(e.eventId(), e) == null;
    }

    @Override
    public List<ActivityEvent> findAllActivity() {
        return new ArrayList<>(activity.values());
    }

    @Override
    public boolean saveNoticeIfAbsent(String key, Notice n) {
        return notices.putIfAbsent(key, n) == null;
    }

    @Override
    public List<Notice> findAllNotices() {
        return new ArrayList<>(notices.values());
    }

    @Override
    public void deleteAll() {
        activity.clear();
        notices.clear();
    }
}
