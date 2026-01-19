package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for SQL session affinity feature.
 * Tests that temporary tables, session variables, and prepared statements
 * work correctly across multiple requests by ensuring session affinity.
 * 
 * This test verifies the SqlSessionAffinityDetector implementation that
 * automatically creates sessions for session-specific SQL operations.
 */
@Slf4j
public class SessionAffinityIntegrationTest {

    private static boolean isH2TestEnabled;
    private static boolean isPostgresTestEnabled;
    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private static boolean isOracleTestEnabled;
    private static boolean isSqlServerTestEnabled;
    private static boolean isDb2TestEnabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        isPostgresTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
        isOracleTestEnabled = Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
        isSqlServerTestEnabled = Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
        isDb2TestEnabled = Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    /**
     * Tests that temporary tables work across multiple SQL statements.
     * This verifies that CREATE TEMPORARY TABLE triggers session affinity
     * and subsequent operations use the same session/connection.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv")
    public void testTemporaryTableSessionAffinity(String driverClass, String url, String user, String pwd, boolean isXA) 
            throws SQLException, ClassNotFoundException {
        // Skip tests based on enabled flags
        skipTestIfDisabled(url);

        log.info("Testing temporary table session affinity for url: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Drop temp table if it exists (cleanup from previous run)
            try {
                if (url.toLowerCase().contains("sqlserver")) {
                    stmt.execute("IF OBJECT_ID('tempdb..#temp_session_test') IS NOT NULL DROP TABLE #temp_session_test");
                } else if (url.toLowerCase().contains("oracle")) {
                    // Oracle global temp tables are permanent, just truncate
                    try {
                        stmt.execute("TRUNCATE TABLE temp_session_test");
                    } catch (SQLException e) {
                        // Table doesn't exist, will create it
                    }
                } else {
                    stmt.execute("DROP TABLE IF EXISTS temp_session_test");
                }
            } catch (SQLException e) {
                // Ignore - table might not exist
            }

            // Create temporary table (this should trigger session affinity)
            String createTableSQL = getCreateTempTableSQL(url);
            log.debug("Executing: {}", createTableSQL);
            stmt.execute(createTableSQL);

            // Insert data into temporary table (should use same session)
            log.debug("Inserting data into temporary table");
            stmt.execute(getInsertSQL(url));

            // Query temporary table (should use same session)
            log.debug("Querying temporary table");
            ResultSet rs = stmt.executeQuery(getSelectSQL(url));
            
            // Verify data was inserted and retrieved successfully
            Assert.assertTrue("Should have at least one row in temporary table", rs.next());
            Assert.assertEquals("Session data should match", 1, rs.getInt("id"));
            Assert.assertEquals("Session data should match", "test_value", rs.getString("value"));
            
            // Verify no more rows
            Assert.assertFalse("Should have only one row", rs.next());
            
            rs.close();
            
            log.info("Temporary table session affinity test passed for: {}", url);

        } finally {
            // Cleanup
            try (Statement stmt = conn.createStatement()) {
                if (url.toLowerCase().contains("sqlserver")) {
                    stmt.execute("IF OBJECT_ID('tempdb..#temp_session_test') IS NOT NULL DROP TABLE #temp_session_test");
                } else if (url.toLowerCase().contains("oracle")) {
                    try {
                        stmt.execute("TRUNCATE TABLE temp_session_test");
                    } catch (SQLException e) {
                        // Ignore
                    }
                }
            } catch (SQLException e) {
                log.warn("Error during cleanup: {}", e.getMessage());
            }
            conn.close();
        }
    }

    /**
     * Tests that session variables work across multiple SQL statements.
     * This is primarily for MySQL and PostgreSQL which support session variables.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_connections.csv")
    public void testSessionVariableAffinity(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        // Skip tests based on enabled flags
        skipTestIfDisabled(url);

        // Session variables are mainly MySQL/MariaDB feature, skip for H2
        if (url.toLowerCase().contains("_h2:")) {
            assumeFalse(true, "Skipping session variable test for H2 (not supported)");
        }

        log.info("Testing session variable affinity for url: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Set session variable (this should trigger session affinity)
            log.debug("Setting session variable");
            stmt.execute("SET @test_var = 12345");

            // Query session variable (should use same session)
            log.debug("Querying session variable");
            ResultSet rs = stmt.executeQuery("SELECT @test_var AS var_value");
            
            // Verify variable value was preserved
            Assert.assertTrue("Should return session variable value", rs.next());
            Assert.assertEquals("Session variable should be preserved", 12345, rs.getInt("var_value"));
            
            rs.close();
            
            log.info("Session variable affinity test passed for: {}", url);

        } finally {
            conn.close();
        }
    }

    /**
     * Tests session variables for PostgreSQL specifically using SET LOCAL.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    public void testPostgresSessionVariableAffinity(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        // Skip tests based on enabled flags
        skipTestIfDisabled(url);

        // Skip H2
        if (url.toLowerCase().contains("_h2:")) {
            assumeFalse(true, "Skipping PostgreSQL session variable test for H2");
        }

        log.info("Testing PostgreSQL session variable affinity for url: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try {
            conn.setAutoCommit(false);
            
            try (Statement stmt = conn.createStatement()) {
                // Set session variable using SET LOCAL (this should trigger session affinity)
                log.debug("Setting PostgreSQL session variable with SET LOCAL");
                stmt.execute("SET LOCAL work_mem = '4MB'");

                // Query the variable (should use same session)
                log.debug("Querying PostgreSQL session variable");
                ResultSet rs = stmt.executeQuery("SHOW work_mem");
                
                // Verify variable value was preserved
                Assert.assertTrue("Should return session variable value", rs.next());
                String workMem = rs.getString(1);
                Assert.assertTrue("work_mem should be 4MB", workMem.contains("4MB") || workMem.contains("4096"));
                
                rs.close();
                
                log.info("PostgreSQL session variable affinity test passed for: {}", url);
            }
            
            conn.commit();
        } finally {
            conn.close();
        }
    }

    /**
     * Tests that multiple temporary table operations work correctly.
     * This includes creating, inserting, updating, and querying temp tables.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv")
    public void testComplexTemporaryTableOperations(String driverClass, String url, String user, String pwd, boolean isXA) 
            throws SQLException, ClassNotFoundException {
        // Skip tests based on enabled flags
        skipTestIfDisabled(url);

        log.info("Testing complex temporary table operations for url: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Cleanup
            try {
                if (url.toLowerCase().contains("sqlserver")) {
                    stmt.execute("IF OBJECT_ID('tempdb..#temp_complex') IS NOT NULL DROP TABLE #temp_complex");
                } else if (url.toLowerCase().contains("oracle")) {
                    try {
                        stmt.execute("TRUNCATE TABLE temp_complex");
                    } catch (SQLException e) {
                        // Ignore
                    }
                } else {
                    stmt.execute("DROP TABLE IF EXISTS temp_complex");
                }
            } catch (SQLException e) {
                // Ignore
            }

            // Create temporary table
            String createSQL = getCreateComplexTempTableSQL(url);
            log.debug("Creating complex temp table: {}", createSQL);
            stmt.execute(createSQL);

            // Insert multiple rows
            log.debug("Inserting multiple rows");
            stmt.execute(getInsertComplexSQL(url, 1, "Alice", 100.50));
            stmt.execute(getInsertComplexSQL(url, 2, "Bob", 200.75));
            stmt.execute(getInsertComplexSQL(url, 3, "Charlie", 150.25));

            // Update a row
            log.debug("Updating a row");
            stmt.executeUpdate(getUpdateComplexSQL(url));

            // Query and verify
            log.debug("Querying temp table");
            ResultSet rs = stmt.executeQuery(getSelectComplexSQL(url));
            
            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
                int id = rs.getInt("id");
                String name = rs.getString("name");
                double amount = rs.getDouble("amount");
                
                log.debug("Row {}: id={}, name={}, amount={}", rowCount, id, name, amount);
                
                if (id == 1) {
                    Assert.assertEquals("Alice", name);
                    Assert.assertEquals(100.50, amount, 0.01);
                } else if (id == 2) {
                    Assert.assertEquals("Bob", name);
                    Assert.assertEquals(250.75, amount, 0.01); // Updated from 200.75 to 250.75
                } else if (id == 3) {
                    Assert.assertEquals("Charlie", name);
                    Assert.assertEquals(150.25, amount, 0.01);
                }
            }
            
            Assert.assertEquals("Should have 3 rows", 3, rowCount);
            rs.close();
            
            log.info("Complex temporary table operations test passed for: {}", url);

        } finally {
            // Cleanup
            try (Statement stmt = conn.createStatement()) {
                if (url.toLowerCase().contains("sqlserver")) {
                    stmt.execute("IF OBJECT_ID('tempdb..#temp_complex') IS NOT NULL DROP TABLE #temp_complex");
                } else if (url.toLowerCase().contains("oracle")) {
                    try {
                        stmt.execute("TRUNCATE TABLE temp_complex");
                    } catch (SQLException e) {
                        // Ignore
                    }
                }
            } catch (SQLException e) {
                log.warn("Error during cleanup: {}", e.getMessage());
            }
            conn.close();
        }
    }

    /**
     * Tests that temp table created outside transaction persists within same session.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_mysql_mariadb_connections.csv")
    public void testTemporaryTablePersistenceAcrossTransactions(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        // Skip tests based on enabled flags
        skipTestIfDisabled(url);

        log.info("Testing temporary table persistence across transactions for url: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Cleanup
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_persist");
            } catch (SQLException e) {
                // Ignore
            }

            // Create temp table
            String createSQL;
            if (url.toLowerCase().contains("_h2:")) {
                createSQL = "CREATE LOCAL TEMPORARY TABLE temp_persist (id INT, data VARCHAR(100))";
            } else if (url.toLowerCase().contains("postgresql")) {
                createSQL = "CREATE TEMPORARY TABLE temp_persist (id INT, data VARCHAR(100))";
            } else {
                // MySQL/MariaDB
                createSQL = "CREATE TEMPORARY TABLE temp_persist (id INT, data VARCHAR(100))";
            }
            stmt.execute(createSQL);

            // Start transaction and insert
            conn.setAutoCommit(false);
            stmt.execute("INSERT INTO temp_persist VALUES (1, 'in_transaction')");
            conn.commit();

            // Start another transaction and query (should still see the temp table)
            conn.setAutoCommit(false);
            ResultSet rs = stmt.executeQuery("SELECT * FROM temp_persist WHERE id = 1");
            Assert.assertTrue("Should find row inserted in previous transaction", rs.next());
            Assert.assertEquals("Data should match", "in_transaction", rs.getString("data"));
            rs.close();
            conn.commit();

            log.info("Temporary table persistence across transactions test passed for: {}", url);

        } finally {
            conn.close();
        }
    }

    // Helper methods

    private void skipTestIfDisabled(String url) {
        if (url.toLowerCase().contains("_h2:") && !isH2TestEnabled) {
            assumeFalse(true, "Skipping H2 tests");
        }
        if (url.toLowerCase().contains("postgresql") && !isPostgresTestEnabled) {
            assumeFalse(true, "Skipping PostgreSQL tests");
        }
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }
        if (url.toLowerCase().contains("oracle") && !isOracleTestEnabled) {
            assumeFalse(true, "Skipping Oracle tests");
        }
        if (url.toLowerCase().contains("sqlserver") && !isSqlServerTestEnabled) {
            assumeFalse(true, "Skipping SQL Server tests");
        }
        if (url.toLowerCase().contains("db2") && !isDb2TestEnabled) {
            assumeFalse(true, "Skipping DB2 tests");
        }
    }

    private String getCreateTempTableSQL(String url) {
        if (url.toLowerCase().contains("_h2:")) {
            return "CREATE LOCAL TEMPORARY TABLE temp_session_test (id INT, value VARCHAR(100))";
        } else if (url.toLowerCase().contains("postgresql")) {
            return "CREATE TEMPORARY TABLE temp_session_test (id INT, value VARCHAR(100))";
        } else if (url.toLowerCase().contains("mysql") || url.toLowerCase().contains("mariadb")) {
            return "CREATE TEMPORARY TABLE temp_session_test (id INT, value VARCHAR(100))";
        } else if (url.toLowerCase().contains("oracle")) {
            // Oracle global temp tables are permanent but data is session-specific
            return "CREATE GLOBAL TEMPORARY TABLE temp_session_test (id INT, value VARCHAR(100)) ON COMMIT PRESERVE ROWS";
        } else if (url.toLowerCase().contains("sqlserver")) {
            // SQL Server local temp table
            return "CREATE TABLE #temp_session_test (id INT, value VARCHAR(100))";
        } else if (url.toLowerCase().contains("db2")) {
            return "DECLARE GLOBAL TEMPORARY TABLE temp_session_test (id INT, value VARCHAR(100)) ON COMMIT PRESERVE ROWS";
        }
        throw new IllegalArgumentException("Unsupported database URL: " + url);
    }

    private String getInsertSQL(String url) {
        if (url.toLowerCase().contains("sqlserver")) {
            return "INSERT INTO #temp_session_test VALUES (1, 'test_value')";
        } else if (url.toLowerCase().contains("db2")) {
            return "INSERT INTO SESSION.temp_session_test VALUES (1, 'test_value')";
        } else {
            return "INSERT INTO temp_session_test VALUES (1, 'test_value')";
        }
    }

    private String getSelectSQL(String url) {
        if (url.toLowerCase().contains("sqlserver")) {
            return "SELECT id, value FROM #temp_session_test";
        } else if (url.toLowerCase().contains("db2")) {
            return "SELECT id, value FROM SESSION.temp_session_test";
        } else {
            return "SELECT id, value FROM temp_session_test";
        }
    }

    private String getCreateComplexTempTableSQL(String url) {
        if (url.toLowerCase().contains("_h2:")) {
            return "CREATE LOCAL TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2))";
        } else if (url.toLowerCase().contains("postgresql")) {
            return "CREATE TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2))";
        } else if (url.toLowerCase().contains("mysql") || url.toLowerCase().contains("mariadb")) {
            return "CREATE TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2))";
        } else if (url.toLowerCase().contains("oracle")) {
            return "CREATE GLOBAL TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2)) ON COMMIT PRESERVE ROWS";
        } else if (url.toLowerCase().contains("sqlserver")) {
            return "CREATE TABLE #temp_complex (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2))";
        } else if (url.toLowerCase().contains("db2")) {
            return "DECLARE GLOBAL TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2)) ON COMMIT PRESERVE ROWS";
        }
        throw new IllegalArgumentException("Unsupported database URL: " + url);
    }

    private String getInsertComplexSQL(String url, int id, String name, double amount) {
        String tableName;
        if (url.toLowerCase().contains("sqlserver")) {
            tableName = "#temp_complex";
        } else if (url.toLowerCase().contains("db2")) {
            tableName = "SESSION.temp_complex";
        } else {
            tableName = "temp_complex";
        }
        return String.format("INSERT INTO %s VALUES (%d, '%s', %.2f)", tableName, id, name, amount);
    }

    private String getUpdateComplexSQL(String url) {
        String tableName;
        if (url.toLowerCase().contains("sqlserver")) {
            tableName = "#temp_complex";
        } else if (url.toLowerCase().contains("db2")) {
            tableName = "SESSION.temp_complex";
        } else {
            tableName = "temp_complex";
        }
        return String.format("UPDATE %s SET amount = amount + 50.00 WHERE id = 2", tableName);
    }

    private String getSelectComplexSQL(String url) {
        String tableName;
        if (url.toLowerCase().contains("sqlserver")) {
            tableName = "#temp_complex";
        } else if (url.toLowerCase().contains("db2")) {
            tableName = "SESSION.temp_complex";
        } else {
            tableName = "temp_complex";
        }
        return String.format("SELECT id, name, amount FROM %s ORDER BY id", tableName);
    }
}
