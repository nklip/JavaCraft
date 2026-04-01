package dev.nklip.javacraft.xlspaceship.impl.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.xlspaceship.impl.game.Grid;
import dev.nklip.javacraft.xlspaceship.impl.game.GridStatus;
import dev.nklip.javacraft.xlspaceship.impl.game.ships.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GridServices {

    private final RandomServices randomServices;

    public Grid newUnknownGrid() {
        return new Grid();
    }

    public Grid newRandomGrid() {
        Grid grid = new Grid();

        List<Spaceship> spaceshipList = new ArrayList<>();
        spaceshipList.add(new BClass(randomServices.generateForm())); // 10 lives
        spaceshipList.add(new Winger(randomServices.generateForm())); // 9 lives
        spaceshipList.add(new SClass(randomServices.generateForm())); // 8 lives
        spaceshipList.add(new AClass(randomServices.generateForm())); // 8 lives
        spaceshipList.add(new Angle(randomServices.generateForm()));  // 6 lives

        while (true) {
            boolean isSet = false;
            for (Spaceship spaceship : spaceshipList) {
                isSet = grid.setSpaceship(randomServices, spaceship);
                if (!isSet) {
                    grid.clear();
                    break;
                }
            }
            if (isSet) {
                break;
            }
        }

        return grid;
    }

    public String getGridInHtml(GridStatus gridStatus, boolean isClickable) {
        StringBuilder html = new StringBuilder();
        List<String> rows = gridStatus.getBoard();
        html.append("<table>");
        int y = 0;
        for (String row : rows) {
            html.append("<tr class=\"row\">");
            char[] chars = row.toCharArray();
            int x = 0;
            for (Character ch : chars) {
                String x16 = Integer.toString(x, 16);
                String y16 = Integer.toString(y, 16);
                String idValue = String.format("%sx%s", x16, y16);
                String value = Character.toString(ch);

                square(html, idValue, value, isClickable);

                x++;
            }
            html.append("</tr>");
            y++;
        }
        html.append("</table>");
        return html.toString();
    }

    private void square(StringBuilder html, String idValue, String value, boolean isClickable) {
        String temp;
        if (value.equalsIgnoreCase("*")) {
            temp = "ship";
        } else if (value.equalsIgnoreCase("x")) {
            temp = "sunk";
        } else if (value.equalsIgnoreCase("-")) {
            temp = "shot";
        } else {
            temp = "empty";
        }
        if (isClickable) {
            html.append(String.format("<td id=\"%s\" shot=\"%s\" onclick=\"addShot(this);\" class=\"%s\"/>", idValue, idValue, temp));
        } else {
            html.append(String.format("<td class=\"%s\"/>", temp));
        }
    }

}
