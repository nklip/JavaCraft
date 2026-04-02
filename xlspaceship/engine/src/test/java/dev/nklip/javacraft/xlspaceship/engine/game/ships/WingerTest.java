package dev.nklip.javacraft.xlspaceship.engine.game.ships;

import dev.nklip.javacraft.xlspaceship.engine.game.Cell;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WingerTest {

    @Test
    public void testCreationCase01() {
        Winger winger = new Winger(1);

        List<Cell> shapeList = winger.shape();

        Assertions.assertEquals(15, shapeList.size());
        Assertions.assertEquals("[*, ., *, *, ., *, ., *, ., *, ., *, *, ., *]", shapeList.toString());
    }

    @Test
    public void testCreationCase02() {
        Winger winger = new Winger(2);

        List<Cell> shapeList = winger.shape();

        Assertions.assertEquals(15, shapeList.size());
        Assertions.assertEquals("[*, *, ., *, *, ., ., *, ., ., *, *, ., *, *]", shapeList.toString());
    }
}
