package dev.nklip.javacraft.xlspaceship.impl.service;

import dev.nklip.javacraft.xlspaceship.impl.game.Grid;
import dev.nklip.javacraft.xlspaceship.impl.game.ships.Spaceship;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GridServicesTest {

    private GridServices gridServices;

    @BeforeEach
    public void before() {
        gridServices = new GridServices(new RandomServices());
    }

    // TODO: can fail - should be fixed
    @Test
    public void testNewGameCase01() {
        Grid grid = gridServices.newRandomGrid();
        System.out.println(grid.toString());

        Assertions.assertNotNull(grid.toString());
        Assertions.assertEquals(5, grid.getSpaceshipList().size());

        int count = 0;
        for (Spaceship spaceship : grid.getSpaceshipList()) {
            count += spaceship.getLives();
        }
        Assertions.assertEquals(41, count);
    }

    @Test
    public void testNewGameCase02() {
        for (int t = 0; t < 50000; t++) {
            Grid grid = gridServices.newRandomGrid();
            String board = grid.toString();
            int count = 0;
            for (int i = 0; i < board.length(); i++) {
                if (Character.toString(board.charAt(i)).equals("*")) {
                    count++;
                }
            }
            if (count != 41) {
                System.out.println(grid);
            }
            Assertions.assertEquals(41, count);
        }
    }
}
