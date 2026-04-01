package dev.nklip.javacraft.translation.rest;

import dev.nklip.javacraft.translation.service.WordCounterService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

public class WordsControllerTest {

    @Test
    public void testCounterByWord() {
        WordCounterService wordCounterService = Mockito.mock(WordCounterService.class);

        Mockito.when(wordCounterService.counterByWord(Mockito.eq("test1 test2"))).thenReturn(2);

        WordsController wordsController = new WordsController(wordCounterService);

        ResponseEntity<Integer> response = wordsController.counterByWord("test1 test2");

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(2, response.getBody().intValue());
    }

    @Test
    public void testPutNewElectricMetric() {
        WordCounterService wordCounterService = Mockito.mock(WordCounterService.class);

        Mockito.when(wordCounterService.counterByWord(Mockito.eq("test1 test2"))).thenReturn(2);

        WordsController wordsController = new WordsController(wordCounterService);

        ResponseEntity<String> response = wordsController.addWords("test1 test2");

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals("Processed.", response.getBody());
    }
}
