# Pool Disable Capability - Comprehensive Testing Plan

## Overview

This document provides a detailed testing plan to validate the pool disable capability for both XA and non-XA connections in the Open J Proxy (OJP) system. The plan covers unit tests, integration tests, performance tests, and manual validation scenarios.

## Testing Strategy

### Test Levels

1. **Unit Tests** - Test individual components in isolation
2. **Integration Tests** - Test end-to-end flows with real databases
3. **Configuration Tests** - Verify configuration parsing and application
4. **Performance Tests** - Compare pooled vs unpooled performance
5. **Error Handling Tests** - Verify proper error handling and recovery
6. **Multinode Tests** - Test disable pool with multinode configurations

### Test Databases

- **H2** - In-memory database for fast unit/integration tests
- **PostgreSQL** - Primary XA-capable database for XA tests
- **MySQL** - Additional coverage for XA tests
- **SQL Server** - Enterprise XA database testing (if available)

### Test Coverage Goals

- **Non-XA:** >90% code coverage on unpooled path
- **XA:** >90% code coverage on unpooled path
- **Configuration:** 100% coverage on property parsing
- **Error scenarios:** All error paths tested

## Test Plan Structure

```
ojp-server/src/test/java/org/openjproxy/grpc/server/
├── pool/
│   ├── NonXAPoolDisableTest.java (NEW)
│   ├── XAPoolDisableTest.java (NEW)
│   ├── PoolConfigurationValidationTest.java (NEW)
│   └── DataSourceConfigurationManagerTest.java (ENHANCE)
└── integration/
    ├── NonXAUnpooledIntegrationTest.java (NEW)
    ├── XAUnpooledIntegrationTest.java (NEW)
    ├── PoolDisableMultinodeTest.java (NEW)
    └── PoolDisablePerformanceTest.java (NEW)
```

---

## Phase 1: Non-XA Pool Disable Tests

### 1.1 Unit Tests - Configuration Parsing

**Test Class:** `NonXAPoolDisableConfigurationTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testPoolDisabledPropertyParsing` | Parse `ojp.connection.pool.enabled=false` | Configuration has poolEnabled=false |
| `testPoolEnabledByDefault` | No property specified | Configuration has poolEnabled=true |
| `testPoolDisabledWithNamedDataSource` | Named datasource with pool disabled | Specific datasource has poolEnabled=false |
| `testPoolDisabledInvalidValue` | Invalid boolean value (e.g., "maybe") | Falls back to default (true) |
| `testPoolDisabledCaseInsensitive` | Property value "FALSE", "False" | All variations work correctly |

**Sample Test:**

```java
@Test
public void testPoolDisabledPropertyParsing() {
    Properties props = new Properties();
    props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
    props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "testDS");
    
    DataSourceConfiguration config = 
        DataSourceConfigurationManager.getConfiguration(props);
    
    assertFalse(config.isPoolEnabled(), 
        "Pool should be disabled when property is false");
    assertEquals("testDS", config.getDataSourceName());
}
```

### 1.2 Unit Tests - Connection Management

**Test Class:** `NonXAUnpooledConnectionManagementTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testUnpooledConnectionDetailsCreation` | Create UnpooledConnectionDetails | Details stored in map |
| `testUnpooledConnectionAcquisition` | Acquire unpooled connection | DriverManager.getConnection called |
| `testUnpooledConnectionReuse` | Multiple requests with same connHash | Each gets new connection (no pooling) |
| `testUnpooledConnectionClosure` | Connection closed after query | Connection properly closed |
| `testUnpooledConnectionWithTimeout` | Connection timeout configuration | Timeout applied (if supported) |
| `testNoPoolCreatedWhenDisabled` | Pool disabled | No HikariDataSource created |
| `testSlowQueryManagerNotCreatedForUnpooled` | Pool disabled | SlowQuerySegregationManager not needed |

### 1.3 Integration Tests - End-to-End

