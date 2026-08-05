package com.example.nodecontrol.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaCompatibilityMigrationTest {

    @Test
    void addsMissingSourcePortColumnForMySqlDatabase() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet table = resultSet(true);
        ResultSet column = resultSet(false);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getTables(null, null, "residential_allocations", new String[]{"TABLE"}))
                .thenReturn(table);
        when(metadata.getColumns(null, null, "residential_allocations", "proxy_source_port"))
                .thenReturn(column);
        ResultSet upperCaseColumn = resultSet(false);
        when(metadata.getColumns(null, null, "RESIDENTIAL_ALLOCATIONS", "PROXY_SOURCE_PORT"))
                .thenReturn(upperCaseColumn);

        new SchemaCompatibilityMigration(dataSource, jdbcTemplate).migrate();

        verify(jdbcTemplate).execute(contains("ADD COLUMN proxy_source_port"));
    }

    @Test
    void removesLegacyGlobalUserIdIndex() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet table = resultSet(true);
        ResultSet column = resultSet(true);
        ResultSet indexes = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getTables(null, null, "residential_allocations", new String[]{"TABLE"}))
                .thenReturn(table);
        when(metadata.getIndexInfo(null, null, "residential_allocations", false, false))
                .thenReturn(indexes);
        when(indexes.next()).thenReturn(true, false);
        when(indexes.getString("INDEX_NAME")).thenReturn("UK_legacy_control_user_id");
        when(indexes.getString("COLUMN_NAME")).thenReturn("control_user_id");
        when(indexes.getBoolean("NON_UNIQUE")).thenReturn(false);
        when(metadata.getColumns(null, null, "residential_allocations", "proxy_source_port"))
                .thenReturn(column);

        new SchemaCompatibilityMigration(dataSource, jdbcTemplate).migrate();

        verify(jdbcTemplate).execute(contains("DROP INDEX `UK_legacy_control_user_id`"));
    }

    @Test
    void skipsNonMySqlDatabase() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");

        new SchemaCompatibilityMigration(dataSource, jdbcTemplate).migrate();

        verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    private ResultSet resultSet(boolean hasRow) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(hasRow);
        return resultSet;
    }
}
