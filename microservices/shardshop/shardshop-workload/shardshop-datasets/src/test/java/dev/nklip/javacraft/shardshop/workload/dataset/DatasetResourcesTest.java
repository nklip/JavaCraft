package dev.nklip.javacraft.shardshop.workload.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetResourcesTest {

    @Test
    void readsUtf8WithoutChangingWhitespaceAndClosesTheStream() throws IOException {
        InputStream input = spy(stream("first\tsecond\nÉlodie\t 東京 \n"));

        var rows = DatasetResources.read(input, "first\tsecond", 1);

        assertEquals(List.of(List.of("Élodie", " 東京 ")), rows);
        assertThrows(UnsupportedOperationException.class, rows::clear);
        assertThrows(UnsupportedOperationException.class, () -> rows.getFirst().clear());
        verify(input).close();
    }

    @Test
    void reportsMissingResourcesWithoutIncludingThePath() {
        assertEquals("Dataset resource is missing", assertThrows(IllegalStateException.class,
                () -> DatasetResources.load("/missing-private-resource.tsv", "first\tsecond", 1)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong\theader\nprivate\tdata\n", "first\tsecond\nprivate\n",
            "first\tsecond\nprivate\tdata\textra\n", "first\tsecond\nprivate\t\n",
            "first\tsecond\n \tdata\n", "first\tsecond\n"})
    void rejectsMalformedResourcesWithoutIncludingTheirContent(String content) {
        assertEquals("Dataset resource has an invalid format", assertThrows(IllegalStateException.class,
                () -> DatasetResources.read(stream(content), "first\tsecond", 1)).getMessage());
    }

    @Test
    void rejectsMoreRowsThanTheRetainedDefinitionAllows() {
        assertEquals("Dataset resource has an invalid format", assertThrows(IllegalStateException.class,
                () -> DatasetResources.read(stream("first\tsecond\none\ttwo\nthree\tfour\n"),
                        "first\tsecond", 1)).getMessage());
    }

    @Test
    void rejectsInvalidUtf8WithoutReplacementCharacters() {
        assertEquals("Dataset resource could not be read", assertThrows(IllegalStateException.class,
                () -> DatasetResources.read(new ByteArrayInputStream(new byte[] {(byte) 0xC3, (byte) 0x28}),
                        "first\tsecond", 1)).getMessage());
    }

    @Test
    void reportsReadFailuresWithoutExposingExceptionDetailsAndClosesTheStream() throws IOException {
        InputStream input = mock(InputStream.class);
        when(input.read(any(byte[].class), anyInt(), anyInt())).thenThrow(new IOException("private payload"));

        var failure = assertThrows(IllegalStateException.class,
                () -> DatasetResources.read(input, "first\tsecond", 1));

        assertEquals("Dataset resource could not be read", failure.getMessage());
        assertNull(failure.getCause());
        verify(input).close();
    }

    @Test
    void reportsCloseFailuresWithoutExposingExceptionDetails() throws IOException {
        InputStream input = spy(stream("first\tsecond\none\ttwo\n"));
        doThrow(new IOException("private payload")).when(input).close();

        var failure = assertThrows(IllegalStateException.class,
                () -> DatasetResources.read(input, "first\tsecond", 1));

        assertEquals("Dataset resource could not be read", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
