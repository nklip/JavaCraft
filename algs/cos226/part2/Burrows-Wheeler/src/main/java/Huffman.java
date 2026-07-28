public class Huffman {

    // alphabet size of extended ASCII
    private static final int R = 256;

    // Huffman trie node
    private static class Node implements Comparable<Node> {
        private final char ch;
        private final int freq;
        private final Node left, right;

        Node(char ch, int freq, Node left, Node right) {
            this.ch    = ch;
            this.freq  = freq;
            this.left  = left;
            this.right = right;
        }

        // is the node a leaf node?
        private boolean isLeaf() {
            assert (left == null && right == null) || (left != null && right != null);
            return left == null;
        }

        // compare, based on frequency
        public int compareTo(Node that) {
            return this.freq - that.freq;
        }
    }

    // compress bytes from standard input and write to standard output
    public static void compress() {
        compress(new BinaryIn(System.in), new BinaryOut(System.out));
    }

    static void compress(BinaryIn binaryIn, BinaryOut binaryOut) {
        // read the input
        String s = binaryIn.isEmpty() ? "" : binaryIn.readString();
        char[] input = s.toCharArray();

        // tabulate frequency counts
        int[] freq = new int[R];
        for (char c : input) {
            freq[c]++;
        }

        // build Huffman trie
        Node root = buildTrie(freq);

        // build code table
        String[] st = new String[R];
        buildCode(st, root, "");

        // print trie for decoder
        writeTrie(root, binaryOut);

        // print number of bytes in original uncompressed message
        binaryOut.write(input.length);

        // use Huffman code to encode input
        for (char c : input) {
            String code = st[c];
            for (int j = 0; j < code.length(); j++) {
                if (code.charAt(j) == '0') {
                    binaryOut.write(false);
                } else if (code.charAt(j) == '1') {
                    binaryOut.write(true);
                } else throw new IllegalStateException("Illegal state");
            }
        }

        // close output stream
        binaryOut.close();
    }

    // build the Huffman trie given frequencies
    private static Node buildTrie(int[] freq) {

        // initialze priority queue with singleton trees
        MinPQ<Node> pq = new MinPQ<>();
        for (char i = 0; i < R; i++)
            if (freq[i] > 0)
                pq.insert(new Node(i, freq[i], null, null));

        if (pq.isEmpty()) {
            pq.insert(new Node('\0', 0, null, null));
            pq.insert(new Node('\1', 0, null, null));
        }

        // special case in case there is only one character with a nonzero frequency
        if (pq.size() == 1) {
            if (freq['\0'] == 0) pq.insert(new Node('\0', 0, null, null));
            else                 pq.insert(new Node('\1', 0, null, null));
        }

        // merge two smallest trees
        while (pq.size() > 1) {
            Node left  = pq.delMin();
            Node right = pq.delMin();
            Node parent = new Node('\0', left.freq + right.freq, left, right);
            pq.insert(parent);
        }
        return pq.delMin();
    }

    // write bitstring-encoded trie to standard output
    private static void writeTrie(Node x, BinaryOut binaryOut) {
        if (x.isLeaf()) {
            binaryOut.write(true);
            binaryOut.write(x.ch, 8);
            return;
        }
        binaryOut.write(false);
        writeTrie(x.left, binaryOut);
        writeTrie(x.right, binaryOut);
    }

    // make a lookup table from symbols and their encodings
    private static void buildCode(String[] st, Node x, String s) {
        if (!x.isLeaf()) {
            buildCode(st, x.left,  s + '0');
            buildCode(st, x.right, s + '1');
        }
        else {
            st[x.ch] = s;
        }
    }

    // expand Huffman-encoded input from standard input and write to standard output
    public static void expand() {
        expand(new BinaryIn(System.in), new BinaryOut(System.out));
    }

    static void expand(BinaryIn binaryIn, BinaryOut binaryOut) {
        // read in Huffman trie from input stream
        Node root = readTrie(binaryIn);

        // number of bytes to write
        int length = binaryIn.readInt();

        // decode using the Huffman trie
        for (int i = 0; i < length; i++) {
            Node x = root;
            while (!x.isLeaf()) {
                boolean bit = binaryIn.readBoolean();
                if (bit) x = x.right;
                else     x = x.left;
            }
            binaryOut.write(x.ch, 8);
        }
        binaryOut.close();
    }

    private static Node readTrie(BinaryIn binaryIn) {
        boolean isLeaf = binaryIn.readBoolean();
        if (isLeaf) {
            return new Node(binaryIn.readChar(), -1, null, null);
        }
        else {
            return new Node('\0', -1, readTrie(binaryIn), readTrie(binaryIn));
        }
    }

    public static void main(String[] args) {
        if      (args[0].equals("-")) compress();
        else if (args[0].equals("+")) expand();
        else throw new IllegalArgumentException("Illegal command line argument");
    }

}
