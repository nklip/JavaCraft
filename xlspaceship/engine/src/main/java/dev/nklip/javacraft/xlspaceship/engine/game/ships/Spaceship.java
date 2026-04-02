package dev.nklip.javacraft.xlspaceship.engine.game.ships;

import dev.nklip.javacraft.xlspaceship.engine.game.Cell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;

public abstract class Spaceship {

    protected int form; // 1 - A; 2 - B; 3 - C; 4 - D;
    @Getter
    protected int width;
    @Getter
    protected int height;

    protected Cell[][] ship;

    protected List<Cell> cells = new ArrayList<>();

    @SuppressWarnings("SuspiciousNameCombination")
    public Spaceship(int form, int concreteWidth, int concreteHeight) {
        this.form = form;

        if (form == 1 || form == 3) {
            width = concreteWidth;
            height = concreteHeight;
            ship = new Cell[concreteHeight][concreteWidth];
        } else {
            // turn ship or change form
            width = concreteHeight;
            height = concreteWidth;
            ship = new Cell[concreteWidth][concreteHeight];
        }
    }

    public void addCell(Cell cell) {
        if (cell.getValue().equals("*")) {
            cells.add(cell);
        }
    }

    public void clearCells() {
        cells.clear();
    }

    public int getHealth() {
        int health = 0;
        for (Cell cell : cells) {
            if (cell.getValue().equals("*")) {
                health++;
            }
        }
        return health;
    }

    protected abstract Cell[][] formA();
    protected abstract Cell[][] formB();
    protected abstract Cell[][] formC();
    protected abstract Cell[][] formD();

    public List<Cell> shape() {
        switch (form) {
            case 1:
                ship = formA();
                break;
            case 2:
                ship = formB();
                break;
            case 3:
                ship = formC();
                break;
            default:
                ship = formD();
        }
        List<Cell> cellList = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            cellList.addAll(Arrays.asList(ship[i]).subList(0, width));
        }
        return cellList;
    }

    public Cell[] string2cells(String value) {
        Cell[] cells = new Cell[value.length()];
        for (int i = 0; i < value.length(); i++) {
            cells[i] = new Cell(Character.toString(value.charAt(i)));
        }
        return cells;
    }

}
