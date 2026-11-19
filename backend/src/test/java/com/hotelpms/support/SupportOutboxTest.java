package com.hotelpms.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupportOutboxTest {
    private record Write(String text, SendHandler result) {}

    private final Session session = mock(Session.class);
    private final RemoteEndpoint.Async remote = mock(RemoteEndpoint.Async.class);
    private final Runnable disconnected = mock(Runnable.class);
    private final LinkedBlockingQueue<Write> writes = new LinkedBlockingQueue<>();

    private SupportSockets.Outbox outbox() {
        when(session.isOpen()).thenReturn(true);
        when(session.getAsyncRemote()).thenReturn(remote);
        doAnswer(call -> {
            writes.add(new Write(call.getArgument(0), call.getArgument(1)));
            return null;
        }).when(remote).sendText(anyString(), any(SendHandler.class));
        return new SupportSockets.Outbox(session, disconnected);
    }

    @Test
    void keepsOneWriteInFlightAndDrainsFramesInEnqueueOrder() throws Exception {
        var outbox = outbox();
        outbox.add("changed");
        outbox.add("ack");
        outbox.add("presence");
        verify(remote, times(1)).sendText(anyString(), any(SendHandler.class));
        for (String expected : java.util.List.of("changed", "ack", "presence")) {
            var write = writes.poll(3, TimeUnit.SECONDS);
            assertThat(write).isNotNull();
            assertThat(write.text()).isEqualTo(expected);
            assertThat(writes).isEmpty();
            write.result().onResult(new SendResult());
        }
        verifyNoInteractions(disconnected);
    }

    @Test
    void failedWriteDiscardsPendingAndUnregistersEvenIfTransportCloseFails() throws Exception {
        var outbox = outbox();
        doThrow(new IOException("already disconnected")).when(session).close(any(CloseReason.class));
        outbox.add("in flight");
        outbox.add("waiting");
        writes.remove().result().onResult(new SendResult(new IOException("failed write")));
        outbox.add("subsequent");
        verify(disconnected).run();
        verifyClose(CloseReason.CloseCodes.UNEXPECTED_CONDITION);
        verify(remote, times(1)).sendText(anyString(), any(SendHandler.class));
    }

    @Test
    void slowSocketIsClosedOnceWhenBoundedPendingQueueFills() throws Exception {
        var outbox = outbox();
        outbox.add("in flight");
        for (int i = 0; i < 32; i++) outbox.add("queued-" + i);
        verifyNoInteractions(disconnected);
        outbox.add("over capacity");
        outbox.add("after close");
        verify(disconnected).run();
        verifyClose(CloseReason.CloseCodes.TRY_AGAIN_LATER);
        verify(remote, times(1)).sendText(anyString(), any(SendHandler.class));
    }

    private void verifyClose(CloseReason.CloseCode expected) throws IOException {
        ArgumentCaptor<CloseReason> reason = ArgumentCaptor.forClass(CloseReason.class);
        verify(session).close(reason.capture());
        assertThat(reason.getValue().getCloseCode().getCode()).isEqualTo(expected.getCode());
    }
}
