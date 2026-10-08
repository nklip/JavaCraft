package dev.nklip.javacraft.shardshop.workload.seeder.catalog;

import org.mockito.ArgumentCaptor;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Reads the complete body that a JDK request publisher sends. */
public final class RequestBodies {
    private RequestBodies() {
        // Static test helper. No instances are necessary.
    }

    public static String of(HttpRequest request) {
        Flow.Subscriber<ByteBuffer> subscriber = mock();
        doAnswer(invocation -> {
            Flow.Subscription subscription = invocation.getArgument(0);
            subscription.request(Long.MAX_VALUE);
            return null;
        }).when(subscriber).onSubscribe(any());
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        ArgumentCaptor<ByteBuffer> bytes = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(subscriber).onNext(bytes.capture());
        verify(subscriber).onComplete();
        return StandardCharsets.UTF_8.decode(bytes.getValue()).toString();
    }
}
