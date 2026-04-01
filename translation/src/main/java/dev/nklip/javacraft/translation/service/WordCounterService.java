package dev.nklip.javacraft.translation.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class WordCounterService {

    // vs - Vocabulary Stats
    private final ConcurrentMap<String, Integer> vs = new ConcurrentHashMap<>();

    private final TranslateService translateService;

    public WordCounterService(TranslateService translateService) {
        this.translateService = translateService;
    }

    public void addWords(String text) {
        List<String> wordList = splitText(text);

        for (String word : wordList) {
            if (containsNonAlphabeticCharacters(word)) {
                continue;
            }
            word = word.toLowerCase().trim();

            String enWord = translateService.translate2English(word);

            Integer counter = vs.getOrDefault(enWord, 0) + 1;

            vs.put(enWord, counter);
        }
    }

    public Integer counterByWord(String word) {
        return Optional.ofNullable(word)
                .map(String::toLowerCase)
                .map(String::trim)
                .map(translateService::translate2English)
                .map(w -> vs.getOrDefault(w, 0))
                .orElse(0);
    }

    static boolean containsNonAlphabeticCharacters(String word) {
        Pattern p = Pattern.compile("^[a-zA-Z]{1,}$");
        return !p.matcher(word).find();
    }

    static List<String> splitText(String text) {
        return Arrays.stream(text
                .replaceAll("[.,!:?;]", "")
                .split(" ")
        ).collect(Collectors.toList());
    }
}
