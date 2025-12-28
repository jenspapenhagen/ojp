# Oracle UCP XA Pool Provider Example

## Overview

This document provides a complete example of implementing an XA pool provider using Oracle Universal Connection Pool (UCP). Oracle UCP offers advanced features specifically optimized for Oracle databases.

## Why Oracle UCP?

Oracle UCP provides several advantages over generic pooling solutions:

- **Connection Affinity**: Sessions stick to specific Oracle RAC nodes
- **Fast Connection Failover (FCF)**: Automatic failover on node failure
- **Statement Caching**: Built-in prepared statement caching
- **Oracle-Specific Optimizations**: Query result caching, connection labeling
- **Native XA Support**: Optimized for Oracle's XA implementation

## Implementation

### 1. Maven Dependencies

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc11</artifactId>
    <version>23.3.0.23.09</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ucp</artifactId>
    <version>23.3.0.23.09</version>
</dependency>
```

### 2. Provider Implementation

```java
package org.openjproxy.xa.pool.oracle;

import oracle.ucp.jdbc.PoolXADataSource;
import oracle.ucp.jdbc.PoolXADataSourceFactory;
import org.openjproxy.xa.pool.*;

import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;

public class OracleUCPXAProvider implements XAConnectionPoolProvider {
    private static final Logger log = Logger.getLogger(OracleUCPXAProvider.class.getName());
    
    @Override
    public String getName() {
        return "OracleUCPXAProvider";
    }
    
    @Override
    public int getPriority() {
        // Higher priority than default provider for Oracle databases
        return 50;
    }
    
    @Override
    public boolean supports(String databaseUrl) {
        // Only handle Oracle databases
        return databaseUrl != null && databaseUrl.contains(":oracle:");
    }
    
    @Override
    public XADataSource createPooledXADataSource(Map<String, String> config) throws Exception {
        log.info("Creating Oracle UCP XA pool with config: " + config);
        
        // Extract configuration
        String url = config.get("xa.url");
        String username = config.get("xa.username");
        String password = config.get("xa.password");
        int maxPoolSize = Integer.parseInt(config.getOrDefault("xa.maxPoolSize", "10"));
        int minIdle = Integer.parseInt(config.getOrDefault("xa.minIdle", "2"));
        int maxWaitMillis = Integer.parseInt(config.getOrDefault("xa.maxWaitMillis", "30000"));
        
        // Oracle UCP-specific configuration
        boolean enableFCF = Boolean.parseBoolean(config.getOrDefault("xa.oracle.ucp.enableFCF", "true"));
        int statementCacheSize = Integer.parseInt(config.getOrDefault("xa.oracle.ucp.statementCacheSize", "50"));
        boolean validateOnBorrow = Boolean.parseBoolean(config.getOrDefault("xa.oracle.ucp.validateOnBorrow", "true"));
        
        // Create UCP PoolXADataSource
        PoolXADataSource pds = PoolXADataSourceFactory.getPoolXADataSource();
        
        // Set connection properties
        pds.setConnectionFactoryClassName("oracle.jdbc.xa.client.OracleXADataSource");
        pds.setURL(url);
        pds.setUser(username);
        pds.setPassword(password);
        
        // Set pool properties
        pds.setInitialPoolSize(minIdle);
        pds.setMinPoolSize(minIdle);
        pds.setMaxPoolSize(maxPoolSize);
        pds.setConnectionWaitTimeout(maxWaitMillis / 1000); // UCP uses seconds
        
        // Set validation
        if (validateOnBorrow) {
            pds.setValidateConnectionOnBorrow(true);
            pds.setInactiveConnectionTimeout(300); // 5 minutes
        }
        
        // Enable Fast Connection Failover
        if (enableFCF) {
            pds.setFastConnectionFailoverEnabled(true);
        }
        
        // Set statement cache size
        pds.setMaxStatements(statementCacheSize);
        
        // Set connection timeout
        pds.setConnectionTimeout(Integer.parseInt(config.getOrDefault("xa.connectionTimeoutSeconds", "60")));
        
        // Set abandoned connection timeout (for leak detection)
        pds.setAbandonedConnectionTimeout(Integer.parseInt(config.getOrDefault("xa.abandonedTimeoutSeconds", "300")));
        pds.setTimeToLiveConnectionTimeout(Integer.parseInt(config.getOrDefault("xa.maxLifetimeMinutes", "30")) * 60);
        
        log.info("Oracle UCP XA pool created successfully");
        return pds;
    }
    