**Test Class:** `NonXAUnpooledIntegrationTest.java`

| Test Case | Description | Database | Expected Result |
|-----------|-------------|----------|-----------------|
| `testUnpooledConnectionBasicQuery` | Execute simple SELECT | H2 | Query succeeds without pooling |
| `testUnpooledConnectionInsertUpdate` | INSERT and UPDATE operations | H2 | Operations succeed |
| `testUnpooledConnectionTransaction` | Begin/commit transaction | H2 | Transaction works correctly |
| `testUnpooledConnectionRollback` | Begin/rollback transaction | H2 | Rollback works correctly |
| `testUnpooledConnectionMultipleQueries` | Execute 10 queries sequentially | H2 | All queries succeed |
| `testUnpooledConnectionConcurrent` | 5 concurrent connections | H2 | No pool exhaustion errors |
| `testUnpooledConnectionWithPostgreSQL` | Basic query | PostgreSQL | Works with real database |
| `testUnpooledConnectionLargeResultSet` | Query returning 1000 rows | H2 | Full result set retrieved |
| `testUnpooledConnectionPreparedStatement` | PreparedStatement with parameters | H2 | PreparedStatement works |
| `testUnpooledConnectionCallableStatement` | CallableStatement (stored proc) | H2 | CallableStatement works |

**Sample Integration Test:**

```java
@Test
public void testUnpooledConnectionBasicQuery() throws Exception {
    // Setup: Create ojp.properties with pool disabled
    Properties ojpProps = new Properties();
    ojpProps.setProperty("ojp.connection.pool.enabled", "false");
    ojpProps.setProperty("ojp.datasource.name", "testUnpooled");
    
    // Setup: Start OJP server with test datasource
    String jdbcUrl = "jdbc:ojp[localhost:1059(testUnpooled)]_h2:mem:testdb";
    
    try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
        try (Statement stmt = conn.createStatement()) {
            // Create table
            stmt.execute("CREATE TABLE test_table (id INT PRIMARY KEY, name VARCHAR(50))");
            stmt.execute("INSERT INTO test_table VALUES (1, 'test')");
            
            // Query data
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM test_table")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("id"));
                assertEquals("test", rs.getString("name"));
            }
        }
    }
    
    // Verify: No pool was created on server side
    // (Would need server-side verification mechanism)
}
```

### 1.4 Error Handling Tests

**Test Class:** `NonXAUnpooledErrorHandlingTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testUnpooledConnectionInvalidURL` | Invalid database URL | SQLException thrown |
| `testUnpooledConnectionInvalidCredentials` | Wrong username/password | Authentication exception |
| `testUnpooledConnectionNetworkFailure` | Database unreachable | Connection timeout exception |
| `testUnpooledConnectionQueryTimeout` | Long-running query | Query timeout exception |
| `testUnpooledConnectionClosedUsage` | Use closed connection | SQLException thrown |
| `testUnpooledConnectionReconnectAfterFailure` | Retry after failure | New connection succeeds |

---

## Phase 2: XA Pool Disable Tests

### 2.1 Unit Tests - Configuration Parsing

**Test Class:** `XAPoolDisableConfigurationTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testXAPoolDisabledPropertyParsing` | Parse `ojp.xa.connection.pool.enabled=false` | XA config has poolEnabled=false |
| `testXAPoolEnabledByDefault` | No property specified | XA config has poolEnabled=true |
| `testXAPoolDisabledWithNamedDataSource` | Named datasource XA pool disabled | Specific datasource XA pool off |
| `testXAPoolIndependentFromNonXA` | Non-XA pooled, XA unpooled | Both configurations independent |
| `testXAPoolConfigurationFallback` | No XA-specific property | Uses XA defaults (not non-XA) |

### 2.2 Unit Tests - XA Connection Management

