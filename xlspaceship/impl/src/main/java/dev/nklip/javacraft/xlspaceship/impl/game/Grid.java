package dev.nklip.javacraft.xlspaceship.impl.game;

import dev.nklip.javacraft.xlspaceship.impl.game.ships.Spaceship;
import dev.nklip.javacraft.xlspaceship.impl.service.RandomServices;

import java.util.*;

public class Grid {

    public static final String HIT = "hit";
    public static final String KILL = "kill";
    public static final String MISS = "miss";

    public static final int SIZE = 16;
    private final Cell[][] grid = new Cell[SIZE][SIZE];
    private List<Spaceship> spaceshipList = new ArrayList<>();

    public Grid() {
        createEmptyGrid();
    }

    private void createEmptyGrid() {
        for (int i = 0; i < SIZE; i++) {
            for (int y = 0; y < SIZE; y++) {
                Cell cell = new Cell();
                cell.setUnknown();
                grid[i][y] = cell;
            }
        }
    }

    public void clear() {
        createEmptyGrid();
        spaceshipList = new ArrayList<>();
    }

    public boolean setSpaceship(RandomServices randomServices, Spaceship spaceship) {
        boolean isSet = setSpaceshipRandomApproach(randomServices, spaceship);
        if (!isSet) {
            isSet = setSpaceshipIterativeApproach(spaceship);
        }
        return isSet;
    }

    private boolean setSpaceshipRandomApproach(RandomServices randomServices, Spaceship spaceship) {
        int width = spaceship.getWidth();
        int height = spaceship.getHeight();

        int attempts = 0;
        while (true) {
            int x = randomServices.generateCell(SIZE - width);
            int y = randomServices.generateCell(SIZE - height);

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

        for (int x = 0; x < SIZE - width; x++) {
            for (int y = 0; y < SIZE - height; y++) {
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
        int xPlus = 2;
        x--;
        if (x < 0) {
            xPlus = 1;
            x = 0;
        }
        int yPlus = 2;
        y--;
        if (y < 0) {
            yPlus = 2;
            y = 0;
        }

        int heightLimit = y + spaceship.getHeight() + yPlus;
        int widthLimit = x + spaceship.getWidth() + xPlus;
        if (heightLimit > SIZE) {
            heightLimit = SIZE;
        }
        if (widthLimit > SIZE) {
            widthLimit = SIZE;
        }

        for (int z = y; z < heightLimit; z++) {
            for (int i = x; i < widthLimit; i++) {
                if (!grid[z][i].getValue().equals(".")) {
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
                    grid[z][i] = temp;
                }
            }
        }
        spaceshipList.add(spaceship);
    }

    public List<Spaceship> getSpaceshipList() {
        return Collections.unmodifiableList(spaceshipList);
    }

    public List<String> toList() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            StringBuilder row = new StringBuilder();
            for (int y = 0; y < SIZE; y++) {
                row.append(grid[i][y].getValue());
            }
            result.add(row.toString());
        }
        return result;
    }

    public String shot(int x, int y) {
        int before = shipsAlive();
        String value = grid[y][x].getValue();
        if (value.equalsIgnoreCase("*")) {
            grid[y][x].setDamagedShip();

            int after = shipsAlive();
            if (before == after) {
                return HIT;
            } else {
                return KILL;
            }
        } else if (value.equalsIgnoreCase(".")) {
            grid[y][x].setMissedShot();
            return MISS;
        }
        return "unknown";
    }

    public String update(int x, int y, String status) {
        if (status.equalsIgnoreCase(HIT) || status.equalsIgnoreCase(KILL)) {
            grid[y][x].setDamagedShip();
            return "X";
        } else if (status.equalsIgnoreCase(MISS)) {
            grid[y][x].setMissedShot();
            return "-";
        } else {
            return "unknown";
        }
    }

    private int shipsAlive() {
        int count = 0;
        for (Spaceship spaceship : spaceshipList) {
            if (spaceship.getLives() > 0) {
                count++;
            }
        }
        return count;
    }

    public String getValue(int x, int y) {
        return grid[y][x].getValue();
    }

    public String toString() {
        StringBuilder status = new StringBuilder();
        for (int i = 0; i < SIZE; i++) {
            for (int y = 0; y < SIZE; y++) {
                status.append(grid[i][y].getValue());
            }
            status.append("\n");
        }
        return status.toString();
    }

}