    @Override
    public BackendSession borrowSession(XADataSource pooledDataSource) throws Exception {
        if (!(pooledDataSource instanceof PoolXADataSource)) {
            throw new IllegalArgumentException("Expected PoolXADataSource, got: " + 
                pooledDataSource.getClass().getName());
        }
        
        PoolXADataSource pds = (PoolXADataSource) pooledDataSource;
        
        try {
            // Borrow XA connection from UCP pool
            var xaConnection = pds.getXAConnection();
            
            // Wrap in BackendSession
            return new BackendSessionImpl(xaConnection);
        } catch (SQLException e) {
            log.severe("Failed to borrow session from Oracle UCP pool: " + e.getMessage());
            throw new Exception("Failed to borrow XA session from pool", e);
        }
    }
}
```

### 3. ServiceLoader Registration

Create file: `src/main/resources/META-INF/services/org.openjproxy.xa.pool.XAConnectionPoolProvider`

```
org.openjproxy.xa.pool.oracle.OracleUCPXAProvider
```

### 4. Configuration

```properties
# Enable XA pooling
ojp.xa.pooling.enabled=true

# Standard XA configuration
ojp.xa.maxPoolSize=20
ojp.xa.minIdle=5
ojp.xa.maxWaitMillis=30000
ojp.xa.connectionTimeoutSeconds=60
ojp.xa.abandonedTimeoutSeconds=300
ojp.xa.maxLifetimeMinutes=30

# Oracle UCP-specific configuration
ojp.xa.oracle.ucp.enableFCF=true
ojp.xa.oracle.ucp.statementCacheSize=100
ojp.xa.oracle.ucp.validateOnBorrow=true
```

## Advanced Features

### Fast Connection Failover (FCF)

FCF provides automatic and rapid failover in Oracle RAC environments:

```java
// Enable FCF in configuration
pds.setFastConnectionFailoverEnabled(true);

// Configure ONS (Oracle Notification Service) for RAC
pds.setONSConfiguration("nodes=rac1:6200,rac2:6200");
```

### Connection Labeling

Use connection labeling for session state optimization:

```java
// Apply labels when borrowing connection
Properties labels = new Properties();
labels.put("MODULE", "OJP_XA");
labels.put("ACTION", "DISTRIBUTED_TRANSACTION");

XAConnection xaConn = pds.getXAConnection();
oracle.ucp.jdbc.LabelableConnection labelConn = (oracle.ucp.jdbc.LabelableConnection) xaConn;
labelConn.applyConnectionLabel(labels);
```

### Connection Affinity

Enable connection affinity for Oracle RAC:

```java
pds.setConnectionAffinityEnabled(true);
```

### Query Result Caching

Enable Oracle's query result cache:

```java
pds.setQueryTimeout(30);
// Query results are cached automatically by Oracle
```

## Monitoring and Metrics

### JMX Monitoring

Oracle UCP exposes JMX MBeans for monitoring:

```java
// Enable JMX
pds.setJMXEnabled(true);

// Access via JMX:
// ObjectName: oracle.ucp.admin.UniversalConnectionPoolMBean
// Attributes: AvailableConnectionsCount, BorrowedConnectionsCount, etc.
```

### Programmatic Metrics

```java
import oracle.ucp.admin.UniversalConnectionPoolManager;
import oracle.ucp.admin.UniversalConnectionPoolManagerImpl;

