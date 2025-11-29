# Connection Pool Configuration Reference

This document provides a complete reference for configuring connection pools in OJP.

## Server Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `OJP_DATASOURCE_PROVIDER` | Default provider ID | hikari |

### System Properties

| Property | Description | Default |
|----------|-------------|---------|
| `ojp.datasource.provider` | Default provider ID | hikari |

## Client Configuration (ojp.properties)

Add these properties to your `ojp.properties` file:

```properties
# Connection pool provider (optional, default: hikari)
ojp.datasource.provider=dbcp

# Pool sizing
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5

# Timeouts (milliseconds)
ojp.connection.pool.connectionTimeout=10000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000
```

## Property Mapping

### Legacy HikariCP Properties → Unified Properties

| Legacy Property | Unified Property |
|-----------------|------------------|
| `hikari.maximumPoolSize` | `ojp.connection.pool.maximumPoolSize` |
| `hikari.minimumIdle` | `ojp.connection.pool.minimumIdle` |
| `hikari.connectionTimeout` | `ojp.connection.pool.connectionTimeout` |
| `hikari.idleTimeout` | `ojp.connection.pool.idleTimeout` |
| `hikari.maxLifetime` | `ojp.connection.pool.maxLifetime` |
| `hikari.connectionTestQuery` | `ojp.connection.pool.validationQuery` |

### Provider-Specific Properties

Pass provider-specific properties using the prefix `ojp.datasource.properties.*`:

```properties
# DBCP-specific properties
ojp.datasource.properties.testOnBorrow=true
ojp.datasource.properties.testWhileIdle=true
ojp.datasource.properties.numTestsPerEvictionRun=3
```

## DBCP Provider Configuration

The DBCP provider (`ojp-datasource-dbcp`) maps `PoolConfig` to DBCP `BasicDataSource`:

| PoolConfig Field | DBCP Property |
|------------------|---------------|
| `url` | `url` |
| `username` | `username` |
| `password` | `password` |
| `driverClassName` | `driverClassName` |
| `maxPoolSize` | `maxTotal` |
| `minIdle` | `minIdle` |
| `connectionTimeoutMs` | `maxWait` |
| `idleTimeoutMs` | `minEvictableIdleTimeMillis` |
| `maxLifetimeMs` | `maxConnLifetimeMillis` |
| `validationQuery` | `validationQuery` |
| `autoCommit` | `defaultAutoCommit` |

### DBCP-Specific Settings

The DBCP provider automatically configures:

- `maxIdle` = `maxPoolSize` (connections won't be evicted below this)
- `testOnBorrow` = true (when validationQuery is set)
- `testWhileIdle` = true (when validationQuery is set)
- `timeBetweenEvictionRunsMillis` = 30000 (30 seconds)
- `numTestsPerEvictionRun` = 3

## Default Values

| Setting | Default |
|---------|---------|
| `maxPoolSize` | 10 |
| `minIdle` | 2 |
| `connectionTimeoutMs` | 30000 (30 seconds) |
| `idleTimeoutMs` | 600000 (10 minutes) |
| `maxLifetimeMs` | 1800000 (30 minutes) |
| `autoCommit` | true |

## Examples

### High-Performance Configuration

```java
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://localhost:5432/mydb")
    .username("user")
    .password("secret")
    .maxPoolSize(50)
    .minIdle(10)
    .connectionTimeoutMs(5000)  // Fail fast
    .validationQuery("SELECT 1")
    .property("cachePrepStmts", "true")
    .property("prepStmtCacheSize", "500")
    .build();
```

### Read-Replica Configuration

```java
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://replica.example.com:5432/mydb")
    .username("readonly")
    .password("secret")
    .maxPoolSize(20)
    .minIdle(2)
    .idleTimeoutMs(60000)  // Shorter idle timeout for read replicas
    .autoCommit(true)      // Read-only queries
    .build();
```

### Connection Pool with Secret Manager

```java
// AWS Secrets Manager example
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://prod.example.com:5432/mydb")
    .username("app_user")
    .passwordSupplier(() -> {
        // Retrieve password from AWS Secrets Manager
        return awsSecretsManager.getSecretString("prod/db/password").toCharArray();
    })
    .maxPoolSize(30)
    .minIdle(5)
    .build();
```
