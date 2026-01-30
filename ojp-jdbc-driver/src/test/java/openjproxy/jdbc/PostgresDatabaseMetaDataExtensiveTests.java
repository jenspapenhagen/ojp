package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class PostgresDatabaseMetaDataExtensiveTests {

    private static boolean isTestEnabled;
    private static Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "postgres_db_metadata_test", TestDBUtils.SqlSyntax.POSTGRES, true);
    }

    @AfterAll
    static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information (PostgreSQL-specific values)
        Assertions.assertTrue( meta.allProceduresAreCallable());
        Assertions.assertTrue( meta.allTablesAreSelectable());
        Assertions.assertTrue(meta.getURL().contains("postgresql") || meta.getURL().contains(":5432/"));
        Assertions.assertNotNull(meta.getUserName()); // PostgreSQL username
        Assertions.assertFalse( meta.isReadOnly());

        // 6–10: Null handling and database product info (PostgreSQL-specific behaviors)
        Assertions.assertTrue( meta.nullsAreSortedHigh());  // PostgreSQL behavior
        Assertions.assertFalse( meta.nullsAreSortedLow());
        Assertions.assertFalse( meta.nullsAreSortedAtStart());
        Assertions.assertFalse( meta.nullsAreSortedAtEnd()); // PostgreSQL behavior
        Assertions.assertEquals("PostgreSQL", meta.getDatabaseProductName());

        // 11–15: Version information
        Assertions.assertNotNull(meta.getDatabaseProductVersion());
        Assertions.assertEquals("PostgreSQL JDBC Driver", meta.getDriverName());
        Assertions.assertNotNull(meta.getDriverVersion());
        Assertions.assertTrue(meta.getDriverMajorVersion() >= 42); // PostgreSQL driver version
        Assertions.assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        Assertions.assertFalse( meta.usesLocalFiles());
        Assertions.assertFalse( meta.usesLocalFilePerTable());
        Assertions.assertFalse( meta.supportsMixedCaseIdentifiers());
        Assertions.assertFalse( meta.storesUpperCaseIdentifiers());
        Assertions.assertTrue( meta.storesLowerCaseIdentifiers()); // PostgreSQL stores lowercase

        // 21–25: Quoted identifiers
        Assertions.assertFalse( meta.storesMixedCaseIdentifiers());
        Assertions.assertTrue( meta.supportsMixedCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesUpperCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesLowerCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesMixedCaseQuotedIdentifiers()); // PostgreSQL behavior

        // 26–30: String handling and functions
        Assertions.assertEquals("\"", meta.getIdentifierQuoteString());
        Assertions.assertNotNull(meta.getSQLKeywords());
        Assertions.assertNotNull(meta.getNumericFunctions());
        Assertions.assertNotNull(meta.getStringFunctions());
        Assertions.assertNotNull(meta.getSystemFunctions());

        // 31–35: More functions and table operations
        Assertions.assertNotNull(meta.getTimeDateFunctions());
        Assertions.assertEquals("\\", meta.getSearchStringEscape());
        // PostgreSQL may not allow extra name characters beyond standard ones
        String extraChars = meta.getExtraNameCharacters();
        Assertions.assertNotNull(extraChars); // Accept any non-null value
        Assertions.assertTrue( meta.supportsAlterTableWithAddColumn());
        Assertions.assertTrue( meta.supportsAlterTableWithDropColumn());

        // 36–40: Query features
        Assertions.assertTrue( meta.supportsColumnAliasing());
        Assertions.assertTrue( meta.nullPlusNonNullIsNull());
        Assertions.assertFalse( meta.supportsConvert()); // PostgreSQL behavior differs from H2
        Assertions.assertFalse( meta.supportsConvert(Types.INTEGER, Types.VARCHAR)); // PostgreSQL behavior
        Assertions.assertTrue( meta.supportsTableCorrelationNames());

        // 41–45: More query features
        Assertions.assertFalse( meta.supportsDifferentTableCorrelationNames());
        Assertions.assertTrue( meta.supportsExpressionsInOrderBy());
        Assertions.assertTrue( meta.supportsOrderByUnrelated());
        Assertions.assertTrue( meta.supportsGroupBy());
        Assertions.assertTrue( meta.supportsGroupByUnrelated());

        // 46–50: Advanced query features
        Assertions.assertTrue( meta.supportsGroupByBeyondSelect());
        Assertions.assertTrue( meta.supportsLikeEscapeClause());
        Assertions.assertTrue( meta.supportsMultipleResultSets()); // PostgreSQL supports multiple result sets
        Assertions.assertTrue( meta.supportsMultipleTransactions());
        Assertions.assertTrue( meta.supportsNonNullableColumns());

        // 51–55: SQL grammar support
        Assertions.assertTrue( meta.supportsMinimumSQLGrammar());
        Assertions.assertFalse( meta.supportsCoreSQLGrammar());
        Assertions.assertFalse( meta.supportsExtendedSQLGrammar());
        Assertions.assertTrue( meta.supportsANSI92EntryLevelSQL());
        Assertions.assertFalse( meta.supportsANSI92IntermediateSQL());

        // 56–60: Advanced SQL and joins
        Assertions.assertFalse( meta.supportsANSI92FullSQL());
        Assertions.assertTrue( meta.supportsIntegrityEnhancementFacility());
        Assertions.assertTrue( meta.supportsOuterJoins());
        Assertions.assertTrue( meta.supportsFullOuterJoins());
        Assertions.assertTrue( meta.supportsLimitedOuterJoins());

        // 61–65: Schema and catalog terminology
        Assertions.assertEquals("schema", meta.getSchemaTerm());
        Assertions.assertEquals("function", meta.getProcedureTerm()); // PostgreSQL uses functions
        Assertions.assertEquals("database", meta.getCatalogTerm());
        Assertions.assertTrue( meta.isCatalogAtStart());
        Assertions.assertEquals(".", meta.getCatalogSeparator());

        // 66–75: Schema and catalog support
        Assertions.assertTrue( meta.supportsSchemasInDataManipulation());
        Assertions.assertTrue( meta.supportsSchemasInProcedureCalls());
        Assertions.assertTrue( meta.supportsSchemasInTableDefinitions());
        Assertions.assertTrue( meta.supportsSchemasInIndexDefinitions());
        Assertions.assertTrue( meta.supportsSchemasInPrivilegeDefinitions());
        Assertions.assertFalse( meta.supportsCatalogsInDataManipulation());
        Assertions.assertFalse( meta.supportsCatalogsInProcedureCalls());
        Assertions.assertFalse( meta.supportsCatalogsInTableDefinitions());
        Assertions.assertFalse( meta.supportsCatalogsInIndexDefinitions());
        Assertions.assertFalse( meta.supportsCatalogsInPrivilegeDefinitions());

        // 76–90: Cursor and subquery support
        Assertions.assertFalse( meta.supportsPositionedDelete());
        Assertions.assertFalse( meta.supportsPositionedUpdate());
        Assertions.assertTrue( meta.supportsSelectForUpdate());
        Assertions.assertTrue( meta.supportsStoredProcedures());
        Assertions.assertTrue( meta.supportsSubqueriesInComparisons());
        Assertions.assertTrue( meta.supportsSubqueriesInExists());
        Assertions.assertTrue( meta.supportsSubqueriesInIns());
        Assertions.assertTrue( meta.supportsSubqueriesInQuantifieds());
        Assertions.assertTrue( meta.supportsCorrelatedSubqueries());
        Assertions.assertTrue( meta.supportsUnion());
        Assertions.assertTrue( meta.supportsUnionAll());
        Assertions.assertFalse( meta.supportsOpenCursorsAcrossCommit());
        Assertions.assertFalse( meta.supportsOpenCursorsAcrossRollback());
        Assertions.assertTrue( meta.supportsOpenStatementsAcrossCommit());
        Assertions.assertTrue( meta.supportsOpenStatementsAcrossRollback());

        // 91–111: Limits (PostgreSQL typically has no limits or very high limits)
        Assertions.assertEquals(0, meta.getMaxBinaryLiteralLength());
        Assertions.assertEquals(0, meta.getMaxCharLiteralLength());
        Assertions.assertEquals(63, meta.getMaxColumnNameLength()); // PostgreSQL identifier limit
        Assertions.assertEquals(0, meta.getMaxColumnsInGroupBy());
        Assertions.assertEquals(32, meta.getMaxColumnsInIndex()); // PostgreSQL index column limit
        Assertions.assertEquals(0, meta.getMaxColumnsInOrderBy());
        Assertions.assertEquals(0, meta.getMaxColumnsInSelect()); // PostgreSQL column limit
        Assertions.assertEquals(1600, meta.getMaxColumnsInTable());
        Assertions.assertEquals(8192, meta.getMaxConnections());
        Assertions.assertEquals(63, meta.getMaxCursorNameLength());
        Assertions.assertEquals(0, meta.getMaxIndexLength());
        Assertions.assertEquals(63, meta.getMaxSchemaNameLength());
        Assertions.assertEquals(63, meta.getMaxProcedureNameLength());
        Assertions.assertEquals(63, meta.getMaxCatalogNameLength());
        Assertions.assertEquals(1073741824, meta.getMaxRowSize());
        Assertions.assertFalse( meta.doesMaxRowSizeIncludeBlobs());
        Assertions.assertEquals(0, meta.getMaxStatementLength());
        Assertions.assertEquals(0, meta.getMaxStatements());
        Assertions.assertEquals(63, meta.getMaxTableNameLength());
        Assertions.assertEquals(0, meta.getMaxTablesInSelect());
        Assertions.assertEquals(63, meta.getMaxUserNameLength());
        Assertions.assertEquals(Connection.TRANSACTION_READ_COMMITTED, meta.getDefaultTransactionIsolation());

        // 112–118: Transaction support
        Assertions.assertTrue( meta.supportsTransactions());
        Assertions.assertTrue( meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        Assertions.assertTrue( meta.supportsDataDefinitionAndDataManipulationTransactions());
        Assertions.assertFalse( meta.supportsDataManipulationTransactionsOnly());
        Assertions.assertFalse( meta.dataDefinitionCausesTransactionCommit());
        Assertions.assertFalse( meta.dataDefinitionIgnoredInTransactions());

        // 119–174: ResultSets for metadata queries
        try (ResultSet rs = meta.getProcedures(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getProcedureColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTables(null, null, null, new String[]{"TABLE"})) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getSchemas()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCatalogs()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTableTypes()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumnPrivileges(null, null, "postgres_db_metadata_test", null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTablePrivileges(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getBestRowIdentifier(null, null, "postgres_db_metadata_test", DatabaseMetaData.bestRowSession, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getVersionColumns(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPrimaryKeys(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getImportedKeys(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getExportedKeys(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCrossReference(null, null, "postgres_db_metadata_test", null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTypeInfo()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, "postgres_db_metadata_test", false, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getUDTs(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        Assertions.assertNotNull(meta.getConnection());
        Assertions.assertTrue( meta.supportsSavepoints());
        Assertions.assertFalse( meta.supportsNamedParameters());
        Assertions.assertFalse( meta.supportsMultipleOpenResults());
        Assertions.assertTrue( meta.supportsGetGeneratedKeys());

        Assertions.assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, meta.getResultSetHoldability());
        Assertions.assertTrue(meta.getDatabaseMajorVersion() >= 10); // Modern PostgreSQL
        Assertions.assertTrue(meta.getDatabaseMinorVersion() >= 0);
        Assertions.assertEquals(4, meta.getJDBCMajorVersion());
        Assertions.assertTrue(meta.getJDBCMinorVersion() >= 2);
        Assertions.assertEquals(DatabaseMetaData.sqlStateSQL, meta.getSQLStateType());
        Assertions.assertTrue( meta.locatorsUpdateCopy());
        Assertions.assertFalse( meta.supportsStatementPooling());

        try (ResultSet rs = meta.getSchemas(null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        Assertions.assertTrue( meta.supportsStoredFunctionsUsingCallSyntax());
        Assertions.assertFalse( meta.autoCommitFailureClosesAllResultSets());
        try (ResultSet rs = meta.getClientInfoProperties()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctions(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctionColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        Assertions.assertTrue( meta.generatedKeyAlwaysReturned());
        Assertions.assertEquals(0, meta.getMaxLogicalLobSize());
        Assertions.assertTrue( meta.supportsRefCursors());
        Assertions.assertFalse( meta.supportsSharding());

        // 175–177: ResultSet/Concurrency methods
        Assertions.assertTrue( meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertTrue( meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        Assertions.assertTrue( meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertTrue( meta.ownDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertTrue( meta.ownInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.othersUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.othersDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.othersInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.updatesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.deletesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.insertsAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertTrue( meta.supportsBatchUpdates());

        // These tests has to be at the end as per when using hikariCP the connection will be marked as broken after this operations.
        Assertions.assertTrue( meta.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        Assertions.assertThrows(SQLException.class, () -> meta.getSuperTypes(null, null, null));
        Assertions.assertThrows(SQLException.class, () -> meta.getSuperTables(null, null, null));
        Assertions.assertThrows(SQLException.class, () -> meta.getAttributes(null, null, null, null));
        Assertions.assertThrows(SQLException.class, () -> meta.getRowIdLifetime());
        Assertions.assertThrows(SQLException.class, () -> meta.getPseudoColumns(null, null, null, null));

    }
}