UniversalConnectionPoolManager mgr = UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager();
oracle.ucp.jdbc.PoolDataSource pds = mgr.getConnectionPool("mypool");

// Get statistics
int available = pds.getAvailableConnectionsCount();
int borrowed = pds.getBorrowedConnectionsCount();
long totalConnections = pds.getTotalConnectionsCount();
long failedConnections = pds.getFailedConnectionAttempts();
```

## Performance Tuning

### Optimal Pool Sizing

```properties
# For OLTP workloads
ojp.xa.maxPoolSize=50
ojp.xa.minIdle=10

# For batch workloads
ojp.xa.maxPoolSize=20
ojp.xa.minIdle=5
```

### Statement Caching

```properties
# Aggressive caching for repetitive queries
ojp.xa.oracle.ucp.statementCacheSize=200

# Conservative for varied queries
ojp.xa.oracle.ucp.statementCacheSize=50
```

### Connection Validation

```properties
# Production: Validate on borrow
ojp.xa.oracle.ucp.validateOnBorrow=true

# Development: Disable for faster performance
ojp.xa.oracle.ucp.validateOnBorrow=false
```

## Testing

```java
@Test
public void testOracleUCPProvider() throws Exception {
    // Create configuration
    Map<String, String> config = new HashMap<>();
    config.put("xa.url", "jdbc:oracle:thin:@localhost:1521:ORCL");
    config.put("xa.username", "system");
    config.put("xa.password", "oracle");
    config.put("xa.maxPoolSize", "10");
    config.put("xa.oracle.ucp.enableFCF", "true");
    config.put("xa.oracle.ucp.statementCacheSize", "50");
    
    // Create provider
    OracleUCPXAProvider provider = new OracleUCPXAProvider();
    assertTrue(provider.supports("jdbc:oracle:thin:@localhost:1521:ORCL"));
    assertEquals(50, provider.getPriority());
    
    // Create pool
    XADataSource xaDataSource = provider.createPooledXADataSource(config);
    assertNotNull(xaDataSource);
    assertTrue(xaDataSource instanceof PoolXADataSource);
    
    // Borrow session
    BackendSession session = provider.borrowSession(xaDataSource);
    assertNotNull(session);
    assertNotNull(session.getXAConnection());
    assertNotNull(session.getConnection());
    assertNotNull(session.getXAResource());
    
    // Test XA operations
    Xid xid = new SimpleXid(1, new byte[]{1}, new byte[]{1});
    session.getXAResource().start(xid, XAResource.TMNOFLAGS);
    
    // Execute SQL
    try (Statement stmt = session.getConnection().createStatement()) {
        stmt.execute("SELECT 1 FROM DUAL");
    }
    
    session.getXAResource().end(xid, XAResource.TMSUCCESS);
    session.getXAResource().prepare(xid);
    session.getXAResource().commit(xid, false);
    
    // Return session (back to pool)
    session.close();
}
```

## Troubleshooting

### Issue: FCF Not Working

**Symptom**: Connections don't failover automatically

**Solution**:
1. Verify ONS configuration:
   ```properties
   ojp.xa.oracle.ucp.onsConfiguration=nodes=rac1:6200,rac2:6200
   ```

2. Check Oracle RAC ONS is running:
   ```bash
   srvctl status nodeapps
   ```

3. Enable FCF debugging:
   ```java
   System.setProperty("oracle.ucp.debug.level", "FINEST");
   ```

### Issue: Statement Cache Not Effective

**Symptom**: High parse time despite caching enabled

**Solution**:
- Ensure using PreparedStatements (not regular Statements)
- Increase cache size if needed
- Monitor cache hit ratio via JMX

### Issue: Connection Leaks

**Symptom**: Pool exhaustion over time

**Solution**:
```properties
# Enable abandoned connection timeout
ojp.xa.abandonedTimeoutSeconds=300

# Enable connection timeout
ojp.xa.connectionTimeoutSeconds=60

