import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * @author Lipatov Nikita
 * <p>
 * Complexity notation:
 * {@code I} is the total input size,
 * {@code N} is the number of distinct nouns,
 * {@code V} is the number of synsets,
 * {@code E} is the number of hypernym edges, and
 * {@code L} is the total length of the noun arguments to a method.
 */
public class WordNet {
    private Digraph digraph;
    private final SAP sap;
    private final Map<Integer, String> idToSynset = new HashMap<>();
    private final Map<String, Set<Integer>> nouns = new HashMap<>(); // The number of nouns in synsets.txt is 119,188.
    private final Set<String> nounView = Collections.unmodifiableSet(nouns.keySet());

    /**
     * Required time complexity: {@code O(I log I)} or better.
     * Actual time complexity: {@code O(I + V + E)} expected; the hash-table worst case is
     * {@code O(I log N + V + E)}.
     */
    public WordNet(String synsets, String hypernyms) {
        if (synsets == null || hypernyms == null) {
            throw new NullPointerException("Input file names must not be null");
        }
        parseSynsets(synsets);
        parseHypernyms(hypernyms);

        checkCycles();
        checkRoot();

        this.sap = new SAP(digraph);
    }

    private void parseSynsets(String synsets) {
        In synsetIn = ResourceFiles.open(WordNet.class, synsets);
        String oneLine;
        while (synsetIn.hasNextLine()) {
            oneLine = synsetIn.readLine();
            StringTokenizer oneSynsetTokenizer = new StringTokenizer(oneLine, ",");
            int synsetId = 0;
            if (oneSynsetTokenizer.hasMoreTokens()) {
                synsetId = Integer.parseInt(oneSynsetTokenizer.nextToken());
            }

            if (oneSynsetTokenizer.hasMoreTokens()) {
                String synset = oneSynsetTokenizer.nextToken();
                idToSynset.put(synsetId, synset);
                StringTokenizer synonymTokenizer = new StringTokenizer(synset, " ");
                while (synonymTokenizer.hasMoreTokens()) {
                    String synonym = synonymTokenizer.nextToken();
                    Set<Integer> ids = nouns.get(synonym);
                    if (null == ids) {
                        ids = new HashSet<>();
                    }
                    ids.add(synsetId);
                    nouns.put(synonym, ids);
                }
            }
        }
    }

    private void parseHypernyms(String hypernyms) {
        In hypernymIn = ResourceFiles.open(WordNet.class, hypernyms);
        digraph = new Digraph(idToSynset.size());

        String oneLine;
        while (hypernymIn.hasNextLine()) {
            oneLine = hypernymIn.readLine();
            StringTokenizer oneHypernymTokenizer = new StringTokenizer(oneLine, ",");
            boolean isFirst = true;
            String hypernymFirst = null;
            while (oneHypernymTokenizer.hasMoreTokens()) {
                String hypernymId = oneHypernymTokenizer.nextToken();
                if (isFirst) {
                    hypernymFirst = hypernymId;
                    isFirst = false;
                } else {
                    digraph.addEdge(Integer.parseInt(hypernymFirst), Integer.parseInt(hypernymId));
                }
            }
        }
    }

    // Check for cycles
    private void checkCycles() {
        DirectedCycle cycle = new DirectedCycle(digraph);
        if (cycle.hasCycle()) {
            throw new IllegalArgumentException("Not a valid DAG");
        }
    }

    // Check if not rooted
    private void checkRoot() {
        int rooted = 0;
        for (int i = 0; i < digraph.V(); i++) {
            if (!this.digraph.adj(i).iterator().hasNext()) {
                rooted++;
            }
        }

        if (rooted != 1) {
            throw new IllegalArgumentException("Not a rooted DAG");
        }
    }

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(1)} to return the precomputed view.
     */
    public Iterable<String> nouns() {
        return nounView;
    }

    /**
     * Required time complexity: {@code O(log N)} or better.
     * Actual time complexity: {@code O(L)} expected for hashing; the hash-table worst case is
     * {@code O(L + log N)}.
     */
    public boolean isNoun(String word) {
        if (word == null) {
            throw new NullPointerException("Word must not be null");
        }
        return nouns.containsKey(word);
    }

    /**
     * Required time complexity: {@code O(V + E)} in the worst case.
     * Actual time complexity: {@code O(L + V + E)} expected.
     */
    public int distance(String nounA, String nounB) {
        if (nounA == null || nounB == null) {
            throw new NullPointerException("Nouns must not be null");
        }
        if (!isNoun(nounA) || !isNoun(nounB)) {
            throw new IllegalArgumentException("Both words must be nouns!");
        }
        Set<Integer> setA = nouns.get(nounA);
        Set<Integer> setB = nouns.get(nounB);
        return sap.length(setA, setB);
    }

    /**
     * Required time complexity: {@code O(V + E)} in the worst case.
     * Actual time complexity: {@code O(L + V + E)} expected.
     */
    public String sap(String nounA, String nounB) {
        if (nounA == null || nounB == null) {
            throw new NullPointerException("Nouns must not be null");
        }
        if (!isNoun(nounA) || !isNoun(nounB)) {
            throw new IllegalArgumentException("Both words must be nouns!");
        }
        Set<Integer> setA = nouns.get(nounA);
        Set<Integer> setB = nouns.get(nounB);
        int ancestor = sap.ancestor(setA, setB);
        return idToSynset.get(ancestor);
    }

    /**
     * Required time complexity: not specified; this is the assignment test client.
     * Actual time complexity: {@code O(I + V + E)} expected.
     */
    public static void main(String[] args) {
        WordNet wordNet = new WordNet(args[0], args[1]);
        StdOut.printf("nouns = %d%n", wordNet.nouns.size());
    }
}
