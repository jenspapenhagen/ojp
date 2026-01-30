package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import openjproxy.jdbc.testutil.SQLServerConnectionProvider;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledIf("openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerDatabaseMetaDataExtensiveTests {

    private static boolean isTestDisabled;
    private static Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "sqlserver_db_metadata_test", TestDBUtils.SqlSyntax.SQLSERVER, true);
    }

    @AfterAll
    static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information (SQL Server-specific values)
        Assertions.assertTrue( meta.allProceduresAreCallable());
        Assertions.assertTrue( meta.allTablesAreSelectable());
        Assertions.assertTrue(meta.getURL().contains("sqlserver") || meta.getURL().contains(":1433"));
        Assertions.assertNotNull(meta.getUserName()); // SQL Server username
        Assertions.assertFalse( meta.isReadOnly());

        // 6–10: Null handling and database product info (SQL Server-specific behaviors)
        Assertions.assertFalse( meta.nullsAreSortedHigh());  // SQL Server behavior
        Assertions.assertTrue( meta.nullsAreSortedLow());   // SQL Server sorts nulls low
        Assertions.assertFalse( meta.nullsAreSortedAtStart());
        Assertions.assertFalse( meta.nullsAreSortedAtEnd());
        Assertions.assertTrue(meta.getDatabaseProductName().toLowerCase().contains("microsoft"));

        // 11–15: Version information
        Assertions.assertNotNull(meta.getDatabaseProductVersion());
        Assertions.assertTrue(meta.getDriverName().toLowerCase().contains("microsoft"));
        Assertions.assertNotNull(meta.getDriverVersion());
        Assertions.assertTrue(meta.getDriverMajorVersion() >= 12); // SQL Server driver version
        Assertions.assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        Assertions.assertFalse( meta.usesLocalFiles());
        Assertions.assertFalse( meta.usesLocalFilePerTable());
        Assertions.assertTrue( meta.supportsMixedCaseIdentifiers());
        Assertions.assertFalse( meta.storesUpperCaseIdentifiers()); // SQL Server doesn't force uppercase
        Assertions.assertFalse( meta.storesLowerCaseIdentifiers()); // SQL Server doesn't force lowercase

        // 21–25: Quoted identifiers
        Assertions.assertTrue( meta.storesMixedCaseIdentifiers()); // SQL Server preserves case
        Assertions.assertTrue( meta.supportsMixedCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesUpperCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesLowerCaseQuotedIdentifiers());
        Assertions.assertTrue( meta.storesMixedCaseQuotedIdentifiers()); // SQL Server behavior

        // 26–30: String handling and functions
        Assertions.assertTrue(meta.getIdentifierQuoteString().equals("[") || meta.getIdentifierQuoteString().equals("\""));
        Assertions.assertNotNull(meta.getSQLKeywords());
        Assertions.assertNotNull(meta.getNumericFunctions());
        Assertions.assertNotNull(meta.getStringFunctions());
        Assertions.assertNotNull(meta.getSystemFunctions());

        // 31–35: Date/time functions and search escape characters
        Assertions.assertNotNull(meta.getTimeDateFunctions());
        Assertions.assertEquals("\\", meta.getSearchStringEscape()); // SQL Server escape for LIKE
        Assertions.assertNotNull(meta.getExtraNameCharacters());
        Assertions.assertTrue( meta.supportsAlterTableWithAddColumn());
        Assertions.assertTrue( meta.supportsAlterTableWithDropColumn());

        // 36–40: Column operations and table correlation names
        Assertions.assertTrue( meta.supportsColumnAliasing());
        Assertions.assertTrue( meta.nullPlusNonNullIsNull());  // SQL Server: NULL + 'text' = NULL
        Assertions.assertTrue( meta.supportsConvert());
        Assertions.assertTrue( meta.supportsTableCorrelationNames());
        Assertions.assertFalse( meta.supportsDifferentTableCorrelationNames());

        // 41–45: Expression handling and ORDER BY
        Assertions.assertTrue( meta.supportsExpressionsInOrderBy());
        Assertions.assertTrue( meta.supportsOrderByUnrelated());
        Assertions.assertTrue( meta.supportsGroupBy());
        Assertions.assertTrue( meta.supportsGroupByUnrelated());
        Assertions.assertTrue( meta.supportsGroupByBeyondSelect());

        // 46–50: LIKE operations and escape characters
        Assertions.assertTrue( meta.supportsLikeEscapeClause());
        Assertions.assertTrue( meta.supportsMultipleResultSets()); // Depends on driver implementation
        Assertions.assertTrue( meta.supportsMultipleTransactions());
        Assertions.assertTrue( meta.supportsNonNullableColumns());
        Assertions.assertTrue( meta.supportsMinimumSQLGrammar());

        // 51–55: SQL grammar support levels
        Assertions.assertTrue( meta.supportsCoreSQLGrammar());
        Assertions.assertFalse( meta.supportsExtendedSQLGrammar());
        Assertions.assertTrue( meta.supportsANSI92EntryLevelSQL());
        Assertions.assertFalse( meta.supportsANSI92IntermediateSQL());
        Assertions.assertFalse( meta.supportsANSI92FullSQL()); // SQL Server doesn't fully support ANSI 92

        // 56–60: Outer joins and schema operations
        Assertions.assertTrue( meta.supportsOuterJoins());
        Assertions.assertTrue( meta.supportsFullOuterJoins());
        Assertions.assertTrue( meta.supportsLimitedOuterJoins());
        Assertions.assertNotNull(meta.getSchemaTerm());
        Assertions.assertNotNull(meta.getProcedureTerm());

        // 61–65: Catalog and cursor operations
        Assertions.assertNotNull(meta.getCatalogTerm());
        Assertions.assertTrue( meta.isCatalogAtStart());
        Assertions.assertEquals(".", meta.getCatalogSeparator());
        Assertions.assertTrue( meta.supportsSchemasInDataManipulation());
        Assertions.assertTrue( meta.supportsSchemasInProcedureCalls());

        // 66–70: Schema and catalog support in various contexts
        Assertions.assertTrue( meta.supportsSchemasInTableDefinitions());
        Assertions.assertTrue( meta.supportsSchemasInIndexDefinitions());
        Assertions.assertTrue( meta.supportsSchemasInPrivilegeDefinitions());
        Assertions.assertTrue( meta.supportsCatalogsInDataManipulation());
        Assertions.assertTrue( meta.supportsCatalogsInProcedureCalls());

        // 71–75: Catalog support in definitions
        Assertions.assertTrue( meta.supportsCatalogsInTableDefinitions());
        Assertions.assertTrue( meta.supportsCatalogsInIndexDefinitions());
        Assertions.assertTrue( meta.supportsCatalogsInPrivilegeDefinitions());
        Assertions.assertTrue( meta.supportsPositionedDelete());
        Assertions.assertTrue( meta.supportsPositionedUpdate());

        // 76–80: SELECT FOR UPDATE and stored procedures
        Assertions.assertFalse( meta.supportsSelectForUpdate()); // SQL Server doesn't support SELECT FOR UPDATE
        Assertions.assertTrue( meta.supportsStoredProcedures());
        Assertions.assertTrue( meta.supportsSubqueriesInComparisons());
        Assertions.assertTrue( meta.supportsSubqueriesInExists());
        Assertions.assertTrue( meta.supportsSubqueriesInIns());

        // 81–85: Subquery support and correlation names
        Assertions.assertTrue( meta.supportsSubqueriesInQuantifieds());
        Assertions.assertTrue( meta.supportsCorrelatedSubqueries());
        Assertions.assertTrue( meta.supportsUnion());
        Assertions.assertTrue( meta.supportsUnionAll());
        Assertions.assertFalse( meta.supportsOpenCursorsAcrossCommit());

        // 86–90: Cursor and statement persistence
        Assertions.assertFalse( meta.supportsOpenCursorsAcrossRollback()); // SQL Server behavior
        Assertions.assertTrue( meta.supportsOpenStatementsAcrossCommit());
        Assertions.assertTrue( meta.supportsOpenStatementsAcrossRollback());
        Assertions.assertTrue(meta.getMaxBinaryLiteralLength() == 0);
        Assertions.assertTrue(meta.getMaxCharLiteralLength() == 0);

        // 91–95: Maximum lengths and limits
        Assertions.assertTrue(meta.getMaxColumnNameLength() > 0);
        Assertions.assertTrue(meta.getMaxColumnsInGroupBy() >= 0);
        Assertions.assertTrue(meta.getMaxColumnsInIndex() >= 0);
        Assertions.assertTrue(meta.getMaxColumnsInOrderBy() >= 0);
        Assertions.assertTrue(meta.getMaxColumnsInSelect() >= 0);

        // 96–100: More maximum limits
        Assertions.assertTrue(meta.getMaxColumnsInTable() > 0);
        Assertions.assertTrue(meta.getMaxConnections() >= 0);
        Assertions.assertTrue(meta.getMaxCursorNameLength() >= 0);
        Assertions.assertTrue(meta.getMaxIndexLength() >= 0);
        Assertions.assertTrue(meta.getMaxSchemaNameLength() > 0);

        // 101–105: Procedure and statement limits
        Assertions.assertTrue(meta.getMaxProcedureNameLength() > 0);
        Assertions.assertTrue(meta.getMaxCatalogNameLength() > 0);
        Assertions.assertTrue(meta.getMaxRowSize() > 0);
        Assertions.assertFalse( meta.doesMaxRowSizeIncludeBlobs()); // SQL Server behavior
        Assertions.assertTrue(meta.getMaxStatementLength() > 0);

        // 106–110: More limits and transaction support
        Assertions.assertTrue(meta.getMaxStatements() >= 0);
        Assertions.assertTrue(meta.getMaxTableNameLength() > 0);
        Assertions.assertTrue(meta.getMaxTablesInSelect() >= 0);
        Assertions.assertTrue(meta.getMaxUserNameLength() > 0);
        Assertions.assertTrue(meta.getDefaultTransactionIsolation() >= 0);

        // 111–115: Transaction and DDL support
        Assertions.assertTrue( meta.supportsTransactions());
        Assertions.assertTrue( meta.supportsDataDefinitionAndDataManipulationTransactions());
        Assertions.assertFalse( meta.supportsDataManipulationTransactionsOnly());
        Assertions.assertFalse( meta.dataDefinitionCausesTransactionCommit()); // SQL Server behavior
        Assertions.assertFalse( meta.dataDefinitionIgnoredInTransactions());

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSpecificMetadata(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test SQL Server-specific metadata features
        Assertions.assertNotNull(meta.getDatabaseProductName());
        Assertions.assertTrue(meta.getDatabaseProductName().toLowerCase().contains("microsoft"));

        // Test SQL Server version information
        String version = meta.getDatabaseProductVersion();
        Assertions.assertNotNull(version);
        
        // Test SQL Server supports various features
        Assertions.assertTrue( meta.supportsStoredProcedures());
        Assertions.assertTrue( meta.supportsBatchUpdates());
        
        // Test SQL Server identifier handling
        Assertions.assertTrue(meta.getIdentifierQuoteString().equals("[") || 
                             meta.getIdentifierQuoteString().equals("\""));

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testGetTables(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"});
        TestDBUtils.validateAllRows(rs);
        rs.close();

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testGetColumns(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet rs = meta.getColumns(null, null, "sqlserver_db_metadata_test", "%");
        TestDBUtils.validateAllRows(rs);
        rs.close();

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }
}