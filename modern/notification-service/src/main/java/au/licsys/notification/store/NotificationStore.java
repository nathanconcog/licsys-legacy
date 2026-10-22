package au.licsys.notification.store;

import au.licsys.notification.activity.ActivityEvent;
import au.licsys.notification.notice.Notice;
import java.util.List;

/** Persistence for the notification-activity table (activity events and notices). */
public interface NotificationStore {

    /** Returns false if an event with the same eventId is already stored. */
    boolean saveActivityIfAbsent(ActivityEvent event);

    List<ActivityEvent> findAllActivity();

    /** Atomically stores the notice unless one already exists for {@code dedupeKey}. */
    boolean saveNoticeIfAbsent(String dedupeKey, Notice notice);

    List<Notice> findAllNotices();

    void deleteAll();
}
