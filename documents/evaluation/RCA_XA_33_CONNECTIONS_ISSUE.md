# RCA: XA Opening 33 Connections Instead of 20

**Date**: 2025-12-24  
**Issue**: Multinode XA test shows 33 connections in PostgreSQL instead of expected 20

## Expected Behavior

With 2 OJP servers and `minIdle=20`, `maxPoolSize=22`:
- MultinodePoolCoordinator should divide: 20 / 2 = 10 per server
- Server 1: creates pool with minIdle=10 → opens 10 connections
- Server 2: creates pool with minIdle=10 → opens 10 connections  
- **Total: 20 connections** ✓

## Actual Behavior

Test observes 33 connections total, suggesting each server is opening ~16-17 connections instead of 10.

## Code Analysis

### 1. Configuration (ojp.properties)
```properties
multinode.ojp.connection.pool.maximumPoolSize=22
multinode.ojp.connection.pool.minimumIdle=20
```
✓ **Correct values**

### 2. XA Pool Creation Code (StatementServiceImpl.java:415-445)
```java
int maxPoolSize = dsConfig.getMaximumPoolSize();  // 22
int minIdle = dsConfig.getMinimumIdle();           // 20

List<String> serverEndpoints = connectionDetails.getServerEndpointsList();
if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
    MultinodePoolCoordinator.PoolAllocation allocation = 
            ConnectionPoolConfigurer.getPoolCoordinator().calculatePoolSizes(
                    connHash, maxPoolSize, minIdle, serverEndpoints);
    
    maxPoolSize = allocation.getCurrentMaxPoolSize();  // Should be 11
    minIdle = allocation.getCurrentMinIdle();          // Should be 10
}
```
✓ **Code looks correct**

### 3. MultinodePoolCoordinator Logic
```java
public PoolAllocation calculatePoolSizes(..., List<String> serverEndpoints) {
    if (serverEndpoints == null || serverEndpoints.isEmpty()) {
        return new PoolAllocation(requestedMaxPoolSize, requestedMinIdle, 1);
    }
    
    int serverCount = serverEndpoints.size();
    return new PoolAllocation(requestedMaxPoolSize, requestedMinIdle, serverCount);
}

public int getCurrentMinIdle() {
    return (int) Math.ceil((double) originalMinIdle / healthyServers);
}
```

With `serverCount=2`, `healthyServers=2`, `originalMinIdle=20`:
- Result: `ceil(20/2) = 10` ✓

✓ **Logic is correct**

## Hypothesis

Since the code logic is correct, the issue must be with the DATA being passed:

### Hypothesis 1: serverEndpoints List is Empty/Null
If `connectionDetails.getServerEndpointsList()` returns null or empty list:
- Coordination is SKIPPED
- Each server uses raw values: minIdle=20, maxPoolSize=22
- Each server opens 20 connections
- Total: 40 connections (but test shows 33, so maybe some aren't idle yet)

### Hypothesis 2: serverEndpoints List Has Wrong Size
If the list has only 1 endpoint per server call:
- Coordination calculates: 20/1 = 20 per server
- Each server opens 20 connections
- Total: 40 connections

### Hypothesis 3: Client Not Sending serverEndpoints
The client (OjpXAConnection) might not be adding serverEndpoints to ConnectionDetails:

```java
// In OjpXAConnection.java:96-98
if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
    connBuilder.addAllServerEndpoints(serverEndpoints);
}
```

If `serverEndpoints` is null/empty at client side, server never receives the list.

## Debug Logging Added

Lines 419-445 in StatementServiceImpl.java now log:
```java
log.info("XA pool BEFORE multinode coordination for {}: requested max={}, min={}", 
        connHash, maxPoolSize, minIdle);

log.info("XA serverEndpoints list: null={}, size={}, endpoints={}", 
        serverEndpoints == null, 
        serverEndpoints == null ? 0 : serverEndpoints.size(),
        serverEndpoints);

if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
    log.info("XA multinode pool coordination for {}: {} servers, divided sizes: max={}, min={}", 
            connHash, serverEndpoints.size(), maxPoolSize, minIdle);
} else {
    log.info("XA multinode coordination SKIPPED for {}: serverEndpoints null or empty", connHash);
}

log.info("XA pool AFTER multinode coordination for {}: final max={}, min={}", 
        connHash, maxPoolSize, minIdle);
```

## Next Steps

1. Analyze CI logs from commit 0abf0a4 to see actual serverEndpoints list
2. Determine if problem is:
   - Client not sending list
   - Server receiving empty/wrong list  
   - Coordination being skipped for another reason
3. Fix the root cause

## Comparison with Non-XA

Non-XA uses identical code path (lines 320-338) and works correctly.
This suggests XA-specific initialization might be the issue.

✓ **Code Trace Complete**:
1. Test URL: `jdbc:ojp[localhost:10591(multinode),localhost:10592(multinode)]_postgresql://localhost:5432/defaultdb`
2. `MultinodeUrlParser.getOrCreateStatementService()` creates serverEndpoints list: `["localhost:10591", "localhost:10592"]`
3. `OjpXADataSource` stores this list (line 120)
4. `OjpXAConnection` receives list in constructor (line 53, 60)
5. `OjpXAConnection.getOrCreateSession()` adds list to ConnectionDetails (lines 96-98)
6. Server receives ConnectionDetails with serverEndpoints list
7. StatementServiceImpl extracts list (line 423)
8. If not empty, applies MultinodePoolCoordinator (lines 429-439)

**All code paths look correct!** Debug logs will reveal runtime behavior.

## Debug Logs Added (Commit 0abf0a4)

```java
// Lines 419-445 in StatementServiceImpl.java
log.info("XA pool BEFORE multinode coordination for {}: requested max={}, min={}", 
        connHash, maxPoolSize, minIdle);

log.info("XA serverEndpoints list: null={}, size={}, endpoints={}", 
        serverEndpoints == null, 
        serverEndpoints == null ? 0 : serverEndpoints.size(),
        serverEndpoints);

if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
    log.info("XA multinode pool coordination for {}: {} servers, divided sizes: max={}, min={}", 
            connHash, serverEndpoints.size(), maxPoolSize, minIdle);
} else {
    log.info("XA multinode coordination SKIPPED for {}: serverEndpoints null or empty", connHash);
}

log.info("XA pool AFTER multinode coordination for {}: final max={}, min={}", 
        connHash, maxPoolSize, minIdle);
```

These logs will show the exact state at each step.