# Monitor via JMX
```

## Comparison: UCP vs Commons Pool 2

| Feature | Oracle UCP | Commons Pool 2 |
|---------|------------|----------------|
| Oracle RAC Support | ✅ Excellent (FCF, Affinity) | ⚠️ Basic |
| Statement Caching | ✅ Built-in | ❌ Manual |
| Connection Labeling | ✅ Yes | ❌ No |
| Query Result Caching | ✅ Yes | ❌ No |
| Vendor Dependencies | ⚠️ Oracle-only | ✅ None |
| Database Support | ⚠️ Oracle only | ✅ All databases |
| Configuration | ⚠️ Complex | ✅ Simple |

## Recommendation

**Use Oracle UCP when:**
- Connecting to Oracle databases
- Using Oracle RAC
- Need FCF or connection affinity
- Want statement caching
- Need Oracle-specific optimizations

**Use Commons Pool 2 when:**
- Connecting to non-Oracle databases
- Want zero vendor dependencies
- Need simple configuration
- Supporting multiple database types

## References

- [Oracle UCP Documentation](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjucp/)
- [Oracle UCP Developer's Guide](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjuar/)
- [Fast Connection Failover](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjuar/fast-connection-failover.html)

## Unified Implementation: Single Pool for Both XA and Non-XA

Oracle UCP's `PoolDataSource` natively supports both XA and non-XA connections from a single pool. This is the **recommended approach** for Oracle databases as it provides:
- Single pool manages all connections (non-XA + XA)
- Unified monitoring and statistics
- Shared resource limits
- Lower memory footprint than separate pools
- Matches UCP's design philosophy

### Unified Provider Implementation

```java
package org.openjproxy.pool.oracle;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import org.openjproxy.pool.ConnectionPoolProvider;
import org.openjproxy.xa.pool.XAConnectionPoolProvider;
import org.openjproxy.xa.pool.BackendSession;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Unified Oracle UCP provider that implements both non-XA and XA SPIs
 * using a single connection pool.
 */
