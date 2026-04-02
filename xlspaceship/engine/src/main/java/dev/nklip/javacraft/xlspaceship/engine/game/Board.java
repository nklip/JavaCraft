package dev.nklip.javacraft.xlspaceship.engine.game;

import dev.nklip.javacraft.xlspaceship.engine.game.ships.Spaceship;
import dev.nklip.javacraft.xlspaceship.engine.service.RandomProvider;

import java.util.*;

public class Board {

    public static final String HIT = "hit";
    public static final String KILL = "kill";
    public static final String MISS = "miss";

    public static final int SIZE = 16;
    private final Cell[][] board = new Cell[SIZE][SIZE];
    private List<Spaceship> spaceships = new ArrayList<>();

    public Board() {
        createEmptyBoard();
    }

    private void createEmptyBoard() {
        for (int i = 0; i < SIZE; i++) {
            for (int y = 0; y < SIZE; y++) {
                Cell cell = new Cell();
                cell.setUnknown();
                board[i][y] = cell;
            }
        }
    }

    public void clear() {
        for (Spaceship spaceship : spaceships) {
            spaceship.clearCells();
        }
        createEmptyBoard();
        spaceships = new ArrayList<>();
    }

    public boolean setSpaceship(RandomProvider randomProvider, Spaceship spaceship) {
        boolean isSet = setSpaceshipRandomApproach(randomProvider, spaceship);
        if (!isSet) {
            isSet = setSpaceshipIterativeApproach(spaceship);
        }
        return isSet;
    }

    private boolean setSpaceshipRandomApproach(RandomProvider randomProvider, Spaceship spaceship) {
        int width = spaceship.getWidth();
        int height = spaceship.getHeight();

        int attempts = 0;
        while (true) {
            int x = randomProvider.generateCell(SIZE - width + 1);
            int y = randomProvider.generateCell(SIZE - height + 1);

            boolean isSettable = isSettable(x, y, spaceship);

            if (isSettable) {
                printSpaceship(x, y, spaceship);
                return true;
            } else {
                attempts++;
                if (attempts > 20) { // nasty cases
                    break;
                }
            }
        }
        return false;
    }

    private boolean setSpaceshipIterativeApproach(Spaceship spaceship) {
        int width = spaceship.getWidth();
        int height = spaceship.getHeight();

        for (int x = 0; x <= SIZE - width; x++) {
            for (int y = 0; y <= SIZE - height; y++) {
                boolean isSettable = isSettable(x, y, spaceship);

                if (isSettable) {
                    printSpaceship(x, y, spaceship);
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isSettable(int x, int y, Spaceship spaceship) {
        if (x < 0 || y < 0 || x + spaceship.getWidth() > SIZE || y + spaceship.getHeight() > SIZE) {
            return false;
        }

        int xStart = Math.max(0, x - 1);
        int yStart = Math.max(0, y - 1);
        int heightLimit = Math.min(SIZE, y + spaceship.getHeight() + 1);
        int widthLimit = Math.min(SIZE, x + spaceship.getWidth() + 1);

        for (int z = yStart; z < heightLimit; z++) {
            for (int i = xStart; i < widthLimit; i++) {
                if (!board[z][i].getValue().equals(".")) {
                    return false;
                }
            }
        }
        return true;
    }

    public void printSpaceship(int x, int y, Spaceship spaceship) {
        int heightLimit = y + spaceship.getHeight();
        int widthLimit = x + spaceship.getWidth();
        if (heightLimit > SIZE) {
            heightLimit = SIZE;
        }
        if (widthLimit > SIZE) {
            widthLimit = SIZE;
        }

        List<Cell> ship = spaceship.shape();
        Iterator<Cell> cellIterator = ship.iterator();
        for (int z = y; z < heightLimit; z++) {
            for (int i = x; i < widthLimit; i++) {
                if (cellIterator.hasNext()) {
                    Cell temp = cellIterator.next();
                    spaceship.addCell(temp);
                    board[z][i] = temp;
                }
            }
        }
        spaceships.add(spaceship);
    }

    public List<Spaceship> getSpaceships() {
        return Collections.unmodifiableList(spaceships);
    }

    public List<String> toList() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            StringBuilder row = new StringBuilder();
            for (int y = 0; y < SIZE; y++) {
                row.append(board[i][y].getValue());
            }
            result.add(row.toString());
        }
        return result;
    }

    public String shot(int x, int y) {
        int before = shipsAlive();
        String value = board[y][x].getValue();
        if (value.equalsIgnoreCase("*")) {
            board[y][x].setDamagedShip();

            int after = shipsAlive();
            if (before == after) {
                return HIT;
            } else {
                return KILL;
            }
        } else if (value.equalsIgnoreCase(".")) {
            board[y][x].setMissedShot();
            return MISS;
        }
        return "unknown";
    }

    public void update(int x, int y, String status) {
        if (status.equalsIgnoreCase(HIT) || status.equalsIgnoreCase(KILL)) {
            board[y][x].setDamagedShip();
        } else if (status.equalsIgnoreCase(MISS)) {
            board[y][x].setMissedShot();
        }
    }

    private int shipsAlive() {
        int count = 0;
        for (Spaceship spaceship : spaceships) {
            if (spaceship.getHealth() > 0) {
                count++;
            }
        }
        return count;
    }

    public String getValue(int x, int y) {
        return board[y][x].getValue();
    }

    public String toString() {
        StringBuilder status = new StringBuilder();
        for (int i = 0; i < SIZE; i++) {
            for (int y = 0; y < SIZE; y++) {
                status.append(board[i][y].getValue());
            }
            status.append("\n");
        }
        return status.toString();
    }

}
