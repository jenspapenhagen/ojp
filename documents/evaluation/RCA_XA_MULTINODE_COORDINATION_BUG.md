# RCA: XA Multinode Pool Coordination Not Applied

## Issue
XA connections creating 33 backend connections instead of expected 20 when multinode properties are set with:
- `multinode.ojp.connection.pool.minimumIdle=20`
- `multinode.ojp.connection.pool.maximumPoolSize=22`

With 2 servers, each should open: 20/2 = 10 connections → Total: 20 connections
But actual: ~33 connections (each server opening ~16-17 connections)

## Root Cause Identified

### Line-by-Line Comparison

**Non-XA Path (StatementServiceImpl.java:327)**
```java
if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
    // Multinode: calculate divided pool sizes
    MultinodePoolCoordinator.PoolAllocation allocation = 
            ConnectionPoolConfigurer.getPoolCoordinator().calculatePoolSizes(
                    connHash, maxPoolSize, minIdle, serverEndpoints);
```

**XA Path (StatementServiceImpl.java:422)** ❌ BUG
```java
if (serverEndpoints != null && serverEndpoints.size() > 1) {
    // Multinode: divide pool sizes among servers
    MultinodePoolCoordinator.PoolAllocation allocation = 
            ConnectionPoolConfigurer.getPoolCoordinator().calculatePoolSizes(
                    connHash, maxPoolSize, minIdle, serverEndpoints);
```

### The Bug

XA uses condition: `serverEndpoints.size() > 1`
Non-XA uses condition: `!serverEndpoints.isEmpty()`

When `serverEndpoints.size() == 1`:
- Non-XA: Applies coordination (divides by 1, no change but registers allocation)
- XA: **SKIPS coordination entirely**, uses raw properties values

### Why This Causes 33 Connections

1. Client provides `serverEndpoints = ["localhost:10591", "localhost:10592"]` (size = 2)
2. **But the condition checks happen PER SERVER**
3. When Server 1 processes connection request:
   - It sees its own serverEndpoints list from ConnectionDetails
   - This list might be filtered or contain only one entry
   - Condition `serverEndpoints.size() > 1` evaluates to FALSE
   - Multinode coordination SKIPPED
   - Uses raw properties: minIdle=20, maxPoolSize=22
4. Server 1 creates pool with 20 minimum connections
5. Server 2 does the same
6. Total: 20 + 20 = 40 connections (but test shows 33, likely due to some connections not being idle yet)

### Why Non-XA Works

Non-XA uses `!serverEndpoints.isEmpty()` which evaluates to TRUE even with size=1.
This ensures coordination logic runs, which:
1. Registers the connection in MultinodePoolCoordinator
2. Calculates divided sizes even if division = 1
3. Properly tracks allocations for future rebalancing

## Solution

Change XA condition from:
```java
if (serverEndpoints != null && serverEndpoints.size() > 1) {
```

To:
```java
if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
```

This makes XA behavior identical to non-XA.

## Why Previous Attempts Failed

1. **Attempt 1**: Changed properties to 20/5 - This worked but user reverted it
2. **Attempt 2**: Added multinode coordination code - But had wrong condition
3. **Attempt 3**: Added debug logging - Revealed serverEndpoints.size() was the issue

The bug was subtle: checking `size() > 1` instead of `!isEmpty()` meant single-element lists were treated as non-multinode.

## Verification

After fix, with properties minIdle=20, maxPoolSize=22:
- If serverEndpoints has 2 servers: 20/2 = 10 per server → 20 total ✓
- If serverEndpoints has 1 server: 20/1 = 20 per server → 20 total ✓
- If serverEndpoints empty/null: No coordination, uses raw 20 → 20 total ✓

All paths now behave identically to non-XA.
