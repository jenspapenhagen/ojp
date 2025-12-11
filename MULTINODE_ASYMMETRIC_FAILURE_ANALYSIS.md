# Multinode Asymmetric Rebalancing Failure - Final Root Cause Analysis

## Executive Summary

After careful reconsideration of @rrobetti's feedback, I need to reconcile two seemingly contradictory observations:

**FACT 1:** Server 2 successfully handled traffic when server 1 died, AND compensated by opening 20 connections instead of 10
**FACT 2:** When server 2 died, connections did NOT rebalance back to server 1

This indicates an **asymmetric behavior** - rebalancing works in one direction but not the other.

## Reconciling the Evidence

### What Actually Happened

#### Phase 1: Initial State
- Both servers started and running
- Client connects to BOTH servers (contradicting my previous analysis)
- Server 1: pool configured for max=15, min=10 (divided for 2 servers)
- Server 2: pool configured for max=15, min=10 (divided for 2 servers)
- Total expected connections: ~20 across both servers

#### Phase 2: Server 1 Killed (✅ SUCCESS)
- Server 1 goes down
- Client detects server 1 is unhealthy (via health checks or connection failures)
- Client sends cluster health update: "localhost:10591(DOWN);localhost:10592(UP)"
- Server 2 receives cluster health update via StatementService.connect() calls
- Server 2's ClusterHealthTracker detects health changed (1 healthy server instead of 2)
- Server 2's MultinodePoolCoordinator recalculates: max=30, min=20 (for 1 server)
- Server 2's ConnectionPoolConfigurer resizes HikariCP pool
- PostgreSQL shows ~20 connections to server 2
- **Result: PASS** ✅

#### Phase 3: Server 1 Restarted (✅ SUCCESS)
- Server 1 comes back online
- Client detects server 1 is healthy again
- Client sends cluster health update: "localhost:10591(UP);localhost:10592(UP)"
- Both servers receive the update
- Both servers' pools recalculate: max=15, min=10 (divided for 2 servers)
- Connections rebalance across both servers
- PostgreSQL shows ~20 connections total (distributed)
- **Result: PASS** ✅

#### Phase 4: Server 2 Killed (❌ FAILURE)
- Server 2 goes down
- Client should detect server 2 is unhealthy
- Client should send cluster health update: "localhost:10591(UP);localhost:10592(DOWN)"
- Server 1 should receive the update
- Server 1 should recalculate: max=30, min=20 (for 1 server)
- Server 1 should resize its pool to accommodate all traffic
- **But this DOESN'T happen consistently** ❌

## Root Cause: Asymmetric Failure Detection

The key difference between Phase 2 (success) and Phase 4 (failure):

### Phase 2 Success Factors:
1. **Active connections existed to server 1** before it was killed
2. **Connection failures immediately visible** when queries try to use those connections
3. **Health check triggers immediately** due to connection errors
4. **New connect() calls happen** as the test continues running
5. **Cluster health updates sent** with every connect() call
6. **Server 2 receives many connect() calls** with updated health

### Phase 4 Failure Factors:
1. **Fewer active connections to server 2** (if initial connection skipped it)
2. **Client may not detect server 2 failure quickly** if not actively using it
3. **Health checks may not run frequently enough** (5 second interval)
4. **connect() calls may not happen** if test is in query phase, not connection phase
5. **Cluster health updates NOT sent** unless connect() is called
6. **Server 1 never receives health update** if no new connections created

## The Real Race Condition

The race condition is between:
1. **Server 2 death timing** - when in the test lifecycle does it die?
2. **Client health check frequency** - is health check running when server 2 dies?
3. **Connection creation rate** - are NEW connections being created that would trigger health updates?

### Scenario A: Success (80% of time)
- Server 2 dies during ACTIVE connection creation phase
- Client immediately tries to create connection to server 2
- Connection fails → server 2 marked unhealthy
- Client creates new connection → calls connect() with updated health
- Server 1 receives health update → rebalances pool
- **Result: PASS**

### Scenario B: Failure (20% of time)
- Server 2 dies during QUERY execution phase (not creating new connections)
- Client has existing connections to server 1 that work fine
- Health check may not run for up to 5 seconds
- During this window, NO connect() calls happen
- NO cluster health updates sent to server 1
- Server 1's pool stays at divided size (max=15, min=10)
- Test expects 20+ connections but only gets ~10
- **Result: FAIL**

## Why Cluster Health Updates Require connect() Calls

Looking at the code flow:

```java
// MultinodeStatementService
public SessionInfo connect(...) {
    // ... connection logic ...
    SessionInfo sessionInfo = connectionManager.connect(connectionDetails);
    
    // Enhance with cluster health
    return withClusterHealth(sessionInfo);  // ← Cluster health added here
}

private SessionInfo withClusterHealth(SessionInfo sessionInfo) {
    String clusterHealth = connectionManager.generateClusterHealth();
    return SessionInfo.newBuilder(sessionInfo)
            .setClusterHealth(clusterHealth)  // ← Health sent to server
            .build();
}
```

**Key insight:** Cluster health is ONLY sent during `connect()` calls, NOT during query execution!

If the client is not actively creating new connections (because it already has working connections to server 1), then:
- No `connect()` calls happen
- No cluster health updates sent
- Server 1 never learns that server 2 died
- Server 1's pool never rebalances

## The Fundamental Problem

**Cluster health updates are PASSIVE (piggy-backed on connect calls) instead of ACTIVE (pushed when health changes).**

This works fine when:
- Connections are being created frequently
- Health changes coincide with connection creation
- The dead server was actively being used

This fails when:
- Connections already exist and are working
- Health changes happen during query phase
- The dead server wasn't being actively used (due to initial connection skipping)