**Test Class:** `XAUnpooledConnectionManagementTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testXAUnpooledDataSourceCreation` | Create native XADataSource | XADataSource created without pool |
| `testXAUnpooledConnectionAllocation` | Get XAConnection from unpooled DS | XAConnection allocated |
| `testXAUnpooledConnectionSessionBinding` | Bind XAConnection to session | Session has XAConnection reference |
| `testXAUnpooledConnectionReuse` | Same session uses same XAConnection | No new XAConnection created |
| `testXAUnpooledConnectionCleanup` | Session termination | XAConnection closed properly |
| `testXAUnpooledNoRegistryCreated` | Pool disabled | No XATransactionRegistry |
| `testXAUnpooledNoPoolProvider` | Pool disabled | XA pool provider not initialized |

### 2.3 Integration Tests - XA Operations

**Test Class:** `XAUnpooledTransactionIntegrationTest.java`

| Test Case | Description | Database | Expected Result |
|-----------|-------------|----------|-----------------|
| `testXAUnpooledStart` | xa.start() operation | PostgreSQL | XA transaction starts |
| `testXAUnpooledEnd` | xa.end() operation | PostgreSQL | XA transaction ends |
| `testXAUnpooledPrepare` | xa.prepare() operation | PostgreSQL | Prepare returns XA_OK |
| `testXAUnpooledCommit` | xa.commit() one-phase | PostgreSQL | Transaction commits |
| `testXAUnpooledTwoPhaseCommit` | xa.prepare() + xa.commit() | PostgreSQL | Two-phase commit works |
| `testXAUnpooledRollback` | xa.rollback() operation | PostgreSQL | Transaction rolls back |
| `testXAUnpooledMultipleBranches` | Two branches, same XID | PostgreSQL | Branch join works |
| `testXAUnpooledRecover` | xa.recover() operation | PostgreSQL | Returns pending XIDs |
| `testXAUnpooledForget` | xa.forget() operation | PostgreSQL | XID forgotten |
| `testXAUnpooledSetTimeout` | xa.setTransactionTimeout() | PostgreSQL | Timeout set correctly |
| `testXAUnpooledGetTimeout` | xa.getTransactionTimeout() | PostgreSQL | Timeout retrieved |
| `testXAUnpooledIsSameRM` | xa.isSameRM() comparison | PostgreSQL | RM comparison works |

**Sample XA Integration Test:**

```java
@Test
public void testXAUnpooledTwoPhaseCommit() throws Exception {
    // Setup: XA datasource with pool disabled
    Properties ojpProps = new Properties();
    ojpProps.setProperty("ojp.xa.connection.pool.enabled", "false");
    ojpProps.setProperty("ojp.datasource.name", "xaUnpooled");
    
    OjpXADataSource xaDS = new OjpXADataSource();
    xaDS.setURL("jdbc:ojp[localhost:1059(xaUnpooled)]_postgresql://localhost/testdb");
    xaDS.setUser("testuser");
    xaDS.setPassword("testpass");
    
    XAConnection xaConn = xaDS.getXAConnection();
    XAResource xaRes = xaConn.getXAResource();
    Connection conn = xaConn.getConnection();
    
    // Create test table
    try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE xa_test (id INT PRIMARY KEY, value VARCHAR(50))");
    }
    
    // Execute XA transaction with two-phase commit
    Xid xid = new XidImpl(1, new byte[]{0x01}, new byte[]{0x01});
    
    xaRes.start(xid, XAResource.TMNOFLAGS);
    
    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO xa_test VALUES (?, ?)")) {
        ps.setInt(1, 1);
        ps.setString(2, "test_value");
        ps.executeUpdate();
    }
    
    xaRes.end(xid, XAResource.TMSUCCESS);
    
    // Prepare phase
    int prepareResult = xaRes.prepare(xid);
    assertEquals(XAResource.XA_OK, prepareResult);
    
    // Commit phase
    xaRes.commit(xid, false);
    
    // Verify data committed
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT * FROM xa_test WHERE id = 1")) {
        assertTrue(rs.next());
        assertEquals("test_value", rs.getString("value"));
    }
    
    // Cleanup
    xaConn.close();
}
```

