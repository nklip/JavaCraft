package dev.nklip.javacraft.tictactoe.model;

/**
 * Game instance
 *
 * @author Lipatov Nikita
 **/
public class TicTacToe {
    private final Player playerOne;
    private final Player playerTwo;
    private final GameField gameField;
    private final GameSettings gameSettings;
    private final ArtificialIntelligence ai;
    private int computerMove;

    public TicTacToe(Player one, Player two) {
        this.playerOne = one;
        this.playerTwo = two;
        this.gameField = new GameField();
        this.gameSettings = GameSettings.getInstance();
        this.ai = new ArtificialIntelligence(one, two, this.gameField);
    }

    /**
     * @param button int player button
     */
    public void makeHumanMove(int button) {
        if (!gameSettings.isFirstGamerMove() && !playerTwo.isComputer()) {
            this.gameField.setPlayer(playerTwo, button);
        } else {
            this.gameField.setPlayer(playerOne, button);
        }
    }

    public void makeComputerMove() {
        if (playerTwo.isComputer()) {
            this.ai.computerBrain();
            this.computerMove = this.ai.getNextMove();
        }
    }

    public int getComputerMove() {
        return computerMove;
    }

    public Player getWinner() {
        return gameField.getWinner();
    }

    public void printState() {
        for (int i = 0; i < 3; i++) {
            System.out.println("-------");
            for (int y = 0; y < 3; y++) {
                Player player = gameField.getGameField()[i][y];
                String print;
                if (player == null) {
                    print = "null";
                } else {
                    print = player.getName();
                }
                System.out.print(print + " || ");
            }
            System.out.print("\n");
        }
    }
}

