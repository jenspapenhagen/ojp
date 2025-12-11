# Multinode Sporadic Failure Fixes - Implementation Guide

## Overview

This guide provides step-by-step instructions for implementing the fixes identified in the multinode sporadic failure analysis. The fixes are ordered by priority to allow incremental improvements.

## Prerequisites

Before implementing fixes:
1. Review `MULTINODE_SPORADIC_FAILURE_ANALYSIS.md` for detailed root cause analysis
2. Ensure you have a test environment with multinode setup (3+ OJP servers)
3. Have access to multinode integration tests for verification
4. Set up logging at DEBUG level for affected packages

## Priority 1 Fixes (Critical - Implement First)

### Fix 1.1: Atomic Server Health State

**Affected File:** `ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/ServerEndpoint.java`

**Current Issue:** Two volatile fields (healthy, lastFailureTime) updated separately, causing inconsistent reads.

**Implementation Steps:**

1. Create inner HealthState class:
```java
private static class HealthState {
    final boolean healthy;
    final long lastFailureTime;
    
    HealthState(boolean healthy, long lastFailureTime) {
        this.healthy = healthy;
        this.lastFailureTime = lastFailureTime;
    }
}
```

2. Replace volatile fields with AtomicReference:
```java
// OLD:
// private volatile boolean healthy = true;
// private volatile long lastFailureTime = 0;

// NEW:
private final AtomicReference<HealthState> healthState = 
    new AtomicReference<>(new HealthState(true, 0));
```

3. Update all methods to use AtomicReference:
```java
public boolean isHealthy() {
    return healthState.get().healthy;
}

public long getLastFailureTime() {
    return healthState.get().lastFailureTime;
}

public void setHealthy(boolean healthy) {
    HealthState current = healthState.get();
    healthState.set(new HealthState(healthy, current.lastFailureTime));
}

public void setLastFailureTime(long lastFailureTime) {
    HealthState current = healthState.get();
    healthState.set(new HealthState(current.healthy, lastFailureTime));
}

public void markHealthy() {
    healthState.set(new HealthState(true, 0));
}

public void markUnhealthy() {
    healthState.set(new HealthState(false, System.currentTimeMillis()));
}

// Add convenience method for atomic read of both values
public HealthState getHealthState() {
    return healthState.get();
}
```

4. Update callers that read both fields to use getHealthState():
```java
// In MultinodeConnectionManager.connectToAllServers()
HealthState state = server.getHealthState();
if (!state.healthy) {
    long currentTime = System.currentTimeMillis();
    if ((currentTime - state.lastFailureTime) > retryDelayMs) {
        // Attempt recovery
    }
}
```

**Testing:**
- Run multinode integration tests 10+ times to verify no failures
- Add stress test with concurrent health updates and reads
- Verify no inconsistent state logged

---

### Fix 1.2: Prevent Concurrent Health Check Execution

**Affected File:** `ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java`

**Current Issue:** Multiple threads can execute performHealthCheck() concurrently.

**Implementation Steps:**

1. Add healthCheckInProgress flag:
```java
private final AtomicBoolean healthCheckInProgress = new AtomicBoolean(false);
```

2. Update tryTriggerHealthCheck():
```java
private void tryTriggerHealthCheck() {
    long now = System.currentTimeMillis();
    long lastCheck = lastHealthCheckTimestamp.get();
    long elapsed = now - lastCheck;
    
    // Only check if interval has passed
    if (elapsed >= healthCheckConfig.getHealthCheckIntervalMs()) {
        // Try to acquire health check lock - prevents concurrent execution
        if (healthCheckInProgress.compareAndSet(false, true)) {
            try {
                // Update timestamp BEFORE starting (prevents other threads from trying)
                lastHealthCheckTimestamp.set(now);
                performHealthCheck();
            } catch (Exception e) {
                log.warn("Health check failed: {}", e.getMessage());
            } finally {
                // Always release lock
                healthCheckInProgress.set(false);
            }
        } else {
            log.debug("Health check already in progress, skipping");
        }
    }
}
```

**Testing:**
- Add test that triggers tryTriggerHealthCheck() from 10+ threads simultaneously
- Verify only one thread executes performHealthCheck()
- Check logs for "Health check already in progress" messages
- Verify no duplicate session invalidation logs

---

### Fix 1.3: Synchronized Cluster Health Generation

