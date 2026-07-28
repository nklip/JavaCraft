import java.util.*;

/**
 * @author Lipatov Nikita
 * <p>
 * Complexity notation: {@code N} is the number of teams.
 * The flow network has {@code V = O(N^2)} vertices and {@code E = O(N^2)} edges.
 * As directed by the assignment analysis, max-flow is treated as {@code O(V * E^2) = O(N^6)}.
 */
public class BaseballElimination {

    private final TreeMap<Integer, Team> intToTeams;
    private final TreeMap<String, Team> teams;
    private final int[][] grid;

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(N^2)}.
     */
    public BaseballElimination(String filename) {
        int teamsCount = 0;
        In teamsFile = ResourceFiles.open(BaseballElimination.class, filename);
        if (teamsFile.hasNextLine()) {
            teamsCount = Integer.parseInt(teamsFile.readLine());
        }
        intToTeams = new TreeMap<>();
        teams = new TreeMap<>();
        grid = new int[teamsCount][teamsCount];

        // teams parsing
        while (teamsFile.hasNextLine()) {
            String teamLine = teamsFile.readLine();

            // team parsing
            int tempCounter = 0;
            Team team = new Team();
            team.id = teams.size();

            StringTokenizer teamTokens = new StringTokenizer(teamLine, " ");
            while (teamTokens.hasMoreTokens()) {
                String token = teamTokens.nextToken();
                switch (tempCounter++) {
                    case 0:
                        team.name = token;
                        break;
                    case 1:
                        team.wins = Integer.parseInt(token);
                        break;
                    case 2:
                        team.losses = Integer.parseInt(token);
                        break;
                    case 3:
                        team.toPlay = Integer.parseInt(token);
                        break;
                    default:
                        int row = teams.size();
                        int col = tempCounter - 5; // magic ^_^ (°◡°♡)
                        grid[row][col] = Integer.parseInt(token);
                }
            }
            intToTeams.put(team.id, team);
            teams.put(team.name, team);
        }
    }

    static void main(String[] args) {
        String filename = "teams4.txt";
        if (args != null && args.length > 0) {
            filename = args[0];
        }
        BaseballElimination division = new BaseballElimination(filename);
        for (String team : division.teams()) {
            if (division.isEliminated(team)) {
                StdOut.print(team + " is eliminated by the subset R = { ");
                for (String t : division.certificateOfElimination(team)) {
                    StdOut.print(t + " ");
                }
                StdOut.println("}");
            } else {
                StdOut.println(team + " is not eliminated");
            }
        }
    }

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(1)}.
     */
    public int numberOfTeams() {
        return teams.size();
    }

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(N log N)}.
     */
    public Iterable<String> teams() {
        List<Team> sortableTeams = new ArrayList<>(teams.size());
        for (String key : teams.keySet()) {
            sortableTeams.add(teams.get(key));
        }

        Collections.sort(sortableTeams);
        List<String> sortableTeamNames = new ArrayList<>(teams.size());
        for (Team team : sortableTeams) {
            sortableTeamNames.add(team.name);
        }
        return sortableTeamNames;
    }

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(log N)}.
     */
    public int wins(String team) {
        if (!teams.containsKey(team)) {
            throw new IllegalArgumentException("Such " + team + " team doesn't exist!");
        }
        return teams.get(team).wins;
    }

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(log N)}.
     */
    public int losses(String team) {
        if (!teams.containsKey(team)) {
            throw new IllegalArgumentException("Such " + team + " team doesn't exist!");
        }

        return teams.get(team).losses;
    }

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(log N)}.
     */
    public int remaining(String team) {
        if (!teams.containsKey(team)) {
            throw new IllegalArgumentException("Such " + team + " team doesn't exist!");
        }
        return teams.get(team).toPlay;
    }

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(log N)}.
     */
    public int against(String teamName1, String teamName2) {
        if (!teams.containsKey(teamName1)) {
            throw new IllegalArgumentException("Such " + teamName1 + " team doesn't exist!");
        }
        if (!teams.containsKey(teamName2)) {
            throw new IllegalArgumentException("Such " + teamName2 + " team doesn't exist!");
        }
        Team team1 = teams.get(teamName1);
        Team team2 = teams.get(teamName2);
        int teamPosition1 = team1.id;
        int teamPosition2 = team2.id;
        return grid[teamPosition1][teamPosition2];
    }

    /**
     * Required time complexity: not specified; the assignment asks students to analyze it.
     * Actual worst-case time complexity: {@code O(N^6)}.
     */
    public boolean isEliminated(String team) {
        if (!teams.containsKey(team)) {
            throw new IllegalArgumentException("Such " + team + " team doesn't exist!");
        }
        Iterable<String> result = certificateOfElimination(team);
        return result != null;
    }

    private boolean isEliminated(FlowNetwork flowNetwork, int sourceVertex) {
        // Returns the edges incident on vertex v (includes both edges pointing to and from v
        for (FlowEdge flowEdge : flowNetwork.adj(sourceVertex)) {
            // Returns the endpoint of the edge that is different from the given vertex
            // (unless the edge represents a self-loop in which case it returns the same vertex).
            int to = flowEdge.other(sourceVertex);
            // Returns the residual capacity of the edge in the direction to the given vertex.
            if (flowEdge.residualCapacityTo(to) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Required time complexity: not specified; the assignment asks students to analyze it.
     * Actual worst-case time complexity: {@code O(N^6)}.
     */
    public Iterable<String> certificateOfElimination(String team) {
        if (!teams.containsKey(team)) {
            throw new IllegalArgumentException("Such " + team + " team doesn't exist!");
        }
        Team sourceTeam = teams.get(team);

        List<String> teamNames = new ArrayList<>();

        // trivial elimination
        trivialElimination(teamNames, sourceTeam);
        if (!teamNames.isEmpty()) {
            return teamNames;
        }

        // nontrivial elimination
        nonTrivialElimination(teamNames, sourceTeam);
        if (!teamNames.isEmpty()) {
            return teamNames;
        }

        // not eliminated
        return null;
    }

    /**
     * Trivial elimination. If the maximum number of games team x can win is less than the number of wins of some other
     * team i, then team x is trivially eliminated (as is Montreal in the example above). That is, if w[x] + r[x] <
     * w[i], then team x is mathematically eliminated.
     */
    private void trivialElimination(List<String> teamNames, Team sourceTeam) {
        int maxPossibleResult = sourceTeam.wins + sourceTeam.toPlay;
        for (String key : teams.keySet()) {
            if (!sourceTeam.name.equals(key)) {
                Team tempTeam = teams.get(key);
                if (tempTeam.wins > maxPossibleResult) {
                    teamNames.add(tempTeam.name);
                }
            }
        }
    }

    /**
     * Nontrivial elimination. Otherwise, we create a flow network and solve a maxflow problem in it. In the network,
     * feasible integral flows correspond to outcomes of the remaining schedule. There are vertices corresponding to
     * teams (other than team x) and to remaining divisional games (not involving team x). Intuitively, each unit of
     * flow in the network corresponds to a remaining game. As it flows through the network from s to t, it passes from
     * a game vertex, say between teams i and j, then through one of the team vertices i or j, classifying this game as
     * being won by that team.
     */
    private void nonTrivialElimination(List<String> teamNames, Team sourceTeam) {
        int countGamesVertices = (teams.size() * teams.size() - teams.size()) / 2;
        int sourceVertex = 0;
        int targetVertex = countGamesVertices + teams.size() + 1;

        // build FN
        FlowNetwork flowNetwork = buildFlowNetwork(sourceTeam, sourceVertex, targetVertex);

        // param1 G the flow network
        // param2 s the source vertex
        // param3 t the sink vertex
        FordFulkerson fordFulkerson = new FordFulkerson(flowNetwork, sourceVertex, targetVertex);
        if (isEliminated(flowNetwork, sourceVertex)) {
            for (int i = 0; i < numberOfTeams(); i++) {
                if (i != sourceTeam.id && fordFulkerson.inCut(targetVertex - numberOfTeams() + i)) {
                    teamNames.add(intToTeams.get(i).name);
                }
            }
        }
    }

    private FlowNetwork buildFlowNetwork(Team sourceTeam, int sourceVertex, int targetVertex) {
        int countGamesVertices = (teams.size() * teams.size() - teams.size()) / 2;
        FlowNetwork flowNetwork = new FlowNetwork(targetVertex + 1);

        // game vertices
        int headVertex = 1;
        for (int row = 0; row < teams.size(); row++) {
            // required for loss teams
            int plusPrefix = 0;
            for (int col = row; col < teams.size(); col++) {
                if (row == col) { // team cannot play themself
                    continue;
                }
                plusPrefix++;
                int value = grid[row][col];

                // We connect an artificial source vertex s to each game vertex i-j and set its capacity to g[i][j].
                // If a flow uses all g[i][j] units of capacity on this edge, then we interpret this as playing
                // all of these games, with the wins distributed between the team vertices i and j.
                FlowEdge tempEdge = new FlowEdge(sourceVertex, headVertex, value);
                flowNetwork.addEdge(tempEdge);

                // We connect each game vertex i-j with the two opposing team vertices to ensure that one of the
                // two teams earns a win. We do not need to restrict the amount of flow on such edges.
                FlowEdge winEdge = new FlowEdge(headVertex, countGamesVertices + row + 1, value);
                FlowEdge lossEdge = new FlowEdge(headVertex, countGamesVertices + row + plusPrefix + 1, value);
                flowNetwork.addEdge(winEdge);
                flowNetwork.addEdge(lossEdge);

                headVertex++;
            }
        }

        // Finally, we connect each team vertex to an artificial sink vertex t.
        // We want to know if there is some way of completing all the games so that team x ends up
        // winning at least as many games as team i. Since team x can win as many as w[x] + r[x] games,
        // we prevent team i from winning more than that many games in total, by including an edge from team
        // vertex i to the sink vertex with capacity w[x] + r[x] - w[i].
        for (int teams = 0; teams < numberOfTeams(); teams++) {
            int teamN = countGamesVertices + teams + 1;
            Team team = intToTeams.get(teams);

            int valueToTarget = sourceTeam.wins + sourceTeam.toPlay - team.wins;
            valueToTarget = Math.max(valueToTarget, 0);
            FlowEdge edgeToTarget = new FlowEdge(teamN, targetVertex, valueToTarget);
            flowNetwork.addEdge(edgeToTarget);
        }
        return flowNetwork;
    }

    private static class Team implements Comparable<Team> {
        private int id;
        private String name;
        private int wins;
        private int losses;
        private int toPlay;

        public int compareTo(Team team) {
            return Integer.compare(this.id, team.id);
        }

    }
}
