package dev.nklip.javacraft.xlspaceship.engine.game.ships;

import dev.nklip.javacraft.xlspaceship.engine.game.Cell;

public class BClass extends Spaceship {

    private static final int WIDTH = 3;
    private static final int HEIGHT = 5;

    public BClass(int form) {
        super(form, WIDTH, HEIGHT);
    }

    @Override
    protected Cell[][] formA() {
        ship[0] = string2cells("**.");
        ship[1] = string2cells("*.*");
        ship[2] = string2cells("**.");
        ship[3] = string2cells("*.*");
        ship[4] = string2cells("**.");
        return ship;
    }
    @Override
    protected Cell[][] formB() {
        ship[0] = string2cells(".*.*.");
        ship[1] = string2cells("*.*.*");
        ship[2] = string2cells("*****");
        return ship;
    }
    @Override
    protected Cell[][] formC() {
        ship[0] = string2cells(".**");
        ship[1] = string2cells("*.*");
        ship[2] = string2cells(".**");
        ship[3] = string2cells("*.*");
        ship[4] = string2cells(".**");
        return ship;
    }
    @Override
    protected Cell[][] formD() {
        ship[0] = string2cells("*****");
        ship[1] = string2cells("*.*.*");
        ship[2] = string2cells(".*.*.");
        return ship;
    }

}
