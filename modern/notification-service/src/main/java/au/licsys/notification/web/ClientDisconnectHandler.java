package au.licsys.notification.web;

import java.io.IOException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/** An SSE client going away mid-write is normal; don't log it as a servlet error. */
@RestControllerAdvice
public class ClientDisconnectHandler {

    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    public void clientGone() {
        // nothing to send: the connection is closed
    }
}
