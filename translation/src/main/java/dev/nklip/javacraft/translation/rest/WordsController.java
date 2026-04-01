package dev.nklip.javacraft.translation.rest;

import dev.nklip.javacraft.translation.service.WordCounterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/v1/words")
public class WordsController {

    private final WordCounterService wordCounterService;

    @Autowired
    public WordsController(WordCounterService wordCounterService) {
        this.wordCounterService = wordCounterService;
    }

    @GetMapping(value = "/{word}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Integer> counterByWord(@PathVariable String word) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(wordCounterService.counterByWord(word));
    }

    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> addWords(
            @RequestBody String text) {
        wordCounterService.addWords(text);

        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body("Processed.");
    }

}