### 2.4 XA Error Handling Tests

**Test Class:** `XAUnpooledErrorHandlingTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testXAUnpooledInvalidXADataSource` | Invalid XADataSource class | Configuration exception |
| `testXAUnpooledConnectionFailure` | Cannot create XAConnection | SQLException thrown |
| `testXAUnpooledStartWithoutConnection` | xa.start() before getConnection() | Exception handled gracefully |
| `testXAUnpooledDoubleStart` | xa.start() called twice | XAException thrown |
| `testXAUnpooledPrepareUnknownXID` | xa.prepare() unknown XID | XAException XAER_NOTA |
| `testXAUnpooledCommitWithoutPrepare` | xa.commit() without prepare | Works (one-phase) or throws |
| `testXAUnpooledRollbackUnknownXID` | xa.rollback() unknown XID | XAException XAER_NOTA |
| `testXAUnpooledConnectionLeakPrevention` | Session not closed | Connection eventually cleaned |

---

## Phase 3: Configuration Validation Tests

### 3.1 Property Parsing Tests

**Test Class:** `PoolDisablePropertyParsingTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testBothPoolsDisabled` | Both non-XA and XA pools off | Both configurations disabled |
| `testOnlyNonXADisabled` | Non-XA off, XA on | Independent configuration |
| `testOnlyXADisabled` | Non-XA on, XA off | Independent configuration |
| `testMultipleDataSourcesPooling` | DS1 pooled, DS2 unpooled | Per-datasource configuration |
| `testPropertyPrecedence` | Named DS overrides default | Named property takes precedence |
| `testEnvironmentVariableOverride` | ENV var vs properties file | Environment variable wins |

### 3.2 Configuration Application Tests

**Test Class:** `PoolConfigurationApplicationTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testPoolDisabledTakesEffect` | Pool disabled in config | No pool created on server |
| `testPoolEnabledAfterDisabled` | Re-enable pool for new connection | New connection uses pool |
| `testConfigCaching` | Same config used twice | Config cached, not re-parsed |
| `testConfigInvalidation` | Config changes | New config loaded |
| `testDefaultVsNamedConfig` | Default and named coexist | Both work independently |

---

## Phase 4: Performance and Load Tests

### 4.1 Performance Comparison Tests

**Test Class:** `PoolDisablePerformanceTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testConnectionAcquisitionTime` | Compare pooled vs unpooled | Unpooled slower (expected) |
| `testThroughputSingleThread` | Queries/sec, single thread | Baseline performance |
| `testThroughputMultiThread` | Queries/sec, 10 threads | Unpooled handles concurrency |
| `testLatencyDistribution` | P50, P95, P99 latencies | Document latency differences |
| `testConnectionOverhead` | Connection creation time | Measure unpooled overhead |
| `testMemoryUsage` | Memory consumption | Unpooled uses less memory |
| `testResourceExhaustion` | Max concurrent connections | Document limits |

### 4.2 Load Tests

**Test Class:** `PoolDisableLoadTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testSustainedLoad` | 100 req/sec for 5 minutes | System stable |
| `testBurstLoad` | 1000 req in 10 seconds | No connection errors |
| `testGradualIncrease` | Ramp from 10 to 100 req/sec | Handles ramp smoothly |
| `testLongRunningConnections` | Connections held for 10 min | No issues |
| `testConnectionStarvation` | More threads than DB limit | Proper error handling |

---

## Phase 5: Multinode Tests

### 5.1 Multinode Pool Disable Tests

**Test Class:** `PoolDisableMultinodeTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testMultinodeNonXAUnpooled` | 2 servers, non-XA unpooled | Both servers work |
| `testMultinodeXAUnpooled` | 2 servers, XA unpooled | XA transactions work |
| `testMultinodeMixedPooling` | Server1 pooled, Server2 unpooled | Both configurations work |
| `testMultinodeFailoverUnpooled` | Failover with unpooled | Failover succeeds |
| `testMultinodeLoadBalancingUnpooled` | Load distribution | Requests distributed |
| `testMultinodeXARecoveryUnpooled` | XA recovery after crash | Recovery works |

