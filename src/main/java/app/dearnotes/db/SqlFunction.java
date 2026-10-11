package app.dearnotes.db;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface SqlFunction<T> {
    T apply(Connection conn) throws SQLException;
}
