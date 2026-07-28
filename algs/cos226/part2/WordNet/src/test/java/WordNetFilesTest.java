import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntBiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives {@link WordNet}, {@link SAP} and {@link Outcast} from the files in
 * {@code src/test/resources}.
 *
 * <p>The hypernym fixtures carry their verdict in their names. Anything called {@code Invalid} has
 * to be rejected by the {@code WordNet} constructor, and the rest of the name says why: a
 * {@code Cycle} is not a DAG, {@code TwoRoots} is not rooted. Everything without {@code Invalid} -
 * including the deliberately awkward {@code AmbiguousAncestor}, {@code ManyAncestors} and
 * {@code WrongBFS} graphs - has to be accepted.
 *
 * <p>{@code SAP} is checked against a breadth-first search written out in this file: for a pair of
 * vertices it takes the distance to every vertex from each side and keeps the smallest total. That
 * is the definition of a shortest ancestral path stated directly, with no caching and no shared
 * code with the implementation, which matters here because {@code SAP} memoises its answers. On
 * the small digraphs every pair of vertices is checked, not a sample.
 *
 * <p>The three answers in the {@code Outcast} test client's own documentation - {@code table},
 * {@code bed}, {@code potato} - anchor the outcast fixtures; the rest are recorded values.
 */
class WordNetFilesTest {

    /** The full WordNet takes about a quarter of a second to build, so it is built once. */
    private static WordNet fullWordNet;
    private static Outcast outcast;

    @BeforeAll
    static void buildTheFullWordNet() {
        fullWordNet = new WordNet("synsets.txt", "hypernyms.txt");
        outcast = new Outcast(fullWordNet);
    }

    @Test
    void testSapIterableOverloadsTraverseEachSourceOnce() {
        Digraph digraph = new Digraph(ResourceFiles.open(WordNetFilesTest.class, "digraph1.txt"));
        SAP sap = new SAP(digraph);

        assertEachSourceIsTraversedOnce(sap::length, 3);
        assertEachSourceIsTraversedOnce(sap::ancestor, 5);
    }

    @Test
    void testSapFirstIterableOverloadsTraverseEachSourceOnce() {
        Digraph digraph = new Digraph(ResourceFiles.open(WordNetFilesTest.class, "digraph1.txt"));
        SAPFirst sap = new SAPFirst(digraph);

        assertEachSourceIsTraversedOnce(sap::length, 3);
        assertEachSourceIsTraversedOnce(sap::ancestor, 5);
    }

    @Test
    void testSapDoesNotRetainEveryScalarQuery() {
        assertDoesNotUseAMapBackedQueryCache(SAP.class);
    }

    @Test
    void testSapFirstDoesNotRetainEveryScalarQuery() {
        assertDoesNotUseAMapBackedQueryCache(SAPFirst.class);
    }

    private static void assertDoesNotUseAMapBackedQueryCache(Class<?> sapType) {
        boolean hasMapField = Stream.concat(Stream.of(sapType), Arrays.stream(sapType.getDeclaredClasses()))
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .anyMatch(field -> Map.class.isAssignableFrom(field.getType()));

        Assertions.assertFalse(
                hasMapField,
                () -> sapType.getSimpleName() + " must not retain an unbounded map of scalar queries");
    }

    private static void assertEachSourceIsTraversedOnce(
            ToIntBiFunction<Iterable<Integer>, Iterable<Integer>> query, int expected) {
        Iterable<Integer> left = new OneShotIterable(List.of(3, 9));
        Iterable<Integer> right = new OneShotIterable(List.of(11, 12));

        Assertions.assertEquals(expected, query.applyAsInt(left, right));
    }

    private static final class OneShotIterable implements Iterable<Integer> {
        private final Iterable<Integer> values;
        private boolean traversed;

        private OneShotIterable(Iterable<Integer> values) {
            this.values = values;
        }

        @Override
        public Iterator<Integer> iterator() {
            if (traversed) {
                throw new IllegalStateException("The iterable was traversed more than once");
            }
            traversed = true;
            return values.iterator();
        }
    }

