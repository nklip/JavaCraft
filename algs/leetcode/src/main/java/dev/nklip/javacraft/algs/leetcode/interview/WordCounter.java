package dev.nklip.javacraft.algs.leetcode.interview;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WordCounter {

    // vs - Vocabulary Stats
    private final ConcurrentMap<String, Integer> vs = new ConcurrentHashMap<>();

    public void addWords(String text) {
        List<String> wordList = splitText(text);

        for (String word : wordList) {
            if (containsNonAlphabeticCharacters(word)) {
                continue;
            }
            word = word.toLowerCase().trim();

            Integer counter = vs.getOrDefault(word, 0) + 1;

            vs.put(word, counter);
        }
    }

    public Integer counterByWord(String word) {
        return Optional.ofNullable(word)
                .map(String::toLowerCase)
                .map(String::trim)
                .map(w -> vs.getOrDefault(w, 0))
                .orElse(0);
    }

    public static boolean containsNonAlphabeticCharacters(String word) {
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
