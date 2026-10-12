package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.StringJoiner;

/** The row notation of the feature files: commas between the columns of a row, semicolons between the rows. */
public final class SqlRows {

    private SqlRows() {
    }

    public static String render(ResultSet rows) throws SQLException {
        try (rows) {
            int columns = rows.getMetaData().getColumnCount();
            StringJoiner text = new StringJoiner(";");
            while (rows.next()) {
                StringJoiner row = new StringJoiner(",");
                for (int column = 1; column <= columns; column++) {
                    row.add(String.valueOf(rows.getString(column)));
                }
                text.add(row.toString());
            }
            return text.toString();
        }
    }

    public static String single(ResultSet rows) throws SQLException {
        try (rows) {
            if (!rows.next()) {
                throw new IllegalStateException("The query returned no row");
            }
            return rows.getString(1);
        }
    }
}