**Affected File:** `ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java`

**Current Issue:** Cluster health generated without synchronization, allowing inconsistent snapshots.

**Implementation Steps:**

1. Make generateClusterHealth() synchronized:
```java
public synchronized String generateClusterHealth() {
    return serverEndpoints.stream()
            .map(endpoint -> endpoint.getAddress() + "(" + (endpoint.isHealthy() ? "UP" : "DOWN") + ")")
            .collect(Collectors.joining(";"));
}
```

**Alternative (Better Performance):**
```java
public String generateClusterHealth() {
    // Capture all health states atomically first
    Map<ServerEndpoint, Boolean> healthSnapshot = new HashMap<>();
    for (ServerEndpoint endpoint : serverEndpoints) {
        healthSnapshot.put(endpoint, endpoint.isHealthy());
    }
    
    // Build string from snapshot (no concurrent reads during iteration)
    return serverEndpoints.stream()
            .map(endpoint -> endpoint.getAddress() + "(" + 
                 (healthSnapshot.get(endpoint) ? "UP" : "DOWN") + ")")
            .collect(Collectors.joining(";"));
}
```

**Testing:**
- Add test that generates cluster health while concurrently updating server states
- Parse generated health strings and verify they represent valid system states
- Verify no inconsistent states like "server marked healthy after it failed"

---

## Priority 2 Fixes (High - Implement Next)

### Fix 2.1: Atomic Health Change Detection

**Affected File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/ClusterHealthTracker.java`

**Current Issue:** hasHealthChanged() has check-then-act race condition.

**Implementation Steps:**

1. Replace the method with atomic version:
```java
public boolean hasHealthChanged(String connHash, String currentClusterHealth) {
    if (connHash == null || connHash.isEmpty()) {
        return false;
    }
    
    String normalizedCurrent = currentClusterHealth == null ? "" : currentClusterHealth;
    
    // Atomic check-and-update using compute()
    AtomicBoolean changed = new AtomicBoolean(false);
    
    lastKnownHealth.compute(connHash, (key, lastHealth) -> {
        if (lastHealth == null) {
            log.debug("First cluster health report for connHash {}: {}", connHash, normalizedCurrent);
            changed.set(false);
            return normalizedCurrent;
        }
        
        if (!lastHealth.equals(normalizedCurrent)) {
            log.info("Cluster health changed for connHash {}: {} -> {}", 
                    connHash, lastHealth, normalizedCurrent);
            changed.set(true);
            return normalizedCurrent;
        }
        
        return lastHealth; // No change
    });
    
    return changed.get();
}
```

**Testing:**
- Send same health change from 10+ threads concurrently
- Verify only ONE thread detects the change (only one log message)
- Verify pool rebalancing triggered exactly once

---

### Fix 2.2: Synchronized Pool Resizing

**Affected File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/pool/ConnectionPoolConfigurer.java`

**Current Issue:** Multiple threads can resize same pool concurrently.

**Implementation Steps:**

1. Add lock map at class level:
```java
private static final ConcurrentHashMap<String, Object> resizeLocks = new ConcurrentHashMap<>();
```

