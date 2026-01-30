package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class OracleDatabaseMetaDataExtensiveTests {

    private static boolean isTestDisabled;
    private static Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "oracle_db_metadata_test", TestDBUtils.SqlSyntax.ORACLE, true);
    }

    @AfterAll
    static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information (Oracle-specific values)
        Assertions.assertFalse( meta.allProceduresAreCallable());
        Assertions.assertFalse( meta.allTablesAreSelectable());
        Assertions.assertTrue(meta.getURL().contains("oracle") || meta.getURL().contains(":1521/"));
        Assertions.assertNotNull(meta.getUserName()); // Oracle username
        Assertions.assertFalse( meta.isReadOnly());

        // 6–10: Null handling and database product info (Oracle-specific behaviors)
        Assertions.assertTrue( meta.nullsAreSortedHigh());  // Oracle behavior
        Assertions.assertFalse( meta.nullsAreSortedLow());
        Assertions.assertFalse( meta.nullsAreSortedAtStart());
        Assertions.assertFalse( meta.nullsAreSortedAtEnd()); // Oracle behavior
        Assertions.assertEquals("Oracle", meta.getDatabaseProductName());

        // 11–15: Version information
        Assertions.assertNotNull(meta.getDatabaseProductVersion());
        Assertions.assertEquals("Oracle JDBC driver", meta.getDriverName());
        Assertions.assertNotNull(meta.getDriverVersion());
        Assertions.assertTrue(meta.getDriverMajorVersion() >= 21); // Oracle driver version
        Assertions.assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        Assertions.assertFalse( meta.usesLocalFiles());
        Assertions.assertFalse( meta.usesLocalFilePerTable());
        Assertions.assertFalse( meta.supportsMixedCaseIdentifiers());
        Assertions.assertTrue( meta.storesUpperCaseIdentifiers()); // Oracle stores uppercase
        Assertions.assertFalse( meta.storesLowerCaseIdentifiers()); // Oracle stores uppercase

        // 21–25: Quoted identifiers
        Assertions.assertFalse( meta.storesMixedCaseIdentifiers());
        Assertions.assertTrue( meta.supportsMixedCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesUpperCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesLowerCaseQuotedIdentifiers());
        Assertions.assertTrue( meta.storesMixedCaseQuotedIdentifiers()); // Oracle behavior

        // 26–30: String handling and functions
        Assertions.assertEquals("\"", meta.getIdentifierQuoteString());
        Assertions.assertNotNull(meta.getSQLKeywords());
        Assertions.assertNotNull(meta.getNumericFunctions());
        Assertions.assertNotNull(meta.getStringFunctions());
        Assertions.assertNotNull(meta.getSystemFunctions());

        // 31–35: More functions and table operations
        Assertions.assertNotNull(meta.getTimeDateFunctions());
        Assertions.assertEquals("/", meta.getSearchStringEscape());
        // Oracle may have extra name characters
        String extraChars = meta.getExtraNameCharacters();
        Assertions.assertNotNull(extraChars); // Accept any non-null value
        Assertions.assertTrue( meta.supportsAlterTableWithAddColumn());
        Assertions.assertFalse( meta.supportsAlterTableWithDropColumn());

        // 36–40: Query features
        Assertions.assertTrue( meta.supportsColumnAliasing());
        Assertions.assertTrue( meta.nullPlusNonNullIsNull());
        Assertions.assertFalse( meta.supportsConvert()); // Oracle behavior differs from PostgreSQL
        Assertions.assertFalse( meta.supportsConvert(Types.INTEGER, Types.VARCHAR)); // Oracle behavior
        Assertions.assertTrue( meta.supportsTableCorrelationNames());

        // 41–45: More query features
        Assertions.assertTrue( meta.supportsDifferentTableCorrelationNames());
        Assertions.assertTrue( meta.supportsExpressionsInOrderBy());
        Assertions.assertTrue( meta.supportsOrderByUnrelated());
        Assertions.assertTrue( meta.supportsGroupBy());
        Assertions.assertTrue( meta.supportsGroupByUnrelated());

        // 46–50: Advanced query features
        Assertions.assertTrue( meta.supportsGroupByBeyondSelect());
        Assertions.assertTrue( meta.supportsLikeEscapeClause());
        Assertions.assertFalse( meta.supportsMultipleResultSets()); // Oracle supports multiple result sets
        Assertions.assertTrue( meta.supportsMultipleTransactions());
        Assertions.assertTrue( meta.supportsNonNullableColumns());

        // 51–55: SQL grammar support
        Assertions.assertTrue( meta.supportsMinimumSQLGrammar());
        Assertions.assertTrue( meta.supportsCoreSQLGrammar());
        Assertions.assertTrue( meta.supportsExtendedSQLGrammar());
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
        Assertions.assertEquals("procedure", meta.getProcedureTerm()); // Oracle uses procedures
        Assertions.assertEquals("", meta.getCatalogTerm());
        Assertions.assertFalse( meta.isCatalogAtStart());
        Assertions.assertEquals("", meta.getCatalogSeparator());

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
        Assertions.assertFalse( meta.supportsOpenStatementsAcrossCommit());
        Assertions.assertFalse( meta.supportsOpenStatementsAcrossRollback());

        // 91–111: Limits (Oracle-specific limits)
        Assertions.assertEquals(1000, meta.getMaxBinaryLiteralLength());
        Assertions.assertEquals(2000, meta.getMaxCharLiteralLength()); // Oracle VARCHAR2 limit
        Assertions.assertEquals(128, meta.getMaxColumnNameLength()); // Oracle identifier limit
        Assertions.assertEquals(0, meta.getMaxColumnsInGroupBy());
        Assertions.assertEquals(32, meta.getMaxColumnsInIndex()); // Oracle index column limit
        Assertions.assertEquals(0, meta.getMaxColumnsInOrderBy());
        Assertions.assertEquals(0, meta.getMaxColumnsInSelect()); // Oracle column limit
        Assertions.assertEquals(1000, meta.getMaxColumnsInTable());
        Assertions.assertEquals(0, meta.getMaxConnections());
        Assertions.assertEquals(0, meta.getMaxCursorNameLength());
        Assertions.assertEquals(0, meta.getMaxIndexLength());
        Assertions.assertEquals(128, meta.getMaxSchemaNameLength());
        Assertions.assertEquals(128, meta.getMaxProcedureNameLength());
        Assertions.assertEquals(0, meta.getMaxCatalogNameLength());
        Assertions.assertEquals(0, meta.getMaxRowSize());
        Assertions.assertTrue( meta.doesMaxRowSizeIncludeBlobs());
        Assertions.assertEquals(65535, meta.getMaxStatementLength());
        Assertions.assertEquals(0, meta.getMaxStatements());
        Assertions.assertEquals(128, meta.getMaxTableNameLength());
        Assertions.assertEquals(0, meta.getMaxTablesInSelect());
        Assertions.assertEquals(128, meta.getMaxUserNameLength());
        Assertions.assertEquals(Connection.TRANSACTION_READ_COMMITTED, meta.getDefaultTransactionIsolation());

        // 112–118: Transaction support
        Assertions.assertTrue( meta.supportsTransactions());
        Assertions.assertTrue( meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        Assertions.assertTrue( meta.supportsDataDefinitionAndDataManipulationTransactions());
        Assertions.assertTrue( meta.supportsDataManipulationTransactionsOnly());
        Assertions.assertTrue( meta.dataDefinitionCausesTransactionCommit());
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
        try (ResultSet rs = meta.getColumnPrivileges(null, null, "ORACLE_DB_METADATA_TEST", null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTablePrivileges(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getBestRowIdentifier(null, null, "ORACLE_DB_METADATA_TEST", DatabaseMetaData.bestRowSession, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getVersionColumns(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPrimaryKeys(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getImportedKeys(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getExportedKeys(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCrossReference(null, null, "ORACLE_DB_METADATA_TEST", null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTypeInfo()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, "ORACLE_DB_METADATA_TEST", false, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getUDTs(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        Assertions.assertNotNull(meta.getConnection());
        Assertions.assertTrue( meta.supportsSavepoints());
        Assertions.assertTrue( meta.supportsNamedParameters());
        Assertions.assertFalse( meta.supportsMultipleOpenResults());
        Assertions.assertTrue( meta.supportsGetGeneratedKeys());

        Assertions.assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, meta.getResultSetHoldability());
        Assertions.assertTrue(meta.getDatabaseMajorVersion() >= 18); // Modern Oracle
        Assertions.assertTrue(meta.getDatabaseMinorVersion() >= 0);
        Assertions.assertEquals(4, meta.getJDBCMajorVersion());
        Assertions.assertTrue(meta.getJDBCMinorVersion() >= 2);
        Assertions.assertEquals(DatabaseMetaData.functionColumnUnknown, meta.getSQLStateType());
        Assertions.assertTrue( meta.locatorsUpdateCopy());
        Assertions.assertTrue( meta.supportsStatementPooling());

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
        Assertions.assertFalse( meta.generatedKeyAlwaysReturned());
        Assertions.assertTrue( meta.supportsRefCursors());
        Assertions.assertTrue( meta.supportsSharding());

        // 175–177: ResultSet/Concurrency methods
        Assertions.assertTrue( meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertTrue( meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        Assertions.assertFalse( meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.ownDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.ownInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
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

        Assertions.assertEquals(RowIdLifetime.ROWID_VALID_FOREVER, meta.getRowIdLifetime());
        try (ResultSet rs = meta.getPseudoColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
    }
}