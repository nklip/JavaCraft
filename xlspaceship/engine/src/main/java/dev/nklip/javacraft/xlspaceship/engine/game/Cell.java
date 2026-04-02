package dev.nklip.javacraft.xlspaceship.engine.game;

import lombok.Getter;

/**
 * *: A quadrant taken by part of a ship which has not been hit yet.
 * -: A quadrant taken that contains a missed shot.
 * X: A quadrant taken by part of a ship which was hit by a shot.
 * .: A empty of unknown quadrant.
 */
@Getter
public class Cell {

    private static final String ALL_VALUES = "*-X.";

    private String value;

    public Cell() {
        setPartOfShip(); // default value
    }

    public Cell(String value) {
        if (!ALL_VALUES.contains(value)) {
            throw new RuntimeException();
        }
        this.value = value;
    }

    public void setPartOfShip() {
        this.value = "*";
    }

    public void setMissedShot() {
        this.value = "-";
    }

    public void setDamagedShip() {
        this.value = "X";
    }

    public void setUnknown() {
        this.value = ".";
    }

    @Override
    public String toString() {
        return value;
    }

}
