package dev.nklip.javacraft.algs.leetcode.interview;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WordCounterTest {

    @Test
    public void testContainsNonAlphabeticCharacters() {
        Assertions.assertFalse(WordCounter.containsNonAlphabeticCharacters("A"));
        Assertions.assertFalse(WordCounter.containsNonAlphabeticCharacters("apple"));
        Assertions.assertFalse(WordCounter.containsNonAlphabeticCharacters("banana"));

        Assertions.assertTrue(WordCounter.containsNonAlphabeticCharacters("ba ba ba"));
        Assertions.assertTrue(WordCounter.containsNonAlphabeticCharacters("apple1"));
        Assertions.assertTrue(WordCounter.containsNonAlphabeticCharacters("222"));
        Assertions.assertTrue(WordCounter.containsNonAlphabeticCharacters("Romeo’s"));
        Assertions.assertTrue(WordCounter.containsNonAlphabeticCharacters("’"));
        Assertions.assertTrue(WordCounter.containsNonAlphabeticCharacters("2"));
    }

    @Test
    public void testCorrectSingleWord() {
        String word = "myWord";

        WordCounter wordCounter = new WordCounter();
        wordCounter.addWords(word);

        Assertions.assertEquals(1, wordCounter.counterByWord(word));
    }

    @Test
    public void testIncorrectSingleWord() {
        String word = "Romeo’s";

        WordCounter wordCounter = new WordCounter();
        wordCounter.addWords(word);

        Assertions.assertEquals(0, wordCounter.counterByWord(word));
    }

    @Test
    public void testSeveralUniqueWords() {
        String words = "myWordOne, myWordTwo";

        WordCounter wordCounter = new WordCounter();
        wordCounter.addWords(words);

        Assertions.assertEquals(1, wordCounter.counterByWord("myWordOne"));
        Assertions.assertEquals(1, wordCounter.counterByWord("myWordTwo"));
    }

    @Test
    public void testSeveralRepeatableWords() {
        String words = "apple, pear, apple, pear";

        WordCounter wordCounter = new WordCounter();
        wordCounter.addWords(words);

        Assertions.assertEquals(2, wordCounter.counterByWord("apple"));
        Assertions.assertEquals(2, wordCounter.counterByWord("pear"));
    }

    @Test
    public void testStrangeCombinations() {
        String words = "    !!! ; 9 8 \\";

        WordCounter wordCounter = new WordCounter();
        wordCounter.addWords(words);

        Assertions.assertEquals(0, wordCounter.counterByWord(" "));
        Assertions.assertEquals(0, wordCounter.counterByWord("!!!"));
        Assertions.assertEquals(0, wordCounter.counterByWord(";"));
        Assertions.assertEquals(0, wordCounter.counterByWord("9"));
        Assertions.assertEquals(0, wordCounter.counterByWord("8"));
        Assertions.assertEquals(0, wordCounter.counterByWord("\\"));
    }

    @Test
    public void testBigText() throws IOException {
        List<String> wordList = getWordsFromTxtFile("src/test/resources/BigText.txt");

        WordCounter wordCounter = new WordCounter();
        wordCounter.addWords(wordList.toString().substring(1, wordList.toString().length() - 1));

        Assertions.assertEquals(4, wordCounter.counterByWord("Amazon"));
        Assertions.assertEquals(34, wordCounter.counterByWord("Kinesis"));
        Assertions.assertEquals(14, wordCounter.counterByWord("Streams"));
    }

    @Test
    public void testRomeoAndJuliet() throws IOException {
        List<String> wordList = getWordsFromTxtFileByClasspath("Romeo_and_Juliet.txt");

        WordCounter wordCounter = new WordCounter();
        wordCounter.addWords(wordList.toString().substring(1, wordList.toString().length() - 1));

        Assertions.assertEquals(300, wordCounter.counterByWord("Romeo"));
        Assertions.assertEquals(180, wordCounter.counterByWord("Juliet"));
    }

    public List<String> getWordsFromTxtFileByClasspath(String classpath) throws IOException {
        try (InputStream inputStream = WordCounterTest.class.getClassLoader().getResourceAsStream(classpath)) {
            Assertions.assertNotNull(inputStream, "Missing classpath resource: " + classpath);
            return getLinesFromClasspathResource(inputStream);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private List<String> getWordsFromTxtFile(String pathToFile) throws IOException {
        Path path = Paths.get(pathToFile);
        return getLinesFromFile(path);
    }

    private List<String> getLinesFromFile(Path path) throws IOException {
        List<String> lineList = Files.readAllLines(path);

        List<String> wordList = new ArrayList<>();
        for (String line : lineList) {
            wordList.addAll(WordCounter.splitText(line));
        }
        return wordList;
    }

    private List<String> getLinesFromClasspathResource(InputStream inputStream) throws IOException {
        List<String> wordList = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                wordList.addAll(WordCounter.splitText(line));
            }
        }
        return wordList;
    }

}
