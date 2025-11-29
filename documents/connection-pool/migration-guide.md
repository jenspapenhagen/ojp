# Migration Guide: Connection Pool Abstraction

This guide helps you migrate from the current HikariCP-specific implementation to the new connection pool abstraction.

## Overview

The OJP connection pool abstraction provides:
- Provider-agnostic configuration
- Easy switching between pool implementations
- Secure credential handling
- ServiceLoader-based provider discovery

## Migration Steps

### Step 1: Update Dependencies

Add the datasource API module to your project:

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-datasource-api</artifactId>
    <version>${ojp.version}</version>
</dependency>
```

If using DBCP provider:

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-datasource-dbcp</artifactId>
    <version>${ojp.version}</version>
</dependency>
```

### Step 2: Update Code

#### Before (Direct HikariCP)

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

HikariConfig config = new HikariConfig();
config.setJdbcUrl(url);
config.setUsername(username);
config.setPassword(password);
config.setMaximumPoolSize(20);
config.setMinimumIdle(5);
config.setConnectionTimeout(30000);

HikariDataSource dataSource = new HikariDataSource(config);

// Later...
dataSource.close();
```

#### After (Pool Abstraction)

```java
import org.openjproxy.datasource.PoolConfig;
import org.openjproxy.datasource.ConnectionPoolProviderRegistry;

PoolConfig config = PoolConfig.builder()
    .url(url)
    .username(username)
    .password(password)
    .maxPoolSize(20)
    .minIdle(5)
    .connectionTimeoutMs(30000)
    .build();

DataSource dataSource = ConnectionPoolProviderRegistry.createDataSource(config);

// Later...
ConnectionPoolProviderRegistry.closeDataSource("dbcp", dataSource);
```

### Step 3: Update Configuration Properties

#### Before (ojp.properties)

```properties
hikari.maximumPoolSize=20
hikari.minimumIdle=5
hikari.connectionTimeout=30000
```

#### After (ojp.properties)

```properties
# Provider selection (optional)
ojp.datasource.provider=dbcp

# Pool configuration
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=30000
```

## Property Mapping Reference

| Old Property | New Property | Notes |
|--------------|--------------|-------|
| `hikari.jdbcUrl` | `url` (programmatic) | Use URL directly |
| `hikari.username` | `username` (programmatic) | Use username directly |
| `hikari.password` | `password` (programmatic) | Use password directly |
| `hikari.maximumPoolSize` | `ojp.connection.pool.maximumPoolSize` | Same behavior |
| `hikari.minimumIdle` | `ojp.connection.pool.minimumIdle` | Same behavior |
| `hikari.connectionTimeout` | `ojp.connection.pool.connectionTimeout` | Same behavior |
| `hikari.idleTimeout` | `ojp.connection.pool.idleTimeout` | Same behavior |
| `hikari.maxLifetime` | `ojp.connection.pool.maxLifetime` | Same behavior |
| `hikari.connectionTestQuery` | `ojp.connection.pool.validationQuery` | Renamed |

## Backward Compatibility

The new abstraction is designed for backward compatibility:

1. **Default provider**: If no provider is specified, the system defaults to HikariCP (when available)
2. **Legacy properties**: Legacy `hikari.*` properties continue to work
3. **Existing configurations**: No changes required unless switching providers

## Selecting a Provider

### Programmatically

```java
// Use specific provider
DataSource ds = ConnectionPoolProviderRegistry.createDataSource("dbcp", config);

// Use default (highest priority available)
DataSource ds = ConnectionPoolProviderRegistry.createDataSource(config);
```

### Via Configuration

```properties
# In ojp.properties
ojp.datasource.provider=dbcp
```

### Via Environment Variable

```bash
export OJP_DATASOURCE_PROVIDER=dbcp
```

## Provider Priority

When no provider is specified, the registry selects the provider with the highest priority:

| Provider | Priority |
|----------|----------|
| HikariCP | 100 (default) |
| DBCP | 10 |
| Custom | 0 (default) |

## Troubleshooting Migration

### "Unknown provider" error

Ensure the provider module is on the classpath:
- For DBCP: Add `ojp-datasource-dbcp` dependency
- For HikariCP: Add HikariCP dependency and provider module

### Pool not closing properly

Always close DataSources using the registry:

```java
// Correct
ConnectionPoolProviderRegistry.closeDataSource("dbcp", dataSource);

// Incorrect - provider doesn't know about the close
((BasicDataSource) dataSource).close();
```

### Statistics not available

Ensure you're using the correct provider ID when getting statistics:

```java
Map<String, Object> stats = ConnectionPoolProviderRegistry.getStatistics("dbcp", dataSource);
```

## Future Integration with OJP Server

The connection pool abstraction is designed to be integrated into the OJP server. A future update will:

1. Replace direct HikariCP usage in `StatementServiceImpl` with the abstraction
2. Allow server-side provider selection via configuration
3. Support per-datasource provider selection

## Support

For questions or issues with migration, please open an issue on the [OJP GitHub repository](https://github.com/Open-J-Proxy/ojp).
