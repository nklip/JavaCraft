package my.javacraft.elastic.app.config;

import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.Endpoint;
import java.io.IOException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class RetryingElasticsearchTransportTest {

    @Test
    void testBackoffDelayMs() {
        Assertions.assertEquals(100L,
                RetryingElasticsearchTransport.backoffDelayMs(1, 100, 1_000));
        Assertions.assertEquals(200L,
                RetryingElasticsearchTransport.backoffDelayMs(2, 100, 1_000));
        Assertions.assertEquals(400L,
                RetryingElasticsearchTransport.backoffDelayMs(3, 100, 1_000));
        Assertions.assertEquals(1_000L,
                RetryingElasticsearchTransport.backoffDelayMs(10, 100, 1_000));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRetryingTransportShouldRetryAndSucceed() throws IOException {
        ElasticsearchTransport delegate = Mockito.mock(ElasticsearchTransport.class);
        Endpoint<Object, String, Object> endpoint = Mockito.mock(Endpoint.class);

        Mockito.when(delegate.performRequest(Mockito.any(), Mockito.eq(endpoint), Mockito.isNull()))
                .thenThrow(new IOException("temporary connection failure"))
                .thenReturn("ok");

        RetryingElasticsearchTransport transport =
                new RetryingElasticsearchTransport(delegate, 2, 1, 2);

        String result = transport.performRequest(new Object(), endpoint, null);

        Assertions.assertEquals("ok", result);
        Mockito.verify(delegate, Mockito.times(2))
                .performRequest(Mockito.any(), Mockito.eq(endpoint), Mockito.isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRetryingTransportShouldThrowWhenAllRetriesFail() throws IOException {
        ElasticsearchTransport delegate = Mockito.mock(ElasticsearchTransport.class);
        Endpoint<Object, String, Object> endpoint = Mockito.mock(Endpoint.class);

        Mockito.when(delegate.performRequest(Mockito.any(), Mockito.eq(endpoint), Mockito.isNull()))
                .thenThrow(new IOException("always failing"));

        RetryingElasticsearchTransport transport =
                new RetryingElasticsearchTransport(delegate, 3, 1, 4);

        IOException thrown = Assertions.assertThrows(IOException.class,
                () -> transport.performRequest(new Object(), endpoint, null));

        Assertions.assertEquals("always failing", thrown.getMessage());
        Mockito.verify(delegate, Mockito.times(3))
                .performRequest(Mockito.any(), Mockito.eq(endpoint), Mockito.isNull());
    }

}
