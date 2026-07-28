/**
 * @author Lipatov Nikita
 * <p>
 * Complexity notation:
 * {@code M} is the number of nouns in an input array,
 * {@code V} and {@code E} are the WordNet digraph's vertex and edge counts, and
 * {@code I} is the WordNet input size.
 */
public class Outcast {
    private final WordNet wordNet;

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(1)}.
     */
    public Outcast(WordNet wordnet) {
        this.wordNet = wordnet;
    }

    /**
     * Given a list of WordNet nouns A1, A2, ..., An, returns the noun least related to the
     * others.
     * <p>
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(M^2 * (V + E))} expected.
     */
    public String outcast(String[] nouns)  {
        String outcast = null;
        int max = Integer.MIN_VALUE;

        for (String nounA : nouns) {
            int dist = 0;
            for (String nounB : nouns) {
                if (!nounA.equals(nounB)) {
                    dist += wordNet.distance(nounA, nounB);
                }
            }
            if (dist > max) {
                max = dist;
                outcast = nounA;
            }
        }
        return outcast;
    }

    /**
     * Required time complexity: not specified; this is the assignment test client.
     * Actual time complexity:
     * {@code O(I + V + E)} expected for setup, plus
     * {@code O(M^2 * (V + E))} per outcast noun array.
     *
     * <pre>
     * % java Outcast synsets.txt hypernyms.txt outcast5.txt outcast8.txt outcast11.txt
     * outcast5.txt: table
     * outcast8.txt: bed
     * outcast11.txt: potato
     * </pre>
     */
    static void main(String[] args)  {
        if (args == null || args.length == 0) {
            args = new String[]{"synsets.txt", "hypernyms.txt", "outcast5.txt", "outcast8.txt", "outcast11.txt"};
        }
        WordNet wordnet = new WordNet(args[0], args[1]);
        Outcast outcast = new Outcast(wordnet);
        for (int t = 2; t < args.length; t++) {
            In in = ResourceFiles.open(Outcast.class, args[t]);
            String[] nouns = in.readAllStrings();
            StdOut.println(args[t] + ": " + outcast.outcast(nouns));
        }
    }
}