    // ---------- what the constructor accepts and rejects ----------

    /**
     * Each of these is named for the defect it carries, and the message has to match: a cycle
     * fails the DAG check, extra roots fail the rooted check.
     */
    @ParameterizedTest(name = "{1} is rejected: {2}")
    @CsvSource({
            "synsets3.txt, hypernyms3InvalidCycle.txt,     Not a valid DAG",
            "synsets3.txt, hypernyms3InvalidTwoRoots.txt,  Not a rooted DAG",
            "synsets6.txt, hypernyms6InvalidCycle.txt,     Not a valid DAG",
            "synsets6.txt, hypernyms6InvalidTwoRoots.txt,  Not a rooted DAG",
            "synsets6.txt, hypernyms6InvalidCycle+Path.txt, Not a rooted DAG"
    })
    void testTheInvalidHypernymFilesAreRejected(String synsets, String hypernyms, String reason) {
        IllegalArgumentException thrown = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new WordNet(synsets, hypernyms),
                hypernyms + " should not be accepted"
        );

        Assertions.assertEquals(reason, thrown.getMessage(), hypernyms);
    }

    static Stream<Arguments> validPairs() {
        return Stream.of(
                Arguments.of("synsets6.txt", "hypernyms6TwoAncestors.txt", 6),
                Arguments.of("synsets8.txt", "hypernyms8ManyAncestors.txt", 8),
                Arguments.of("synsets8.txt", "hypernyms8WrongBFS.txt", 8),
                Arguments.of("synsets11.txt", "hypernyms11AmbiguousAncestor.txt", 11),
                Arguments.of("synsets11.txt", "hypernyms11ManyPathsOneAncestor.txt", 11),
                Arguments.of("synsets11.txt", "hypernymsManyPathsOneAncestor.txt", 11),
                Arguments.of("synsets15.txt", "hypernyms15Path.txt", 15),
                Arguments.of("synsets15.txt", "hypernyms15Tree.txt", 15),
                Arguments.of("synsets100-subgraph.txt", "hypernyms100-subgraph.txt", 157),
                Arguments.of("synsets500-subgraph.txt", "hypernyms500-subgraph.txt", 751),
                Arguments.of("synsets1000-subgraph.txt", "hypernyms1000-subgraph.txt", 1664),
                Arguments.of("synsets5000-subgraph.txt", "hypernyms5000-subgraph.txt", 7992),
                Arguments.of("synsets10000-subgraph.txt", "hypernyms10000-subgraph.txt", 19600),
                Arguments.of("synsets50000-subgraph.txt", "hypernyms50000-subgraph.txt", 78227)
        );
    }

    @ParameterizedTest(name = "{0} with {1} holds {2} nouns")
    @MethodSource("validPairs")
    void testTheValidPairsAreAcceptedWithTheExpectedNouns(String synsets, String hypernyms, int expectedNouns) {
        WordNet wordNet = new WordNet(synsets, hypernyms);

        int nouns = 0;
        for (String noun : wordNet.nouns()) {
            Assertions.assertTrue(wordNet.isNoun(noun), synsets + ": " + noun);
            nouns++;
        }

        Assertions.assertEquals(expectedNouns, nouns, synsets + " with " + hypernyms);
    }

    /**
     * The same 82,192 synsets under progressively denser hypernym files. More edges mean more
     * ways up the hierarchy, but the graph has to stay a rooted DAG in every case.
     */
    @ParameterizedTest(name = "synsets.txt with {0} is still a rooted DAG")
    @ValueSource(strings = {"hypernyms100K.txt", "hypernyms200K.txt", "hypernyms300K.txt"})
    void testTheDenserHypernymFilesAreStillValid(String hypernyms) {
        WordNet wordNet = new WordNet("synsets.txt", hypernyms);

        Assertions.assertTrue(wordNet.isNoun("bird"), hypernyms);
    }

    // ---------- SAP against a breadth-first search ----------

    /** The small digraphs, every pair of vertices - no sampling. */
    @ParameterizedTest(name = "{0}: SAP matches a plain BFS on every vertex pair")
    @ValueSource(strings = {"digraph1.txt", "digraph2.txt", "digraph3.txt", "digraph4.txt",
                            "digraph5.txt", "digraph6.txt", "digraph9.txt",
                            "digraph-ambiguous-ancestor.txt"})
    void testSapMatchesABreadthFirstSearchOnEveryPair(String fixture) {
        Digraph digraph = new Digraph(ResourceFiles.open(WordNetFilesTest.class, fixture));
        SAP sap = new SAP(digraph);
        SAPFirst sapFirst = new SAPFirst(digraph);

        for (int v = 0; v < digraph.V(); v++) {
            for (int w = 0; w < digraph.V(); w++) {
                assertSapAgreesWithBreadthFirstSearch(digraph, sap, v, w, fixture);
                assertSapFirstAgreesWithBreadthFirstSearch(digraph, sapFirst, v, w, fixture);
            }
        }
    }

    /**
     * The whole hierarchy as one digraph - 82,192 vertices, far too many pairs to enumerate, so a
     * fixed sample is used instead. The seed is fixed so a failure can be reproduced.
     */
    @Test
    void testSapMatchesABreadthFirstSearchOnTheFullHierarchy() {
        Digraph digraph = new Digraph(ResourceFiles.open(WordNetFilesTest.class, "digraph-wordnet.txt"));
        SAP sap = new SAP(digraph);
        SAPFirst sapFirst = new SAPFirst(digraph);
        Random random = new Random(2026);

        Assertions.assertEquals(82192, digraph.V());
        for (int sample = 0; sample < 25; sample++) {
            int v = random.nextInt(digraph.V());
            int w = random.nextInt(digraph.V());

            assertSapAgreesWithBreadthFirstSearch(digraph, sap, v, w, "digraph-wordnet.txt");
            assertSapFirstAgreesWithBreadthFirstSearch(digraph, sapFirst, v, w, "digraph-wordnet.txt");
            Assertions.assertEquals(
                    sap.ancestor(v, w), sapFirst.ancestor(v, w),
                    () -> "the two implementations disagree on the ancestor of " + v + " and " + w);
        }
    }

    /**
     * The set overloads, which is what {@code WordNet} actually calls: a noun can belong to several
     * synsets, so the answer is the best over the cross product. Unreachable pairs contribute
     * nothing and must not drag the result down to -1, and the ancestor has to be the vertex on
     * the shortest path rather than merely some common ancestor.
     *
     * <p>Random subsets against the same breadth-first oracle, seeded so a failure reproduces.
     *
     * <p>Both implementations are driven: {@code SAP} runs two full breadth-first searches per
     * query, {@code SAPFirst} grows from both ends and stops early, and they have to agree with
     * the oracle and with each other.
     */
    @ParameterizedTest(name = "{0}: the set overloads match a plain BFS")
    @ValueSource(strings = {"digraph1.txt", "digraph2.txt", "digraph3.txt", "digraph4.txt",
                            "digraph5.txt", "digraph6.txt", "digraph9.txt",
                            "digraph-ambiguous-ancestor.txt"})
    void testTheSetOverloadsMatchABreadthFirstSearch(String fixture) {
        Digraph digraph = new Digraph(ResourceFiles.open(WordNetFilesTest.class, fixture));
        SAP sap = new SAP(digraph);
        SAPFirst sapFirst = new SAPFirst(digraph);
        Random random = new Random(31);

        for (int trial = 0; trial < 60; trial++) {
            List<Integer> left = randomSubset(random, digraph.V());
            List<Integer> right = randomSubset(random, digraph.V());
            if (left.isEmpty() || right.isEmpty()) {
                continue;
            }

            int[] fromLeft = breadthFirstDistances(digraph, left);
            int[] fromRight = breadthFirstDistances(digraph, right);
            int expected = -1;
            for (int vertex = 0; vertex < digraph.V(); vertex++) {
                if (fromLeft[vertex] >= 0 && fromRight[vertex] >= 0) {
                    int total = fromLeft[vertex] + fromRight[vertex];
                    if (expected < 0 || total < expected) {
                        expected = total;
                    }
                }
            }

            int shortest = expected;
            Assertions.assertEquals(
                    shortest, sap.length(left, right),
                    () -> fixture + ": SAP.length(" + left + ", " + right + ")");
            Assertions.assertEquals(
                    shortest, sapFirst.length(left, right),
                    () -> fixture + ": SAPFirst.length(" + left + ", " + right + ")");

            assertAncestorIsOnAShortestPath(sap.ancestor(left, right), shortest, fromLeft, fromRight,
                    () -> fixture + ": SAP.ancestor(" + left + ", " + right + ")");
            assertAncestorIsOnAShortestPath(sapFirst.ancestor(left, right), shortest, fromLeft, fromRight,
                    () -> fixture + ": SAPFirst.ancestor(" + left + ", " + right + ")");
        }
    }

    private static void assertAncestorIsOnAShortestPath(
            int ancestor, int shortest, int[] fromLeft, int[] fromRight,
            java.util.function.Supplier<String> what) {
        if (shortest < 0) {
            Assertions.assertEquals(-1, ancestor, what);
            return;
        }
        Assertions.assertTrue(
                ancestor >= 0 && fromLeft[ancestor] >= 0 && fromRight[ancestor] >= 0,
                () -> what.get() + ": " + ancestor + " is not reachable from both sides");
        Assertions.assertEquals(
                shortest, fromLeft[ancestor] + fromRight[ancestor],
                () -> what.get() + ": " + ancestor + " is not on a path of length " + shortest);
    }

    private static List<Integer> randomSubset(Random random, int vertices) {
        List<Integer> subset = new java.util.ArrayList<>();
        for (int vertex = 0; vertex < vertices; vertex++) {
            if (random.nextInt(4) == 0) {
                subset.add(vertex);
            }
        }
        return subset;
    }

    /** Both arguments play the same role, so swapping them cannot change the answer. */
    @ParameterizedTest(name = "{0}: SAP is symmetric in its arguments")
    @ValueSource(strings = {"digraph1.txt", "digraph3.txt", "digraph5.txt", "digraph9.txt"})
    void testSapIsSymmetric(String fixture) {
        Digraph digraph = new Digraph(ResourceFiles.open(WordNetFilesTest.class, fixture));
        SAP sap = new SAP(digraph);

        for (int v = 0; v < digraph.V(); v++) {
            for (int w = 0; w < digraph.V(); w++) {
                int forward = sap.length(v, w);
                int backward = sap.length(w, v);
                Assertions.assertEquals(forward, backward, fixture + ": length(" + v + ", " + w + ")");
            }
        }
    }

    // ---------- the outcast fixtures ----------

    static Stream<Arguments> outcastFixtures() {
        return Stream.of(
                Arguments.of("outcast2.txt", 2, "Turing"),
                Arguments.of("outcast3.txt", 3, "Mickey_Mouse"),
                Arguments.of("outcast4.txt", 4, "probability"),
                Arguments.of("outcast5.txt", 5, "table"),
                Arguments.of("outcast5a.txt", 5, "heart"),
                Arguments.of("outcast7.txt", 7, "India"),
                Arguments.of("outcast8.txt", 8, "bed"),
                Arguments.of("outcast8a.txt", 8, "playboy"),
                Arguments.of("outcast8b.txt", 8, "cabbage"),
                Arguments.of("outcast8c.txt", 8, "tree"),
                Arguments.of("outcast9.txt", 9, "tree"),
                Arguments.of("outcast9a.txt", 9, "eyes"),
                Arguments.of("outcast10.txt", 10, "albatross"),
                Arguments.of("outcast10a.txt", 10, "serendipity"),
                Arguments.of("outcast11.txt", 11, "potato"),
                Arguments.of("outcast12.txt", 12, "Minneapolis"),
                Arguments.of("outcast12a.txt", 12, "mongoose"),
                Arguments.of("outcast17.txt", 17, "particularism"),
                Arguments.of("outcast20.txt", 20, "particularism"),
                Arguments.of("outcast29.txt", 29, "acorn")
        );
    }

    /**
     * {@code outcast5.txt}, {@code outcast8.txt} and {@code outcast11.txt} are the three the
     * {@code Outcast} test client documents; the rest are recorded from a run and kept as a
     * baseline. Each file's noun count is asserted too, so a fixture that changed underneath would
     * be obvious rather than silently shifting the expected answer.
     */
    @ParameterizedTest(name = "the outcast of {0} is {2}")
    @MethodSource("outcastFixtures")
    void testTheOutcastOfEachFixture(String fixture, int expectedNouns, String expectedOutcast) {
        String[] nouns = ResourceFiles.open(WordNetFilesTest.class, fixture).readAllStrings();

        Assertions.assertEquals(expectedNouns, nouns.length, fixture + ": fixture changed");
        Assertions.assertEquals(expectedOutcast, outcast.outcast(nouns), fixture);
    }

    /**
     * The definition behind the expected answers above: the outcast is the noun whose total
     * distance to the others is largest. Only the small fixtures are checked this way - it costs a
     * second full pass over every pair, and the answers above already pin the result, so running
     * it on all twenty would triple the time for no extra coverage.
     */
    @ParameterizedTest(name = "{0}: no noun is further from the rest than the outcast")
    @CsvSource({
            "outcast2.txt, Turing", "outcast3.txt, Mickey_Mouse", "outcast4.txt, probability",
            "outcast5.txt, table", "outcast7.txt, India", "outcast8.txt, bed"
    })
    void testTheOutcastIsTheFurthestNounFromTheRest(String fixture, String expected) {
        String[] nouns = ResourceFiles.open(WordNetFilesTest.class, fixture).readAllStrings();
        int outcastDistance = totalDistanceToTheOthers(expected, nouns);

        for (String noun : nouns) {
            Assertions.assertTrue(
                    totalDistanceToTheOthers(noun, nouns) <= outcastDistance,
                    () -> fixture + ": " + noun + " is further from the rest than " + expected);
        }
    }

    // ---------- distance and ancestor on the real hierarchy ----------

    @ParameterizedTest(name = "distance is symmetric for {0} and {1}")
    @CsvSource({
            "white_marlin, mileage",
            "Black_Plague, black_marlin",
            "American_water_spaniel, histology",
            "Brown_Swiss, barrel_roll",
            "individual, edible_fruit",
            "administrative_district, populated_area"
    })
    void testDistanceIsSymmetricAndTheAncestorIsShared(String nounA, String nounB) {
        int forward = fullWordNet.distance(nounA, nounB);

        Assertions.assertEquals(forward, fullWordNet.distance(nounB, nounA));
        Assertions.assertEquals(fullWordNet.sap(nounA, nounB), fullWordNet.sap(nounB, nounA));
        Assertions.assertTrue(forward >= 0, nounA + " to " + nounB);
    }

    /** A noun is zero steps from itself, and is its own common ancestor's synset. */
    @ParameterizedTest(name = "{0} is zero away from itself")
    @ValueSource(strings = {"bird", "table", "potato", "individual"})
    void testANounIsZeroDistanceFromItself(String noun) {
        Assertions.assertTrue(fullWordNet.isNoun(noun));
        Assertions.assertEquals(0, fullWordNet.distance(noun, noun), noun);
        Assertions.assertNotNull(fullWordNet.sap(noun, noun), noun);
    }

    @Test
    void testRejectsWordsThatAreNotNouns() {
        Assertions.assertFalse(fullWordNet.isNoun("not_a_wordnet_noun"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> fullWordNet.distance("bird", "not_a_wordnet_noun"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> fullWordNet.sap("not_a_wordnet_noun", "bird"));
        Assertions.assertThrows(NullPointerException.class, () -> fullWordNet.isNoun(null));
    }

    // ---------- helpers ----------

    private static int totalDistanceToTheOthers(String noun, String[] nouns) {
        int total = 0;
        for (String other : nouns) {
            if (!noun.equals(other)) {
                total += fullWordNet.distance(noun, other);
            }
        }
        return total;
    }

    private static void assertSapAgreesWithBreadthFirstSearch(
            Digraph digraph, SAP sap, int v, int w, String fixture) {
        int[] fromV = breadthFirstDistances(digraph, v);
        int[] fromW = breadthFirstDistances(digraph, w);

        int shortest = -1;
        for (int vertex = 0; vertex < digraph.V(); vertex++) {
            if (fromV[vertex] >= 0 && fromW[vertex] >= 0) {
                int total = fromV[vertex] + fromW[vertex];
                if (shortest < 0 || total < shortest) {
                    shortest = total;
                }
            }
        }

        int expected = shortest;
        Assertions.assertEquals(
                expected, sap.length(v, w), () -> fixture + ": length(" + v + ", " + w + ")");

        int ancestor = sap.ancestor(v, w);
        if (expected < 0) {
            Assertions.assertEquals(
                    -1, ancestor, () -> fixture + ": " + v + " and " + w + " share no ancestor");
        } else {
            Assertions.assertTrue(
                    ancestor >= 0 && fromV[ancestor] >= 0 && fromW[ancestor] >= 0,
                    () -> fixture + ": " + ancestor + " is not reachable from both " + v + " and " + w);
            Assertions.assertEquals(
                    expected, fromV[ancestor] + fromW[ancestor],
                    () -> fixture + ": " + ancestor + " is not on a shortest ancestral path");
        }
    }

    /** The same check for {@code SAPFirst}, whose two-ended search must reach the same answers. */
    private static void assertSapFirstAgreesWithBreadthFirstSearch(
            Digraph digraph, SAPFirst sap, int v, int w, String fixture) {
        int[] fromV = breadthFirstDistances(digraph, v);
        int[] fromW = breadthFirstDistances(digraph, w);

        int shortest = -1;
        for (int vertex = 0; vertex < digraph.V(); vertex++) {
            if (fromV[vertex] >= 0 && fromW[vertex] >= 0) {
                int total = fromV[vertex] + fromW[vertex];
                if (shortest < 0 || total < shortest) {
                    shortest = total;
                }
            }
        }

        int expected = shortest;
        Assertions.assertEquals(
                expected, sap.length(v, w), () -> fixture + ": SAPFirst.length(" + v + ", " + w + ")");
        assertAncestorIsOnAShortestPath(
                sap.ancestor(v, w), expected, fromV, fromW,
                () -> fixture + ": SAPFirst.ancestor(" + v + ", " + w + ")");
    }

    /** @return steps from any of {@code sources} to every vertex, or -1 where there is no path */
    private static int[] breadthFirstDistances(Digraph digraph, List<Integer> sources) {
        int[] distance = new int[digraph.V()];
        Arrays.fill(distance, -1);
        Deque<Integer> queue = new ArrayDeque<>();
        for (int source : sources) {
            distance[source] = 0;
            queue.add(source);
        }

        while (!queue.isEmpty()) {
            int vertex = queue.poll();
            for (int next : digraph.adj(vertex)) {
                if (distance[next] < 0) {
                    distance[next] = distance[vertex] + 1;
                    queue.add(next);
                }
            }
        }
        return distance;
    }

    /** @return steps from {@code source} to every vertex, or -1 where there is no path */
    private static int[] breadthFirstDistances(Digraph digraph, int source) {
        int[] distance = new int[digraph.V()];
        Arrays.fill(distance, -1);
        Deque<Integer> queue = new ArrayDeque<>(List.of(source));
        distance[source] = 0;

        while (!queue.isEmpty()) {
            int vertex = queue.poll();
            for (int next : digraph.adj(vertex)) {
                if (distance[next] < 0) {
                    distance[next] = distance[vertex] + 1;
                    queue.add(next);
                }
            }
        }
        return distance;
    }
}