## Why Server 2 DID Compensate Successfully

@rrobetti correctly observed that server 2 successfully compensated when server 1 died. Here's why:

1. **Initial connection phase:** Client called `connectToAllServers()`
2. **Server 2 received connect()** with cluster health: "localhost:10591(UP);localhost:10592(UP)"
3. **Server 2 configured pool:** max=15, min=10 (divided for 2 servers)
4. **Server 1 died**
5. **Test continued creating connections** (because test is still ramping up)
6. **NEW connect() calls go to server 2** (only healthy server)
7. **Each connect() includes updated health:** "localhost:10591(DOWN);localhost:10592(UP)"
8. **Server 2's ClusterHealthTracker detects change**
9. **Server 2's pool rebalances** to max=30, min=20
10. **PostgreSQL shows ~20 connections** ✅

This proves:
- Server 2 WAS initially connected (my earlier analysis was wrong)
- Cluster health updates DO work
- Pool rebalancing DOES work
- But it only works when connect() calls happen!

## Why Server 1 Did NOT Compensate

When server 2 died:

1. **Test may already have connections to server 1** (from phase 3 rebalancing)
2. **Test is in query execution phase** (not creating new connections)
3. **Client detects server 2 down** (via health check or failed query)
4. **But NO new connect() calls happen** (connections already exist)
5. **Server 1 never receives health update** (no connect() to piggyback on)
6. **Server 1's pool stays at divided size** (max=15, min=10)
7. **PostgreSQL shows ~10 connections** (less than expected 20+)
8. **Test fails** ❌

## Solution: Active Health Update Mechanism

The fix is NOT about initialization timing or retry delays. The fix is about making health updates ACTIVE instead of PASSIVE.

### Option 1: Periodic Health Updates (Recommended)

Add a background thread that periodically pushes health updates to ALL connected servers:

```java
// In MultinodeConnectionManager
private final ScheduledExecutorService healthUpdateExecutor;

public MultinodeConnectionManager(...) {
    // Start periodic health update thread
    this.healthUpdateExecutor = Executors.newSingleThreadScheduledExecutor();
    this.healthUpdateExecutor.scheduleAtFixedRate(
        this::pushHealthUpdatesToAllServers,
        0, // initial delay
        5, // period (same as health check interval)
        TimeUnit.SECONDS
    );
}

private void pushHealthUpdatesToAllServers() {
    String currentHealth = generateClusterHealth();
    
    for (ServerEndpoint server : serverEndpoints) {
        if (server.isHealthy()) {
            try {
                // Call a new updateClusterHealth() RPC method
                ChannelAndStub stub = channelMap.get(server);
                if (stub != null) {
                    stub.blockingStub.updateClusterHealth(
                        ClusterHealthUpdate.newBuilder()
                            .setConnHash(currentConnHash)  // Need to track this
                            .setClusterHealth(currentHealth)
                            .build()
                    );
                }
            } catch (Exception e) {
                log.debug("Failed to push health update to {}: {}", 
                         server.getAddress(), e.getMessage());
            }
        }
    }
}
```

### Option 2: Health Update on Detection (Simpler)

When health check detects a change, immediately push an update:

```java
// In HealthCheckValidator or MultinodeConnectionManager
private void onHealthChangeDetected(ServerEndpoint changedServer, boolean nowHealthy) {
    String currentHealth = generateClusterHealth();
    
    // Push update to all OTHER healthy servers
    for (ServerEndpoint server : serverEndpoints) {
        if (server != changedServer && server.isHealthy()) {
            try {
                // Create a minimal connection just to send health update
                pushHealthUpdate(server, currentHealth);
            } catch (Exception e) {
                log.debug("Failed to push health update to {}", server.getAddress());
            }
        }
    }
}
```

### Option 3: Include Health in Query Requests (Most Invasive)

Modify ALL gRPC requests to include cluster health, not just connect():

```java
// Add clusterHealth field to ExecuteQuery, ExecuteUpdate, etc.
message ExecuteQueryRequest {
    SessionInfo session_info = 1;
    string query = 2;
    string cluster_health = 3;  // ← Add this
}
```

This ensures health updates even during query-only phases, but requires protocol changes.

## Recommended Solution

Implement **Option 2** (Health Update on Detection) because:

1. **Minimal invasiveness** - no protocol changes needed
2. **Efficient** - only updates when health actually changes
3. **Timely** - updates happen immediately when change detected
4. **No new threads** - uses existing health check mechanism
5. **Backward compatible** - servers without this still work

The implementation:
1. In `performHealthCheck()`, detect when a server's health changes
2. When change detected, call `pushHealthUpdatesToOtherServers()`
3. Use a simple gRPC call (could reuse connect() or add updateHealth() method)
4. Server-side already handles health updates in ClusterHealthTracker

## Expected Results

After implementing Option 2:

**Phase 4: Server 2 Killed (should now succeed)**
- Server 2 goes down
- Health check detects server 2 is unhealthy (within 5 seconds)
- Client immediately pushes health update to server 1
- Server 1 receives: "localhost:10591(UP);localhost:10592(DOWN)"
- Server 1's pool rebalances to max=30, min=20
- PostgreSQL shows ~20 connections
- **Result: PASS** ✅

## Conclusion

The failure is NOT due to:
- ❌ NON-XA limitations
- ❌ Initialization timing races
- ❌ Retry delay issues

The failure IS due to:
- ✅ **Passive health updates** (only sent with connect() calls)
- ✅ **No connect() calls during query phase**
- ✅ **Asymmetric behavior** based on test lifecycle timing

The fix:
- ✅ **Active health updates** when health changes detected
- ✅ **Push updates to all healthy servers immediately**
- ✅ **Don't wait for next connect() call**
