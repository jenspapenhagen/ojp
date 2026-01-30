package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;


public class H2DatabaseMetaDataExtensiveTests {

    private static boolean isH2TestEnabled;
    private static Connection connection;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "h2_db_metadata_test", TestDBUtils.SqlSyntax.H2, true);
    }

    @AfterAll
    static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5
        Assertions.assertTrue( meta.allProceduresAreCallable());
        Assertions.assertTrue( meta.allTablesAreSelectable());
        Assertions.assertEquals("jdbc:h2:~/test", meta.getURL());
        Assertions.assertEquals(user.toUpperCase(), meta.getUserName()); // random: H2 username can be "SA" or empty, set as needed
        Assertions.assertFalse( meta.isReadOnly());

        // 6–10
        Assertions.assertFalse( meta.nullsAreSortedHigh());      // random
        Assertions.assertTrue( meta.nullsAreSortedLow());        // random
        Assertions.assertFalse( meta.nullsAreSortedAtStart());   // random
        Assertions.assertFalse( meta.nullsAreSortedAtEnd());      // random
        Assertions.assertEquals("H2", meta.getDatabaseProductName());

        // 11–15
        Assertions.assertNotNull(meta.getDatabaseProductVersion()); // random: version string e.g. "2.1.214 (2022-07-29)"
        Assertions.assertEquals("H2 JDBC Driver", meta.getDriverName());
        Assertions.assertNotNull(meta.getDriverVersion()); // random: version string
        Assertions.assertEquals(2, meta.getDriverMajorVersion()); // random: check your H2 version
        Assertions.assertEquals(3, meta.getDriverMinorVersion()); // random

        // 16–20
        Assertions.assertTrue( meta.usesLocalFiles());
        Assertions.assertFalse( meta.usesLocalFilePerTable());
        Assertions.assertFalse( meta.supportsMixedCaseIdentifiers());
        Assertions.assertTrue( meta.storesUpperCaseIdentifiers());
        Assertions.assertFalse( meta.storesLowerCaseIdentifiers());

        // 21–25
        Assertions.assertFalse( meta.storesMixedCaseIdentifiers());
        Assertions.assertTrue( meta.supportsMixedCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesUpperCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesLowerCaseQuotedIdentifiers());
        Assertions.assertFalse( meta.storesMixedCaseQuotedIdentifiers());

        // 26–30
        Assertions.assertEquals("\"", meta.getIdentifierQuoteString());
        Assertions.assertNotNull(meta.getSQLKeywords()); // random: String like "LIMIT,MINUS,..." etc
        Assertions.assertNotNull(meta.getNumericFunctions()); // random: String containing function names
        Assertions.assertNotNull(meta.getStringFunctions()); // random
        Assertions.assertNotNull(meta.getSystemFunctions()); // random

        // 31–35
        Assertions.assertNotNull(meta.getTimeDateFunctions()); // random
        Assertions.assertEquals("\\", meta.getSearchStringEscape());
        Assertions.assertEquals("", meta.getExtraNameCharacters());
        Assertions.assertTrue( meta.supportsAlterTableWithAddColumn());
        Assertions.assertTrue( meta.supportsAlterTableWithDropColumn());

        // 36–40
        Assertions.assertTrue( meta.supportsColumnAliasing());
        Assertions.assertTrue( meta.nullPlusNonNullIsNull());
        Assertions.assertTrue( meta.supportsConvert());
        Assertions.assertTrue( meta.supportsConvert(Types.INTEGER, Types.VARCHAR));
        Assertions.assertTrue( meta.supportsTableCorrelationNames());

        // 41–45
        Assertions.assertFalse( meta.supportsDifferentTableCorrelationNames());
        Assertions.assertTrue( meta.supportsExpressionsInOrderBy());
        Assertions.assertTrue( meta.supportsOrderByUnrelated());
        Assertions.assertTrue( meta.supportsGroupBy());
        Assertions.assertTrue( meta.supportsGroupByUnrelated());

        // 46–50
        Assertions.assertTrue( meta.supportsGroupByBeyondSelect());
        Assertions.assertTrue( meta.supportsLikeEscapeClause());
        Assertions.assertFalse( meta.supportsMultipleResultSets());
        Assertions.assertTrue( meta.supportsMultipleTransactions());
        Assertions.assertTrue( meta.supportsNonNullableColumns());

        // 51–55
        Assertions.assertTrue( meta.supportsMinimumSQLGrammar());
        Assertions.assertTrue( meta.supportsCoreSQLGrammar());
        Assertions.assertFalse( meta.supportsExtendedSQLGrammar());
        Assertions.assertTrue( meta.supportsANSI92EntryLevelSQL());
        Assertions.assertFalse( meta.supportsANSI92IntermediateSQL());

        // 56–60
        Assertions.assertFalse( meta.supportsANSI92FullSQL());
        Assertions.assertTrue( meta.supportsIntegrityEnhancementFacility());
        Assertions.assertTrue( meta.supportsOuterJoins());
        Assertions.assertFalse( meta.supportsFullOuterJoins());
        Assertions.assertTrue( meta.supportsLimitedOuterJoins());

        // 61–65
        Assertions.assertEquals("schema", meta.getSchemaTerm());
        Assertions.assertEquals("procedure", meta.getProcedureTerm());
        Assertions.assertEquals("catalog", meta.getCatalogTerm());
        Assertions.assertTrue( meta.isCatalogAtStart());
        Assertions.assertEquals(".", meta.getCatalogSeparator());

        // 66–75
        Assertions.assertTrue( meta.supportsSchemasInDataManipulation());
        Assertions.assertTrue( meta.supportsSchemasInProcedureCalls());
        Assertions.assertTrue( meta.supportsSchemasInTableDefinitions());
        Assertions.assertTrue( meta.supportsSchemasInIndexDefinitions());
        Assertions.assertTrue( meta.supportsSchemasInPrivilegeDefinitions());
        Assertions.assertTrue( meta.supportsCatalogsInDataManipulation());
        Assertions.assertFalse( meta.supportsCatalogsInProcedureCalls());
        Assertions.assertTrue( meta.supportsCatalogsInTableDefinitions());
        Assertions.assertTrue( meta.supportsCatalogsInIndexDefinitions());
        Assertions.assertTrue( meta.supportsCatalogsInPrivilegeDefinitions());

        // 76–90
        Assertions.assertFalse( meta.supportsPositionedDelete());
        Assertions.assertFalse( meta.supportsPositionedUpdate());
        Assertions.assertTrue( meta.supportsSelectForUpdate());
        Assertions.assertFalse( meta.supportsStoredProcedures());
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

        // 91–111: Random numeric values (replace with actual as needed)
        Assertions.assertEquals(0, meta.getMaxBinaryLiteralLength());
        Assertions.assertEquals(0, meta.getMaxCharLiteralLength());
        Assertions.assertEquals(0, meta.getMaxColumnNameLength());
        Assertions.assertEquals(0, meta.getMaxColumnsInGroupBy());
        Assertions.assertEquals(0, meta.getMaxColumnsInIndex());
        Assertions.assertEquals(0, meta.getMaxColumnsInOrderBy());
        Assertions.assertEquals(0, meta.getMaxColumnsInSelect());
        Assertions.assertEquals(0, meta.getMaxColumnsInTable());
        Assertions.assertEquals(0, meta.getMaxConnections());
        Assertions.assertEquals(0, meta.getMaxCursorNameLength());
        Assertions.assertEquals(0, meta.getMaxIndexLength());
        Assertions.assertEquals(0, meta.getMaxSchemaNameLength());
        Assertions.assertEquals(0, meta.getMaxProcedureNameLength());
        Assertions.assertEquals(0, meta.getMaxCatalogNameLength());
        Assertions.assertEquals(0, meta.getMaxRowSize());
        Assertions.assertFalse( meta.doesMaxRowSizeIncludeBlobs());
        Assertions.assertEquals(0, meta.getMaxStatementLength());
        Assertions.assertEquals(0, meta.getMaxStatements());
        Assertions.assertEquals(0, meta.getMaxTableNameLength());
        Assertions.assertEquals(0, meta.getMaxTablesInSelect());
        Assertions.assertEquals(0, meta.getMaxUserNameLength());
        Assertions.assertEquals(Connection.TRANSACTION_READ_COMMITTED, meta.getDefaultTransactionIsolation());

        // 112–118
        Assertions.assertTrue( meta.supportsTransactions());
        Assertions.assertTrue( meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        Assertions.assertFalse( meta.supportsDataDefinitionAndDataManipulationTransactions());
        Assertions.assertTrue( meta.supportsDataManipulationTransactionsOnly());
        Assertions.assertTrue( meta.dataDefinitionCausesTransactionCommit());
        Assertions.assertFalse( meta.dataDefinitionIgnoredInTransactions());

        // 119–174: ResultSets, Connection, and more
        try (ResultSet rs = meta.getProcedures(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getProcedureColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTables(null, null, null, new String[]{"TABLE"})) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getSchemas()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCatalogs()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTableTypes()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumnPrivileges(null, null, "TEST_TABLE", null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTablePrivileges(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getBestRowIdentifier(null, null, "TEST_TABLE", DatabaseMetaData.bestRowSession, false)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getVersionColumns(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPrimaryKeys(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getImportedKeys(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getExportedKeys(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCrossReference(null, null, "TEST_TABLE", null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTypeInfo()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, "TEST_TABLE", false, false)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getUDTs(null, null, null, null)) {
            validateAllRows(rs);
        }
        Assertions.assertNotNull(meta.getConnection());
        Assertions.assertTrue( meta.supportsSavepoints());
        Assertions.assertFalse( meta.supportsNamedParameters());
        Assertions.assertFalse( meta.supportsMultipleOpenResults());
        Assertions.assertTrue( meta.supportsGetGeneratedKeys());
        try (ResultSet rs = meta.getSuperTypes(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getSuperTables(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getAttributes(null, null, null, null)) {
            validateAllRows(rs);
        }
        Assertions.assertFalse( meta.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        Assertions.assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, meta.getResultSetHoldability());
        Assertions.assertEquals(2, meta.getDatabaseMajorVersion());
        Assertions.assertEquals(3, meta.getDatabaseMinorVersion());
        Assertions.assertEquals(4, meta.getJDBCMajorVersion());
        Assertions.assertEquals(3, meta.getJDBCMinorVersion());
        Assertions.assertEquals(DatabaseMetaData.sqlStateSQL, meta.getSQLStateType());
        Assertions.assertFalse( meta.locatorsUpdateCopy());
        Assertions.assertFalse( meta.supportsStatementPooling());
        Assertions.assertNotNull(meta.getRowIdLifetime());
        try (ResultSet rs = meta.getSchemas(null, null)) {
            validateAllRows(rs);
        }
        Assertions.assertTrue( meta.supportsStoredFunctionsUsingCallSyntax());
        Assertions.assertFalse( meta.autoCommitFailureClosesAllResultSets());
        try (ResultSet rs = meta.getClientInfoProperties()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctions(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctionColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPseudoColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        Assertions.assertTrue( meta.generatedKeyAlwaysReturned());
        Assertions.assertEquals(0, meta.getMaxLogicalLobSize());
        Assertions.assertFalse( meta.supportsRefCursors());
        Assertions.assertFalse( meta.supportsSharding());

        // 175–177: ResultSet/Concurrency methods
        Assertions.assertTrue( meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertTrue( meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        Assertions.assertTrue( meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.ownDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.ownInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.othersUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.othersDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.othersInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.updatesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.deletesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertFalse( meta.insertsAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertTrue( meta.supportsBatchUpdates());
    }

    private void validateAllRows(ResultSet rs) throws SQLException {
        TestDBUtils.validateAllRows(rs);
    }
}
