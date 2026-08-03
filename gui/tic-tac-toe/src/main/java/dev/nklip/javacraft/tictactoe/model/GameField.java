package dev.nklip.javacraft.tictactoe.model;

/**
 * User: Lipatov Nikita
 */
public class GameField {
    private Player[][] gameField = new Player[3][3];

    public GameField() {
        for (int i = 0; i < 3; i++) {
            for (int y = 0; y < 3; y++) {
                gameField[i][y] = null;
            }
        }
    }

    public Player[][] getGameField() {
        return gameField;
    }

    public void setGameField(Player[][] gameField) {
        this.gameField = gameField;
    }

    /**
     * @param player gamer: Human, PC
     * @param button values 1-9 left to right, upper to bottom
     */
    public void setPlayer(Player player, int button) {
        int i = ((button - 1) / 3);
        int y = ((button - (i * 3)) - 1);
        this.gameField[i][y] = player;
    }

    /**
     * @param button values 1-9; left to right, upper to bottom
     * @return Player or null
     */
    public Player getPlayer(int button) {
        int i = ((button - 1) / 3);
        int y = ((button - (i * 3)) - 1);
        return this.gameField[i][y];
    }

    /**
     * Cells:
     * -------
     * |1|2|3|
     * -------
     * |4|5|6|
     * -------
     * |7|8|9|
     * -------
     *
     * @return Player or null
     **/
    public Player getWinner() {
        Player winner;
        winner = getWinner(1, 2, 3); // row 1
        if (winner != null) {
            return winner;
        }
        winner = getWinner(4, 5, 6); // row 2
        if (winner != null) {
            return winner;
        }
        winner = getWinner(7, 8, 9); // row 3
        if (winner != null) {
            return winner;
        }
        winner = getWinner(1, 4, 7); // column 1
        if (winner != null) {
            return winner;
        }
        winner = getWinner(2, 5, 8); // column 2
        if (winner != null) {
            return winner;
        }
        winner = getWinner(3, 6, 9); // column 3
        if (winner != null) {
            return winner;
        }
        winner = getWinner(1, 5, 9); // diagonal
        if (winner != null) {
            return winner;
        }
        winner = getWinner(3, 5, 7); // diagonal
        if (winner != null) {
            return winner;
        }

        return winner;
    }

    private Player getWinner(int one, int two, int three) {
        Player playerOne = this.getPlayer(one);
        Player playerTwo = this.getPlayer(two);
        Player playerThree = this.getPlayer(three);
        if (playerOne == null || playerTwo == null || playerThree == null) {
            return null;
        }
        if (playerOne.equals(playerTwo) && playerOne.equals(playerThree)) {
            return playerOne;
        }
        return null;
    }


}
