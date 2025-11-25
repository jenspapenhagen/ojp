# Server Recovery and Connection Redistribution

## Overview

This document describes the server failure handling and connection redistribution feature for OJP multinode XA deployments. When an OJP server fails, the system **immediately invalidates all sessions and connections** for that server. When the server recovers, connections are automatically rebalanced across all available servers.

## Problem Statement

In multinode XA deployments, when a server fails:
1. Sessions bound to that server become stale (server loses in-memory session state)
2. Connections to that server are still held by connection pools
3. When the failed server recovers, stale sessions cause "Connection not found" errors
4. Load imbalance occurs until application restart

**Example without immediate invalidation:**
```
Initial:  Server1=10, Server2=10, Server3=10 connections
Failure:  Server2=0 (failed), sessions still bound to Server2
Recovery: Server2=0 (healthy but sessions are stale), "Connection not found" errors
```

## Solution

The solution implements a **two-phase strategy**:

### Phase 1: Immediate Invalidation on Server Failure
When a server becomes unhealthy (detected via connection-level errors):
1. **Clear all session bindings** from `sessionToServerMap` for that server
2. **Mark all connections invalid** via `markForceInvalid()` for connections bound to that server
3. **Close connections** to force pool replacement
4. **No attempt** to call `terminateSession()` on the failed server (it's unreachable)
5. **Log invalidation details** - Number of sessions and connections invalidated

### Phase 2: Connection Rebalancing on Server Recovery
When a server recovers:
1. **Calculate total connections** across all healthy servers
2. **Calculate target per server**: `totalConnections / totalHealthyServers`
3. **Mark excess connections** on overloaded servers as invalid
4. **Connection pools naturally rebalance** as Atomikos closes invalid connections and creates new ones

**Example with immediate invalidation:**
```
Initial:  Server1=10, Server2=10, Server3=10 connections
Failure:  Server2=0 (failed), sessions INVALIDATED immediately, connections closed
Adapted:  Server1=15, Server3=15 (new connections created)
Recovery: Server2=0 (healthy), rebalance triggered
After:    Server1=10, Server2=10, Server3=10 (evenly distributed)
```

### XA vs Non-XA Behavior Differences

**XA Mode:**
- Uses `connectToSingleServer()` to connect to ONE server per connection
- Tracks connections via ConnectionTracker for load-aware selection
- **Immediate session/connection invalidation on server failure**
- Supports automatic redistribution when servers recover
- New connections are routed to least-loaded server
- Session stickiness: operations must use the same server as the connection

**Non-XA Mode:**
- Uses `connectToAllServers()` to connect to ALL healthy servers
- Does NOT track connections (ConnectionTracker not used)
- Does NOT support automatic redistribution
- Uses round-robin selection for all operations (not load-aware)
- Connection pools manage distribution naturally via their own logic

**Why the difference?**
- XA transactions require session stickiness to a single server
- Non-XA can use any server for any operation, so connection pools handle distribution
- In non-XA, the driver ensures all servers know about datasource config by connecting to all

## Architecture

### Components

#### 1. HealthCheckConfig
Loads configuration from `ojp.properties`:
- `ojp.health.check.interval` - How often to check unhealthy servers (default: 5000ms / 5 seconds)
- `ojp.health.check.threshold` - Min time before retrying unhealthy server (default: 5000ms / 5 seconds)
- `ojp.health.check.timeout` - Connection timeout for health checks (default: 5000ms / 5 seconds)
- `ojp.redistribution.enabled` - Enable/disable redistribution (default: true)
- `ojp.redistribution.idleRebalanceFraction` - Fraction of excess connections to rebalance (default: 1.0)
- `ojp.redistribution.maxClosePerRecovery` - Maximum connections to close per recovery event (default: 100)
- `ojp.loadaware.selection.enabled` - Enable load-aware server selection (default: true, XA mode only)

#### 2. ConnectionTracker
Tracks active connections and their bound servers using `ConcurrentHashMap`:
- `register(Connection, ServerEndpoint)` - Registers new connection
- `unregister(Connection)` - Removes closed connection
- `getDistribution()` - Returns map of servers to connections (only called during redistribution)
- `getCounts()` - Returns connection counts per server

#### 3. HealthCheckValidator
Validates server health by attempting direct connection:
- Creates test connection to server
- Returns true if server responds, false otherwise
- Closes test connection after validation

#### 4. ConnectionRedistributor
Implements balanced redistribution algorithm:
- Calculates target connections per server
- Identifies overloaded servers
- Marks connections for closure using round-robin across overloaded servers
- Logs warnings if redistribution incomplete

#### 5. MultinodeConnectionManager (Enhanced)
Integrates health check and redistribution:
- Uses `AtomicLong` timestamp for time-based triggering
- Calls `tryTriggerHealthCheck()` on each connection borrow
- Only one thread executes health check (via `compareAndSet`)
- Triggers redistribution when servers recover

#### 6. Connection (Enhanced)
Supports forced invalidation:
- `markForceInvalid()` - Marks connection for removal
- `isForceInvalid()` - Checks if marked
- `checkValid()` - Throws `SQLNonTransientConnectionException` with SQLState 08006
- `isValid()` - Returns false when marked invalid

## Flow Diagram

```
1. Connection-level error occurs (UNAVAILABLE, io exception, etc.)
2. MultinodeStatementService detects error and calls handleServerFailure()
3. Server marked unhealthy
4. Session/Connection Invalidation (XA mode only):
   ├─ Clear all session bindings for failed server
   ├─ Mark all connections for failed server as invalid
   ├─ Close connections to force pool replacement
   └─ Log invalidation details
5. Pool detects invalid connections (via isValid() or 08006)
6. Pool closes invalid connections permanently
7. Pool creates new connections to replace closed ones
8. New connections distributed via load-aware selection to healthy servers

--- When server recovers ---

9. Health check detects server recovery
10. Rebalancing triggered (XA mode only):
    ├─ Calculate target distribution
    ├─ Identify overloaded servers
    ├─ Mark excess connections for closure (balanced)
    └─ Marked connections throw SQLState 08006 on next use
11. Pool creates new connections distributed evenly
12. Load rebalanced!
```

### XA Session Invalidation Details

When a server fails (connection-level error detected):

1. **Detection**: Connection error triggers `handleServerFailure()`
2. **Immediate Session Invalidation** (XA mode only):
   - Identify all sessions bound to failed server in `sessionToServerMap`
   - Remove session bindings (clears client-side cache)
   - Mark all connections to failed server as invalid
   - Close connections to force pool replacement
   - Log invalidated sessions for debugging
3. **Result**: 
   - Stale sessions are immediately cleared
   - Connection pools detect invalid connections
   - New connections create fresh sessions on healthy servers
   - No "Connection not found" errors when server recovers

**Note**: Only affects XA mode where sessions are tracked. Non-XA mode doesn't maintain session bindings.

## Configuration

### ojp.properties

```properties
# Health check interval (milliseconds) - how often to check unhealthy servers for recovery
# Default: 5000 (5 seconds)
# Recommended: 5000-30000 (5-30 seconds) for production
# Lower values detect recovery faster but increase overhead
ojp.health.check.interval=5000

# Health check threshold (milliseconds) - min time before retrying failed server
# Default: 5000 (5 seconds)
# Recommended: Match interval or slightly higher to avoid excessive retries
ojp.health.check.threshold=5000

# Health check timeout (milliseconds) - connection timeout for health checks
# Default: 5000 (5 seconds)
# Recommended: 3000-10000 based on network latency
ojp.health.check.timeout=5000

# Enable/disable automatic redistribution on recovery
# Default: true
# Set to false to disable connection redistribution when servers recover
ojp.redistribution.enabled=true

# Fraction of excess connections to rebalance per recovery event
# Default: 1.0 (rebalance 100% of excess)
# Lower values enable gradual rebalancing over multiple health check cycles
ojp.redistribution.idleRebalanceFraction=1.0

# Maximum connections to close per recovery event
# Default: 100
# Prevents closing too many connections at once in large deployments
ojp.redistribution.maxClosePerRecovery=100

# Enable/disable load-aware server selection (XA mode only)
# Default: true
# When enabled, selects server with fewest connections
# When disabled or in non-XA mode, uses round-robin
ojp.loadaware.selection.enabled=true
```

## Configuration Parameters Explained

### ojp.health.check.interval

**Purpose:** Controls how often the driver checks for server recovery.

**Default:** 5000ms (5 seconds)

**When to adjust:**
- **Decrease (1000-3000ms):** For faster recovery detection in dev/test environments
- **Increase (10000-30000ms):** To reduce overhead in stable production environments
- **Keep default:** For most production use cases

**Example effects:**
- `1000ms`: Health checks run frequently, server recovery detected quickly (1-2s typical)
- `5000ms` (default): Balanced - recovery typically detected within 5-10s
- `30000ms`: Lower overhead, but recovery detection takes 30-60s

**Note:** Recovery detection time = up to 2× interval in worst case (if server recovers just after a check). With 5s interval, typically 5-10s.

**Impact on system:**
- Lower values: Faster recovery detection, slightly higher CPU/network usage
- Higher values: Slower recovery detection, minimal overhead

### ojp.health.check.threshold

**Purpose:** Minimum time to wait before retrying a server that previously failed a health check.

**Default:** 5000ms (5 seconds)

**When to adjust:**
- **Match interval:** Recommended for most cases
- **Higher than interval:** If servers tend to stay down for longer periods
- **Lower than interval:** Not recommended - may cause excessive retry attempts

**Example effects:**
- `5000ms` (default): After a server fails, it won't be re-checked for 5s
- `60000ms`: After a server fails, it won't be re-checked for 60s (good for planned maintenance)

**Relationship with interval:**
- Threshold filters which failed servers to check during each health check cycle
- If threshold = interval: Failed servers eligible for retry at every check
- If threshold > interval: Failed servers wait longer before being rechecked
- If threshold < interval: Failed servers may be checked multiple times per threshold period
- Best practice: Set threshold ≥ interval to avoid excessive retry attempts

### ojp.health.check.timeout

**Purpose:** Maximum time to wait for a health check connection attempt to complete.

**Default:** 5000ms (5 seconds)

**When to adjust:**
- **Decrease (1000-3000ms):** For low-latency networks where fast response expected
- **Increase (10000-15000ms):** For high-latency networks or slow-starting servers
- **Keep default:** For most production networks

**Example effects:**
- `1000ms`: Server must respond within 1 second or marked unhealthy
- `5000ms` (default): Balanced for typical network latency
- `15000ms`: Tolerates slow networks/server startup, but slow to detect failures

**Symptoms of incorrect value:**
- Too low: Healthy servers incorrectly marked as failed due to normal latency
- Too high: Takes longer to detect genuinely failed servers

**Best practice:** Set to 2-3x your typical network round-trip time

### Configuration Examples by Scenario

**Development/Testing:**
```properties
ojp.health.check.interval=2000        # Fast detection
ojp.health.check.threshold=2000       # Quick retry
ojp.health.check.timeout=3000         # Tolerate local network
```

**Production - High Availability:**
```properties
ojp.health.check.interval=5000        # Balanced detection (default)
ojp.health.check.threshold=5000       # Match interval (default)
ojp.health.check.timeout=5000         # Standard timeout (default)
```

**Production - Stable Environment:**
```properties
ojp.health.check.interval=30000       # Lower overhead
ojp.health.check.threshold=30000      # Match longer interval
ojp.health.check.timeout=10000        # Allow for occasional slowness
```

**High-Latency Network:**
```properties
ojp.health.check.interval=10000       # Check less frequently
ojp.health.check.threshold=10000      # Match interval
ojp.health.check.timeout=15000        # Account for network latency
```

## Performance

### Overhead

**Per Connection Borrow:**
- Time check: ~50 nanoseconds
- Timestamp comparison only
- No locks, no I/O

**Health Check (every 5s by default):**
- Validates only unhealthy servers
- Single thread execution (via compareAndSet)
- ~100-300ms per server validation
- Non-blocking (doesn't affect connection attempts)

### Scalability

**XA Mode:**
- Works efficiently with 10-50 connections (typical)
- ConcurrentHashMap iteration very fast for this size
- Only iterates map during redistribution (infrequent)
- Load-aware selection adds minimal overhead

**Non-XA Mode:**
- No connection tracking overhead
- Round-robin selection is extremely fast
- Connection pools handle distribution naturally

## Testing

### Manual Testing

1. Start 3 OJP servers
2. Create connection pool (Atomikos/HikariCP/DBCP)
3. Generate load to create 30 connections (10 per server)
4. Stop server2
5. Verify connections redistribute to server1 and server3 (15 each)
6. Wait 30+ seconds
7. Start server2
8. Verify connections rebalance after 30-60 seconds (10 per server)

### Monitoring

Check logs for:
```
# When server failure is detected (connection-level error)
INFO  MultinodeConnectionManager - XA Server failure: Invalidated N session(s) and closed M connection(s) for failed server <address>

# When server recovers
INFO  MultinodeConnectionManager - Server <address> has recovered
INFO  XAConnectionRedistributor - Starting XA connection redistribution for N recovered server(s) among M healthy servers
INFO  XAConnectionRedistributor - XA connection redistribution complete: marked N connections as invalid for pool replacement
```

### XA Session Invalidation Logs

When a server fails (connection-level error detected), you'll see:
```
INFO  MultinodeConnectionManager - XA Server failure: Invalidated 5 session(s) and closed 5 connection(s) for failed server server1:1059
```

When a server recovers, you'll see:
```
INFO  MultinodeConnectionManager - Server server1:1059 has recovered
INFO  XAConnectionRedistributor - Starting XA connection redistribution for 1 recovered server(s) among 3 healthy servers
INFO  XAConnectionRedistributor - Total XA connections: 30, Target per server: 10
INFO  XAConnectionRedistributor - XA connection redistribution complete: marked 10 connections as invalid for pool replacement
```

### Verification Queries

```java
// Get connection counts per server
Map<ServerEndpoint, Integer> counts = 
    connectionManager.getConnectionTracker().getCounts();
    
// Check if redistribution enabled
boolean enabled = healthCheckConfig.isRedistributionEnabled();
```

## Troubleshooting

### Server Not Recovering

**Symptoms:** Server marked healthy but no connections redistributed

**Possible Causes:**
1. Health check disabled: Check `ojp.redistribution.enabled=true`
2. Threshold not met: Wait for `ojp.health.check.interval` to elapse
3. Connections in use: Wait for connections to be returned to pool
4. Pool not validating: Check pool validation configuration

**Solutions:**
- Enable logging: `log.level.org.openjproxy.grpc.client=DEBUG`
- Check configuration: Review ojp.properties
- Monitor pool: Check pool statistics

### Partial Redistribution

**Symptoms:** Warning "Redistribution incomplete: N connections not marked"

**Possible Causes:**
1. Active transactions: Connections in use can't be marked
2. Low traffic: Connections not being borrowed
3. Pool behavior: Some pools may not return connections immediately

**Solutions:**
- Wait for traffic: More borrows = more opportunities to mark
- Check transactions: Ensure transactions complete
- Increase interval: Give more time between checks

### Performance Impact

**Symptoms:** Slowdown during redistribution

**Possible Causes:**
1. Too many connections: Thousands of connections (unusual for XA)
2. Frequent health checks: Interval too short

**Solutions:**
- Increase interval: `ojp.health.check.interval=60000` (60s)
- Increase timeout: `ojp.health.check.timeout=10000` (10s)

### "Connection not found" Errors After Server Recovery

**Symptoms:** After server restart, queries fail with "Connection not found for this sessionInfo"

**Root Cause (without proper invalidation):** 
- Server loses session state when killed (in-memory storage)
- Client-side session bindings persist
- Queries sent with old session UUIDs that don't exist on resurrected server

**Solution (Automatic in XA Mode):**
The system automatically invalidates XA sessions **immediately when a server fails** (not on recovery):
1. Connection-level error is detected (UNAVAILABLE, io exception, etc.)
2. All sessions bound to that server are immediately invalidated
3. All connections bound to that server are closed
4. Connection pools create new connections to healthy servers
5. When server recovers, no stale sessions exist - "Connection not found" errors are prevented

**Manual Verification:**
- Check logs for "XA Server failure: Invalidated N session(s)" messages
- Verify XA redistributor is enabled: `manager.setXaConnectionRedistributor()`
- Ensure connection pool validates connections periodically

**For Non-XA Mode:**
Session invalidation only affects XA mode. In non-XA mode, applications should implement retry logic for transient connection errors.

## Best Practices

### Configuration

1. **Set appropriate intervals**
   - Start with 5s interval (default) for most environments
   - Increase for stable environments (10-30s) to reduce overhead
   - Decrease for testing only (1-3s for faster feedback)
   - Never set below 1 second to avoid excessive overhead

2. **Match threshold to interval**
   - Set `threshold` = `interval` for most cases
   - Only increase threshold if servers tend to stay down longer
   - Never set threshold < interval

3. **Enable logging during initial deployment**
   - Set `log.level.org.openjproxy.grpc.client=INFO`
   - Monitor failure and recovery events
   - Verify redistribution working (XA mode only)

4. **Consider your deployment mode**
   - **XA mode**: Benefits from load-aware selection and automatic redistribution
   - **Non-XA mode**: Uses round-robin, relies on connection pool for distribution

### Operations

1. **Monitor connection distribution**
   - Check logs for redistribution events (XA mode only)
   - Verify balanced distribution after recovery (XA mode only)
   - Alert on permanent imbalance
   - In non-XA mode, let connection pools handle distribution

2. **Plan for downtime**
   - Sessions are invalidated immediately on server failure
   - Redistribution automatic in XA mode (no manual intervention)
   - Expect 5-10 seconds delay for rebalancing after recovery (based on default 5s interval, worst case 10s)
   - Non-XA mode: Connection pools will naturally redistribute

3. **Test recovery scenarios**
   - Test server failure and recovery
   - Verify redistribution working (XA mode)
   - Validate performance acceptable
   - Monitor both XA and non-XA deployments separately

## Limitations

1. **XA Mode Only Features**
   - Connection tracking and load-aware selection only work in XA mode
   - Automatic redistribution only applies to XA deployments
   - Non-XA mode uses round-robin and relies on connection pools for distribution

2. **Requires connection pool validation**
   - Works with pools that validate connections (isValid() or test query)
   - Most modern pools support this (Atomikos, HikariCP, DBCP)

3. **Gradual redistribution (XA mode)**
   - Connections marked as borrowed
   - Takes time for all connections to be redistributed
   - Usually completes within few minutes under normal traffic

4. **Cannot interrupt transactions**
   - Active transactions not interrupted
   - Connection marked on return to pool
   - Ensures transaction safety

5. **Multinode only**
   - Feature only works in multinode deployments
   - Single-node deployments unaffected

## Future Enhancements

Potential improvements for future versions:

1. **Metrics and monitoring**
   - Expose JMX metrics for redistribution
   - Track failure detection and recovery success rate
   - Monitor redistribution completion time

2. **Configurable redistribution strategy**
   - Immediate (current): Close connections immediately
   - Gradual: Reduce connection lifetime gradually
   - Manual: Trigger redistribution via API

3. **Admin API**
   - Manually trigger health checks
   - Force redistribution
   - View current distribution

## References

- [JDBC Connection Pool Validation](https://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html#isValid-int-)
- [SQLState Codes](https://en.wikipedia.org/wiki/SQLSTATE)
- [Atomikos Connection Pooling](https://www.atomikos.com/Documentation/ConnectionPoolConfiguration)
- [HikariCP Connection Testing](https://github.com/brettwooldridge/HikariCP#connection-testing)
