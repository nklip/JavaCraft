package dev.nklip.javacraft.xlspaceship.engine.service;

import java.util.List;
import dev.nklip.javacraft.xlspaceship.engine.game.BoardStatus;
import org.springframework.stereotype.Service;

@Service
public class BoardHtmlRenderer {

    public String getBoardInHtml(BoardStatus boardStatus, boolean isClickable) {
        StringBuilder html = new StringBuilder();
        List<String> rows = boardStatus.getRows();
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