public class OracleUCPUnifiedProvider implements 
    ConnectionPoolProvider,           // Non-XA SPI
    XAConnectionPoolProvider {        // XA SPI
    
    private static final Logger log = Logger.getLogger(OracleUCPUnifiedProvider.class.getName());
    private PoolDataSource poolDataSource;
    
    @Override
    public String getName() {
        return "OracleUCPUnifiedProvider";
    }
    
    @Override
    public int getPriority() {
        // Highest priority for Oracle databases
        return 100;
    }
    
    @Override
    public boolean supports(String databaseUrl) {
        return databaseUrl != null && databaseUrl.contains(":oracle:");
    }
    
    /**
     * Initialize unified pool - called by either SPI
     */
    private synchronized void initializePool(Map<String, String> config) throws SQLException {
        if (poolDataSource != null) {
            return; // Already initialized
        }
        
        log.info("Initializing unified Oracle UCP pool");
        
        // Extract configuration with XA precedence (Option 1)
        String url = config.get("xa.url");
        if (url == null) {
            url = config.get("url");
        }
        
        String username = config.get("xa.username");
        if (username == null) {
            username = config.get("username");
        }
        
        String password = config.get("xa.password");
        if (password == null) {
            password = config.get("password");
        }
        
        // Pool sizing: XA properties take precedence
        int maxPoolSize = getConfigValue(config, "maxPoolSize", "maxTotal", 20);
        int minIdle = getConfigValue(config, "minIdle", "minIdle", 5);
        int maxWaitMillis = getConfigValue(config, "maxWaitMillis", "connectionTimeout", 30000);
        
        // Create UCP PoolDataSource (supports both XA and non-XA)
        poolDataSource = PoolDataSourceFactory.getPoolDataSource();
        
        // Set connection factory for XA support
        poolDataSource.setConnectionFactoryClassName("oracle.jdbc.xa.client.OracleXADataSource");
        poolDataSource.setURL(url);
        poolDataSource.setUser(username);
        poolDataSource.setPassword(password);
        
        // Set pool properties
        poolDataSource.setInitialPoolSize(minIdle);
        poolDataSource.setMinPoolSize(minIdle);
        poolDataSource.setMaxPoolSize(maxPoolSize);
        poolDataSource.setConnectionWaitTimeout(maxWaitMillis / 1000); // UCP uses seconds
        
        // Oracle UCP-specific configuration
        boolean enableFCF = Boolean.parseBoolean(config.getOrDefault("xa.oracle.ucp.enableFCF", 
            config.getOrDefault("oracle.ucp.enableFCF", "true")));
        int statementCacheSize = Integer.parseInt(config.getOrDefault("xa.oracle.ucp.statementCacheSize",
            config.getOrDefault("oracle.ucp.statementCacheSize", "50")));
        boolean validateOnBorrow = Boolean.parseBoolean(config.getOrDefault("xa.oracle.ucp.validateOnBorrow",
            config.getOrDefault("oracle.ucp.validateOnBorrow", "true")));
        
        if (validateOnBorrow) {
            poolDataSource.setValidateConnectionOnBorrow(true);
            poolDataSource.setInactiveConnectionTimeout(300);
        }
        
        if (enableFCF) {
            poolDataSource.setFastConnectionFailoverEnabled(true);
        }
        
        poolDataSource.setMaxStatements(statementCacheSize);
        
        log.info(String.format("Oracle UCP unified pool created: maxPoolSize=%d, minIdle=%d, " +
            "FCF=%b, statementCache=%d", maxPoolSize, minIdle, enableFCF, statementCacheSize));
    }
    
    /**
     * Get configuration value with XA precedence (Option 1).
     * 
     * Priority order:
     * 1. ojp.xa.connection.pool.{xaKey}
     * 2. ojp.connection.pool.{nonXaKey}
     * 3. defaultValue
     */
    private int getConfigValue(Map<String, String> config, String xaKey, String nonXaKey, int defaultValue) {
        String xaValue = config.get("xa." + xaKey);
        if (xaValue != null) {
            return Integer.parseInt(xaValue);
        }
        
        String nonXaValue = config.get(nonXaKey);
        if (nonXaValue != null) {
            return Integer.parseInt(nonXaValue);
        }
        
        return defaultValue;
    }
    
    // ====== Non-XA SPI Methods ======
    
    @Override
    public Connection getConnection(String url, Map<String, String> config) throws SQLException {
        initializePool(config);
        
        // Get regular connection from pool
        log.fine("Borrowing non-XA connection from unified UCP pool");
        return poolDataSource.getConnection();
    }
    
    @Override
    public void closePool() throws SQLException {
        if (poolDataSource != null) {
            log.info("Closing unified Oracle UCP pool");
            // Note: UCP doesn't have explicit close, connections released on GC
            poolDataSource = null;
        }
    }
    
    // ====== XA SPI Methods ======
    
    @Override
    public XADataSource createPooledXADataSource(Map<String, String> config) throws Exception {
        initializePool(config);
        
        // Return adapter that provides XAConnections from the same pool
        return new XADataSource() {
            @Override
            public XAConnection getXAConnection() throws SQLException {
                log.fine("Borrowing XA connection from unified UCP pool");
                return poolDataSource.getXAConnection();
            }
            
            @Override
            public XAConnection getXAConnection(String user, String password) throws SQLException {
                return poolDataSource.getXAConnection(user, password);
            }
            
            @Override
            public java.io.PrintWriter getLogWriter() throws SQLException {
                return poolDataSource.getLogWriter();
            }
            
            @Override
            public void setLogWriter(java.io.PrintWriter out) throws SQLException {
                poolDataSource.setLogWriter(out);
            }
            
            @Override
            public void setLoginTimeout(int seconds) throws SQLException {
                poolDataSource.setLoginTimeout(seconds);
            }
            
            @Override
            public int getLoginTimeout() throws SQLException {
                return poolDataSource.getLoginTimeout();
            }
            
            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getLogger("oracle.ucp");
            }
        };
    }
    
    @Override
    public BackendSession borrowSession(XADataSource pooledDataSource) throws Exception {
        // pooledDataSource is the adapter created above
        XAConnection xaConnection = pooledDataSource.getXAConnection();
        return new BackendSessionImpl(xaConnection);
    }
}
```

### Configuration Precedence Strategy (Option 1 - Recommended)

When using a unified Oracle UCP provider, configuration follows this precedence order:

**Priority: XA properties → Non-XA properties → Default values**

```properties
# XA-specific properties (highest priority)
ojp.xa.connection.pool.maxTotal=30
ojp.xa.connection.pool.minIdle=10
ojp.xa.connection.pool.connectionTimeout=30000