2. Wrap applyPoolSizeChanges() with synchronized block:
```java
public static void applyPoolSizeChanges(String connHash, com.zaxxer.hikari.HikariDataSource dataSource) {
    if (connHash == null || connHash.isEmpty() || dataSource == null) {
        return;
    }
    
    // Get or create lock for this connHash
    Object lock = resizeLocks.computeIfAbsent(connHash, k -> new Object());
    
    synchronized (lock) {
        MultinodePoolCoordinator.PoolAllocation allocation = poolCoordinator.getPoolAllocation(connHash);
        
        if (allocation == null) {
            log.debug("No pool allocation found for {}, skipping pool resize", connHash);
            return;
        }
        
        int newMaxPoolSize = allocation.getCurrentMaxPoolSize();
        int newMinIdle = allocation.getCurrentMinIdle();
        
        int currentMaxPoolSize = dataSource.getMaximumPoolSize();
        int currentMinIdle = dataSource.getMinimumIdle();
        
        if (currentMaxPoolSize != newMaxPoolSize || currentMinIdle != newMinIdle) {
            log.info("Resizing HikariCP pool for {}: maxPoolSize {} -> {}, minIdle {} -> {}", 
                    connHash, currentMaxPoolSize, newMaxPoolSize, currentMinIdle, newMinIdle);
            
            // Determine if we're increasing or decreasing pool sizes
            boolean isIncreasing = (newMaxPoolSize > currentMaxPoolSize) || (newMinIdle > currentMinIdle);
            boolean isDecreasing = (newMaxPoolSize < currentMaxPoolSize) || (newMinIdle < currentMinIdle);
            
            if (isDecreasing) {
                dataSource.setMinimumIdle(newMinIdle);
                dataSource.setMaximumPoolSize(newMaxPoolSize);
                dataSource.getHikariPoolMXBean().softEvictConnections();
                
                log.info("Successfully resized (decreased) HikariCP pool for {}. Idle connections above {} will be evicted.", 
                        connHash, newMinIdle);
            } else if (isIncreasing) {
                dataSource.setMaximumPoolSize(newMaxPoolSize);
                dataSource.setMinimumIdle(newMinIdle);
                
                log.info("Successfully resized (increased) HikariCP pool for {}", connHash);
            } else {
                dataSource.setMinimumIdle(newMinIdle);
                dataSource.setMaximumPoolSize(newMaxPoolSize);
                
                log.info("Successfully resized HikariCP pool for {}", connHash);
            }
        } else {
            log.debug("Pool sizes unchanged for {}, no resize needed", connHash);
        }
    }
}
```

3. Add cleanup method to prevent lock map from growing unbounded:
```java
public static void removeResizeLock(String connHash) {
    resizeLocks.remove(connHash);
}

// Call this when connection is terminated:
// ConnectionPoolConfigurer.removeResizeLock(connHash);
```

**Testing:**
- Trigger pool resizing from 10+ threads with same connHash
- Verify only one "Resizing HikariCP pool" log message per actual change
- Verify softEvictConnections() called only once per resize
- Monitor HikariCP metrics to ensure pool remains stable

---

### Fix 2.3: Bind Session Before Returning

**Affected File:** `ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java`

**Current Issue:** Session exists but not bound when returned, allowing queries to use it before binding.

**Implementation Steps:**

1. Update connectToSingleServer() to bind immediately:
```java
private SessionInfo connectToSingleServer(ConnectionDetails connectionDetails) throws SQLException {
    ServerEndpoint selectedServer = selectHealthyServer();
    
    if (selectedServer == null) {
        throw new SQLException("No healthy servers available for XA connection");
    }
    
    // ... datasource check logic ...
    
    try {
        ChannelAndStub channelAndStub = channelMap.get(selectedServer);
        if (channelAndStub == null) {
            channelAndStub = createChannelAndStub(selectedServer);
        }
        
        log.info("Connecting to server {} (XA) with datasource '{}'", 
                selectedServer.getAddress(), selectedServerDataSource);
        SessionInfo sessionInfo = withSelectedServer(channelAndStub.blockingStub.connect(connectionDetails), selectedServer);
        
        // Mark server as healthy
        selectedServer.setHealthy(true);
        selectedServer.setLastFailureTime(0);
        
        // CRITICAL: Bind session IMMEDIATELY before returning
        if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
            String targetServer = sessionInfo.getTargetServer();
            
            if (targetServer != null && !targetServer.isEmpty()) {
                bindSession(sessionInfo.getSessionUUID(), targetServer);
                log.info("=== XA session {} bound to target server {} (immediate binding) ===", 
                        sessionInfo.getSessionUUID(), targetServer);
            } else {
                // Fallback
                sessionToServerMap.put(sessionInfo.getSessionUUID(), selectedServer);
                log.info("=== XA session {} bound to server {} (fallback, immediate binding) ===", 
                        sessionInfo.getSessionUUID(), selectedServer.getAddress());
            }
        } else {
            log.warn("DIAGNOSTIC XA: No sessionUUID from server response!");
        }
        
        // Track the server for this connection hash
        if (sessionInfo.getConnHash() != null && !sessionInfo.getConnHash().isEmpty()) {
            List<ServerEndpoint> connectedServers = new ArrayList<>();
            connectedServers.add(selectedServer);
            connHashToServersMap.put(sessionInfo.getConnHash(), connectedServers);
            log.info("Tracked 1 server for XA connection hash {}", sessionInfo.getConnHash());
        }
        
        // NOW safe to return - session is fully bound
        log.info("Successfully connected to server {} (XA), session fully bound", 
                selectedServer.getAddress());
        return sessionInfo;
        
    } catch (StatusRuntimeException e) {
        // ... error handling ...
    }
}
```