---

## Phase 6: Regression Tests

### 6.1 Pooled Mode Regression Tests

**Test Class:** `PoolEnabledRegressionTest.java`

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| `testPooledModeStillWorks` | Default pooled mode | No regressions |
| `testPooledXAStillWorks` | XA pooled mode | No regressions |
| `testHikariCPIntegration` | HikariCP provider | Works as before |
| `testCommonsPool2Integration` | Commons Pool 2 XA provider | Works as before |
| `testSlowQuerySegregation` | Pooled mode slow query | Works as before |
| `testMultinodePoolRebalancing` | Pool resizing | Works as before |

---

## Test Execution Plan

### Execution Order

1. **Phase 1** - Non-XA Unit Tests (fast, run first)
2. **Phase 3.1** - Configuration Parsing Tests (fast)
3. **Phase 1** - Non-XA Integration Tests (requires DB)
4. **Phase 2** - XA Unit Tests (after XA implementation)
5. **Phase 2** - XA Integration Tests (after XA implementation)
6. **Phase 3.2** - Configuration Application Tests
7. **Phase 6** - Regression Tests (ensure no breaking changes)
8. **Phase 4** - Performance Tests (document findings)
9. **Phase 5** - Multinode Tests (complex setup)

### Continuous Integration

```yaml
# .github/workflows/pool-disable-tests.yml
name: Pool Disable Tests

on: [push, pull_request]

jobs:
  non-xa-unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
      - name: Run Non-XA Unit Tests
        run: mvn test -Dtest=NonXA*Test
  
  xa-unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
      - name: Run XA Unit Tests
        run: mvn test -Dtest=XA*Test
  
  integration-tests:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:latest
        env:
          POSTGRES_PASSWORD: testpass
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
      - name: Run Integration Tests
        run: mvn verify -Dtest=*IntegrationTest
        env:
          POSTGRES_URL: jdbc:postgresql://localhost:5432/postgres
          POSTGRES_USER: postgres
          POSTGRES_PASSWORD: testpass
```

### Test Environments

| Environment | Purpose | Databases | Duration |
|-------------|---------|-----------|----------|
| Local Dev | Quick feedback | H2 | < 5 min |
| CI Pipeline | PR validation | H2, PostgreSQL | < 15 min |
| Nightly Build | Full test suite | H2, PostgreSQL, MySQL | < 1 hour |
| Pre-Release | Performance + Load | All supported DBs | 2-4 hours |

---

## Manual Validation Scenarios

### Non-XA Manual Tests

1. **Scenario: Disable pooling for testing datasource**
   ```properties
   # ojp.properties
   testDS.ojp.connection.pool.enabled=false
   testDS.ojp.connection.pool.connectionTimeout=5000
   ```
   - Connect via `jdbc:ojp[localhost:1059(testDS)]_postgresql://...`
   - Execute multiple queries
   - Verify each query gets new connection (no pooling)
   - Check logs for "Unpooled (passthrough) mode enabled"

2. **Scenario: Mixed pooled and unpooled datasources**
   ```properties
   prod.ojp.connection.pool.enabled=true
   prod.ojp.connection.pool.maximumPoolSize=20
   
   debug.ojp.connection.pool.enabled=false
   ```
   - Connect to both datasources simultaneously
   - Verify prod uses pool, debug doesn't
   - Check server-side datasource maps

### XA Manual Tests

1. **Scenario: Disable XA pooling for debugging**
   ```properties
   # ojp.properties
   debug.ojp.xa.connection.pool.enabled=false
   ```
   - Create OjpXADataSource with URL containing (debug)
   - Execute XA transaction (start, end, prepare, commit)
   - Verify transaction completes successfully
   - Check logs for "XA unpooled mode enabled"