# Non-XA properties (fallback)
ojp.connection.pool.maxPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=20000

# With above config, unified UCP pool will use:
# - maxPoolSize=30 (from XA config)
# - minIdle=10 (from XA config)
# - connectionTimeout=30000ms (from XA config)
```

**Rationale:**
- XA connections are typically more resource-intensive and have stricter requirements
- If XA properties are set, they indicate the user wants specific XA pool sizing
- Non-XA connections can share the pool with XA-sized limits
- Single pool must accommodate both workloads, so use the more conservative (typically larger) XA settings

### Configuration Examples

#### Example 1: XA-Heavy Workload
```properties
# Optimize for XA transactions
ojp.xa.connection.pool.maxTotal=50
ojp.xa.connection.pool.minIdle=15
ojp.xa.connection.pool.connectionTimeout=60000

# Non-XA uses XA pool limits
ojp.connection.pool.maxPoolSize=20  # Ignored, XA precedence
ojp.connection.pool.minimumIdle=5   # Ignored, XA precedence

# Result: Single pool with maxPoolSize=50, minIdle=15
```

#### Example 2: Mixed Workload
```properties
# XA and non-XA share pool
ojp.xa.connection.pool.maxTotal=40
ojp.xa.connection.pool.minIdle=10

# Oracle-specific features
ojp.xa.oracle.ucp.enableFCF=true
ojp.xa.oracle.ucp.statementCacheSize=100

# Result: Single pool handles both, sized for peak XA + non-XA load
```

#### Example 3: Non-XA Only (Fallback)
```properties
# No XA properties set, use non-XA config
ojp.connection.pool.maxPoolSize=25
ojp.connection.pool.minimumIdle=8

# Result: Pool uses non-XA values as fallback
```

### ServiceLoader Registration

Create file: `src/main/resources/META-INF/services/org.openjproxy.pool.ConnectionPoolProvider`
```
org.openjproxy.pool.oracle.OracleUCPUnifiedProvider
```

Create file: `src/main/resources/META-INF/services/org.openjproxy.xa.pool.XAConnectionPoolProvider`
```
org.openjproxy.pool.oracle.OracleUCPUnifiedProvider
```

### Benefits of Unified Approach

1. **Resource Efficiency**: Single pool manages all connections
2. **Simplified Configuration**: One set of pool properties
3. **Unified Monitoring**: Single set of metrics and JMX beans
4. **Lower Memory Footprint**: No duplicate pool overhead
5. **Natural for UCP**: Matches UCP's design philosophy

### Trade-offs

- Non-XA and XA connections compete for same pool slots
- Need careful sizing: `maxPoolSize = max(non-XA peak) + max(XA peak)`
- UCP handles this well with connection request queuing

## Next Steps

- Review [Implementation Guide](./IMPLEMENTATION_GUIDE.md) for general provider patterns
- See [Configuration Reference](./CONFIGURATION.md) for all configuration options
- Check [Troubleshooting Guide](./TROUBLESHOOTING.md) for common issues