2. Do the same for connectToAllServers():
```java
// Move session binding logic BEFORE adding to connectedServers list
// and BEFORE continuing to next server
```

**Testing:**
- Start query immediately after getting connection
- Verify affinityServer() always finds the session
- Add assertion in test: after connect(), session must be bound
- Run 100+ iterations to catch timing issues

---

## Priority 3 Fixes (Medium - Implement When Time Permits)

### Fix 3.1: Add Health State Timestamps

**Implementation:** Requires proto changes and version coordination between client/server.

**Steps:**
1. Update StatementService.proto to add clusterHealthTimestamp field
2. Update client to send timestamp with cluster health
3. Update server to track and compare timestamps
4. Deploy client and server together (breaking change)

**Defer this until other fixes are tested and verified.**

---

### Fix 3.2: Atomic Health Update + Session Invalidation

**Implementation:** Add synchronization around health update in performHealthCheck().

**Steps:**
1. In performHealthCheck(), wrap health update and invalidation:
```java
for (ServerEndpoint endpoint : healthyServers) {
    if (!validateServer(endpoint)) {
        log.info("XA Health check: Server {} has become unhealthy", endpoint.getAddress());
        
        // Make atomic: health update + session invalidation
        synchronized (endpoint) {
            endpoint.setHealthy(false);
            endpoint.setLastFailureTime(System.currentTimeMillis());
            
            // Immediately invalidate while holding lock
            invalidateSessionsAndConnectionsForFailedServer(endpoint);
        }
        
        // Notify after atomic update
        notifyServerUnhealthy(endpoint, new Exception("Health check failed"));
    }
}
```

**Note:** This requires making ServerEndpoint synchronization-friendly. May conflict with Fix 1.1 if using AtomicReference. Coordinate carefully.

---

## Implementation Order

**Week 1:**
- Implement Fix 1.1 (Atomic Server Health State)
- Implement Fix 1.2 (Prevent Concurrent Health Check)
- Test both fixes together
- Deploy to test environment

**Week 2:**
- Implement Fix 1.3 (Synchronized Cluster Health)
- Implement Fix 2.1 (Atomic Health Change Detection)
- Test all fixes together
- Run multinode integration tests 50+ times

**Week 3:**
- Implement Fix 2.2 (Synchronized Pool Resizing)
- Implement Fix 2.3 (Bind Session Before Returning)
- Stress test with high concurrency
- Monitor production-like load

**Week 4:**
- Consider Fix 3.1 and 3.2 if issues persist
- Performance testing
- Documentation updates
- Production deployment

---

## Testing Strategy

### Unit Tests
- Test each fix in isolation
- Mock concurrent access scenarios
- Verify atomicity guarantees

### Integration Tests
- Run existing multinode integration tests 100+ times
- Add new tests for race conditions:
  - Concurrent health updates
  - Concurrent cluster health generation
  - Concurrent pool resizing
  - Session binding races

### Stress Tests
- 10+ threads executing queries concurrently
- Random server failures during load
- Health checks triggered at high frequency
- Verify no "Connection not found" errors
- Verify consistent cluster health states

### Production Monitoring
- Monitor error rates after deployment
- Track health state transitions
- Monitor pool resize operations
- Alert on duplicate operations (sign of remaining races)

---

## Rollback Plan

If issues occur after deployment:

1. **Immediate:** Increase health check interval to reduce trigger frequency
2. **Short-term:** Disable redistribution (`ojp.redistribution.enabled=false`)
3. **Rollback:** Revert to previous version if critical failures occur
4. **Investigate:** Review logs for new race conditions introduced by fixes

---

## Success Criteria

Fixes are successful when:
1. Multinode integration tests pass 100 consecutive times
2. No "Connection not found" errors in stress tests
3. No duplicate pool resize log messages
4. No inconsistent cluster health states observed
5. Health state transitions logged consistently
6. Production deployment shows stable operation for 1 week

---

## Notes

- Each fix should be implemented in a separate commit
- All fixes should include comprehensive logging
- Code reviews should focus on concurrency correctness
- Performance impact should be measured before/after
- Consider adding metrics to track race condition occurrences