2. **Scenario: XA recovery with unpooled connections**
   - Start XA transaction
   - Simulate crash before commit
   - Restart server
   - Verify xa.recover() returns pending XID
   - Complete transaction

### Performance Validation

1. **Measure connection acquisition overhead**
   - Run 1000 queries with pooling enabled
   - Run 1000 queries with pooling disabled
   - Compare average connection acquisition time
   - Document results

2. **Measure throughput difference**
   - Run JMeter/Gatling load test with pooled
   - Run same test with unpooled
   - Compare requests/second
   - Document results and recommendations

---

## Success Criteria Summary

### Non-XA Pool Disable

- [ ] All unit tests pass (>90% coverage)
- [ ] All integration tests pass
- [ ] Configuration property `ojp.connection.pool.enabled=false` works
- [ ] UnpooledConnectionDetails created and used correctly
- [ ] No HikariCP datasource created when disabled
- [ ] Connections acquired via DriverManager
- [ ] Connections properly closed after use
- [ ] Error handling works correctly
- [ ] Documentation updated
- [ ] No regressions in pooled mode

### XA Pool Disable

- [ ] All unit tests pass (>90% coverage)
- [ ] All integration tests pass
- [ ] Configuration property `ojp.xa.connection.pool.enabled=false` works
- [ ] XADataSource created without pool
- [ ] XAConnection properly bound to session
- [ ] All XA operations work (start, end, prepare, commit, rollback, recover, forget)
- [ ] XAConnection properly closed on session termination
- [ ] No XATransactionRegistry created when disabled
- [ ] Error handling works correctly
- [ ] Documentation updated with use cases and implications
- [ ] No regressions in pooled mode

### Overall Validation

- [ ] Both pooled and unpooled modes work independently
- [ ] Configuration is clear and well-documented
- [ ] Performance implications documented
- [ ] Use case recommendations provided
- [ ] Migration guide available
- [ ] Troubleshooting guide available
- [ ] CI/CD pipeline includes all tests
- [ ] Test coverage reports generated
- [ ] Manual validation scenarios verified

---

## Appendix: Test Utilities

### Helper Classes

```java
/**
 * Utility for creating test configurations
 */
public class PoolDisableTestUtils {
    
    public static Properties createUnpooledConfig(String datasourceName) {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, datasourceName);
        props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        return props;
    }
    
    public static Properties createUnpooledXAConfig(String datasourceName) {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, datasourceName);
        props.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "false");
        return props;
    }
    
    public static void verifyNoPoolCreated(String connHash, StatementServiceImpl service) {
        // Verify datasourceMap doesn't contain HikariDataSource
        // Verify unpooledConnectionDetailsMap contains the connHash
    }
    
    public static void verifyNoXARegistryCreated(String connHash, StatementServiceImpl service) {
        // Verify xaRegistries map doesn't contain the connHash
    }
}
```

### Test Database Setup

```java
/**
 * Helper for setting up test databases
 */
public class TestDatabaseSetup {
    
    public static DataSource createH2DataSource() {
        // Create H2 in-memory database
    }
    
    public static XADataSource createH2XADataSource() {
        // Create H2 XA-capable datasource
    }
    
    public static DataSource createPostgreSQLDataSource() {
        // Create PostgreSQL datasource (requires Docker or local instance)
    }
    
    public static XADataSource createPostgreSQLXADataSource() {
        // Create PostgreSQL XADataSource
    }
}
```

---

## Conclusion

This comprehensive testing plan provides:

1. **Structured approach** - Phased testing from unit to integration to performance
2. **Complete coverage** - All aspects of pool disable functionality
3. **Clear success criteria** - Measurable validation goals
4. **Practical guidance** - Sample tests and utilities
5. **Continuous validation** - CI/CD integration

Following this plan will ensure both Non-XA and XA pool disable capabilities are thoroughly tested and production-ready.
