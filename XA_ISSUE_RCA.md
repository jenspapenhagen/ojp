# Root Cause Analysis: XA Transaction Failure in OJP with Spring Boot + Narayana

## Problem Statement

A test in a separated repository fails when using OJP XA Data source with Spring Boot + Narayana TM. The same test passes when using native XA datasource. The failure occurs during XA transaction operations with the following errors:

1. `XAException` at line 88 in `XATransactionRegistry.registerExistingSession()`
2. `PGXAException: tried to call end without corresponding start call. state=ENDED`

## Error Analysis

### Error Log Sequence

1. **Thread 157**: Error in `xaStart` at `XATransactionRegistry.registerExistingSession(XATransactionRegistry.java:88)`
2. **Thread 163**: Error in `xaEnd` with PostgreSQL error: "tried to call end without corresponding start call. state=ENDED"

### Code at Line 88 (XATransactionRegistry.java)

```java
public void registerExistingSession(XidKey xid, XABackendSession session, int flags) throws XAException {
    log.debug("registerExistingSession: xid={}, flags={}", xid, flagsToString(flags));
    
    // Validate flags - only TMNOFLAGS allowed for new transaction
    if (flags != XAResource.TMNOFLAGS) {
        throw new XAException(XAException.XAER_INVAL);  // ← LINE 88
    }
    // ...
}
```

The error occurs because `registerExistingSession` expects `flags == TMNOFLAGS` but receives a different flag value.

## Root Cause

### Primary Issue: Incorrect Flag Handling in `handleXAStartWithPooling`

The method `StatementServiceImpl.handleXAStartWithPooling()` **unconditionally calls `registerExistingSession`** regardless of the XA flags:

```java
private void handleXAStartWithPooling(com.openjproxy.grpc.XaStartRequest request, Session session, 
                                      StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) throws Exception {
    // ...
    // Register the existing XABackendSession with the XA transaction (avoids double allocation)
    registry.registerExistingSession(xidKey, backendSession, request.getFlags());  // ← PROBLEM
    // ...
}
```

### XA Start Flags and Their Semantics

According to XA specification, `xa_start()` can be called with three different flags:

1. **TMNOFLAGS (0x00000000)**: Start a **new** transaction branch with a new Xid
2. **TMJOIN (0x00200000)**: **Join** an existing transaction branch (same Xid, same global transaction)
3. **TMRESUME (0x08000000)**: **Resume** a previously suspended transaction branch

### Current Implementation vs. Required Behavior

#### Current Implementation

- **For ALL flags**: Calls `registerExistingSession(xid, session, flags)`
- `registerExistingSession` only accepts TMNOFLAGS
- For TMJOIN or TMRESUME, throws `XAException(XAER_INVAL)` ✗

#### Required Behavior

- **For TMNOFLAGS**: Call `registerExistingSession(xid, session, TMNOFLAGS)` 
  - Creates new TxContext with the pre-allocated backend session
  
- **For TMJOIN/TMRESUME**: Call `registry.xaStart(xid, flags)`
  - Reuses existing TxContext and its associated backend session
  - Transitions context from ENDED back to ACTIVE

### Why This Causes the Observed Failure

When Spring Boot + Narayana attempts to:

1. Call `xaStart()` with TMJOIN or TMRESUME flags (e.g., for resource enlistment patterns)
2. OJP incorrectly routes this to `registerExistingSession`
3. `registerExistingSession` rejects the call with `XAER_INVAL`
4. The xaStart fails but Narayana continues with its transaction protocol
5. Narayana calls `xaEnd()` expecting the transaction to be active
6. PostgreSQL's XAConnection is still in ENDED state from a previous transaction
7. PostgreSQL throws: "tried to call end without corresponding start call. state=ENDED"

### Secondary Issue: Backend XA Resource State Management

After an XA transaction is committed/rolled back:
- The `TxContext` is removed from the registry
- The backend session remains bound to the OJP Session (by design, for reuse)
- However, the PostgreSQL XAConnection might retain state from the previous transaction

The error message `state=ENDED` suggests that PostgreSQL's XAConnection was not properly reset after the previous transaction completed.

## Design Context

### Eager Session Allocation Architecture

The current OJP XA pooling implementation uses **eager allocation**:

```java
// During connect():
XABackendSession backendSession = xaPoolProvider.borrowSession(registry.getPooledXADataSource());
session.setBackendSession(backendSession);  // Store for reuse across transactions
```

Key design points from code comments:

```java
// NOTE: Do NOT return session to pool here - session stays bound to OJP Session
// for multiple transactions. Pool return happens when OJP Session terminates.
```

This means:
- One OJP Session → One XABackendSession (persistent until connection closes)
- Multiple sequential XA transactions reuse the same backend session
- Contexts are created/removed per transaction, but the session persists

### Dual-Path Implementation

There are TWO implementations of XA handling in OJP:

1. **Pooled Path (NEW)**: Uses `XATransactionRegistry` with session reuse
   - Controlled by: `xaPoolProvider != null`
   - Entry point: `handleXAStartWithPooling()`
   
2. **Pass-through Path (LEGACY)**: Direct XAResource delegation
   - Controlled by: `xaPoolProvider == null`
   - Entry point: `handleXAStartPassThrough()`

### XATransactionRegistry Design

The `XATransactionRegistry` class has TWO methods for xa_start:

1. **`registerExistingSession(xid, session, flags)`**
   - Purpose: Register a pre-allocated session with a NEW transaction (TMNOFLAGS only)
   - Use case: Eager allocation during connect()
   - Validates: `flags == TMNOFLAGS`
   - Creates: New TxContext

2. **`xaStart(xid, flags)`**
   - Purpose: Standard XA start with full flag support
   - Use case: All XA start scenarios
   - Handles:
     - TMNOFLAGS: Borrows new session, creates TxContext
     - TMJOIN: Reuses existing TxContext and session
     - TMRESUME: Resumes suspended TxContext and session

## Why Native XA DataSource Works

When using a native PostgreSQL XA DataSource directly:
- Spring/Narayana interacts with PostgreSQL's XAResource directly
- PostgreSQL's XAConnection handles flag routing correctly
- State transitions are managed by PostgreSQL's native implementation
- No intermediary layer mishandles the flags
- Native XA DataSource may create a new XAConnection for each transaction, avoiding state carryover

## Detailed Failure Analysis

### The Actual Failure Sequence

Based on the error logs and code analysis, here's what happens:

**First Transaction (Success):**
1. Client calls `xaStart(xid1, TMNOFLAGS)` 
2. `handleXAStartWithPooling` → `registerExistingSession(xid1, backendSession, TMNOFLAGS)` ✓
3. PostgreSQL XAConnection state: ACTIVE
4. Client calls `xaEnd(xid1, TMSUCCESS)`
5. PostgreSQL XAConnection state: ENDED
6. Client calls `xaPrepare(xid1)`
7. PostgreSQL XAConnection state: PREPARED
8. Client calls `xaCommit(xid1, false)`
9. PostgreSQL XAConnection state: Should be IDLE, but might still be ENDED
10. OJP removes TxContext for xid1, but keeps backendSession bound to OJP Session

**Second Transaction (Failure):**
1. Client calls `xaStart(xid2, TMJOIN)` or `xaStart(xid2, TMRESUME)` 
   - **Note**: The client might use TMJOIN if it detects the XAConnection is already associated with a transaction
   - OR the client might use TMNOFLAGS if it thinks this is a new transaction
2. `handleXAStartWithPooling` → `registerExistingSession(xid2, backendSession, TMJOIN/TMRESUME)`
3. **ERROR**: `registerExistingSession` rejects because flags != TMNOFLAGS
4. xaStart fails with XAER_INVAL
5. Client still calls `xaEnd(xid2, ...)` as part of cleanup
6. PostgreSQL throws: "tried to call end without corresponding start call. state=ENDED"
   - The connection is still in ENDED state from the previous transaction!

### Two Interrelated Bugs

**Bug #1: Missing Flag Routing**
- `handleXAStartWithPooling` doesn't route TMJOIN/TMRESUME to `registry.xaStart()`
- It always calls `registerExistingSession` which only accepts TMNOFLAGS

**Bug #2: XAConnection State Not Reset After Commit**
- After `xaCommit`, the PostgreSQL XAConnection remains in ENDED state instead of returning to IDLE
- When the next transaction attempts to start, the connection is not in a clean state
- This might be a PostgreSQL driver behavior, or OJP needs to explicitly reset the connection

## Recommendations for Fix

### Primary Fix: Flag-Based Routing with Session Reuse

The fix must handle the fact that:
- One backend session is reused across multiple sequential transactions
- Each transaction has a different Xid
- TMNOFLAGS is used for each new transaction
- TMJOIN/TMRESUME are only valid within a single transaction's lifecycle

**Proposed Implementation:**

```java
private void handleXAStartWithPooling(com.openjproxy.grpc.XaStartRequest request, Session session, 
                                      StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) throws Exception {
    String connHash = session.getSessionInfo().getConnHash();
    XATransactionRegistry registry = xaRegistries.get(connHash);
    XidKey xidKey = XidKey.from(convertXid(request.getXid()));
    int flags = request.getFlags();
    
    if (flags == XAResource.TMNOFLAGS) {
        // New transaction: use existing session from OJP Session
        XABackendSession backendSession = (XABackendSession) session.getBackendSession();
        if (backendSession == null) {
            throw new SQLException("No XABackendSession found in session");
        }
        registry.registerExistingSession(xidKey, backendSession, flags);
    } else if (flags == XAResource.TMJOIN || flags == XAResource.TMRESUME) {
        // Join or resume existing transaction: delegate to registry
        // This requires the context to exist (created by previous TMNOFLAGS start)
        registry.xaStart(xidKey, flags);
    } else {
        throw new SQLException("Invalid XA flags: " + flags);
    }
    
    com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
            .setSession(session.getSessionInfo())
            .setSuccess(true)
            .setMessage("XA start successful (pooled)")
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
}
```

### Secondary Fix: Enhanced registerExistingSession

Enhance `registerExistingSession` to handle session reuse more robustly:

```java
public void registerExistingSession(XidKey xid, XABackendSession session, int flags) throws XAException {
    log.debug("registerExistingSession: xid={}, flags={}", xid, flagsToString(flags));
    
    // Validate flags - only TMNOFLAGS allowed for new transaction
    if (flags != XAResource.TMNOFLAGS) {
        throw new XAException(XAException.XAER_INVAL);
    }
    
    // Create context and ensure no duplicate
    TxContext existing = contexts.putIfAbsent(xid, new TxContext(xid));
    if (existing != null) {
        throw new XAException(XAException.XAER_DUPID);
    }
    
    TxContext ctx = contexts.get(xid);
    try {
        // Register the existing session
        ctx.transitionToActive(session);
        
        // Create Xid object once and store it for reuse
        javax.transaction.xa.Xid actualXid = xid.toXid();
        ctx.setActualXid(actualXid);
        
        // IMPORTANT: Ensure backend XAResource is in idle state before start
        // This handles the case where the session was reused from a previous transaction
        // and might still be in ENDED state
        try {
            // Defensive: try to end any lingering transaction state
            // This is a workaround for PostgreSQL XA state not resetting after commit
            XAResource xaResource = session.getXAResource();
            
            // Note: This might throw if no transaction is active, which is fine
            // We catch and ignore such errors
            
        } catch (Exception ignored) {
            // Expected if connection is already idle
        }
        
        // Call XAResource.start on backend with the stored Xid
        session.getXAResource().start(actualXid, flags);
        
        log.info("XA transaction registered with existing session: xid={}", xid);
    } catch (XAException e) {
        contexts.remove(xid);
        throw e;
    } catch (Exception e) {
        contexts.remove(xid);
        throw new XAException(XAException.XAER_RMERR);
    }
}
```

However, this defensive approach might not work because we can't safely call end() without knowing the previous Xid.

### Better Alternative: Connection Reset After Transaction

A cleaner approach is to ensure the XAConnection is properly reset after each transaction:

**In xaCommit and xaRollback methods:**

```java
public void xaCommit(XidKey xid, boolean onePhase) throws XAException {
    // ... existing commit logic ...
    
    // After successful commit, ensure backend XA resource is reset to idle
    try {
        XABackendSession session = ctx.getSession();
        if (session != null) {
            // Close and reopen the logical connection to reset XA state
            Connection conn = session.getConnection();
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
            // Get a fresh logical connection from the XAConnection
            session.refreshConnection();
        }
    } catch (Exception e) {
        log.warn("Failed to reset XA session after commit: {}", e.getMessage());
        // Don't throw - commit was successful
    }
}
```

This assumes XABackendSession has or can be enhanced with a `refreshConnection()` method.

## Additional Investigation Needed

1. **Capture actual flags being sent**: 
   - Add detailed logging in `handleXAStartWithPooling` to log: `xid`, `flags`, and session state
   - Run the failing integration test and capture the exact sequence
   - Determine if Narayana is sending TMJOIN/TMRESUME or TMNOFLAGS

2. **Verify PostgreSQL XA State Management**:
   - After xaCommit, check the state of PostgreSQL's XAConnection
   - Determine if PostgreSQL requires explicit connection refresh after commit
   - Test with `connection.close()` and getting a fresh connection from XAConnection

3. **Verify transaction boundaries**: 
   - Confirm whether TMJOIN/TMRESUME are used within or across transaction boundaries
   - Check if Narayana uses TMJOIN for resource enlistment patterns
   - Review Narayana documentation for XA resource enlistment behavior

4. **Test with H2/MySQL**: 
   - Verify if the issue is PostgreSQL-specific or affects all databases
   - Different databases might handle XA state differently after commit

5. **Review Narayana integration**: 
   - Check if there's a configuration issue with how Narayana enlists XA resources
   - Verify if Spring's transaction synchronization affects the behavior
   - Look for Narayana-specific XA flags or behaviors

6. **Check XABackendSession lifecycle**:
   - Verify if `XABackendSession.getConnection()` returns the same Connection instance across transactions
   - Determine if closing the logical connection resets XA state
   - Review if XAConnection needs explicit reset/refresh

## Immediate Testing Steps

### Test 1: Add Diagnostic Logging

Add this to `handleXAStartWithPooling`:

```java
private void handleXAStartWithPooling(com.openjproxy.grpc.XaStartRequest request, Session session, 
                                      StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) throws Exception {
    // ... existing code ...
    
    log.info("XA START DIAGNOSTIC: xid={}, flags={}, flagsDecoded={}", 
             xidKey.toCompactString(), 
             request.getFlags(),
             flagsToString(request.getFlags()));
    
    XABackendSession backendSession = (XABackendSession) session.getBackendSession();
    log.info("XA START DIAGNOSTIC: backendSession={}, session={}", 
             backendSession != null ? "present" : "null",
             session.getSessionInfo().getSessionUUID());
    
    // ... rest of method ...
}

private static String flagsToString(int flags) {
    if (flags == XAResource.TMNOFLAGS) return "TMNOFLAGS";
    if (flags == XAResource.TMJOIN) return "TMJOIN";
    if (flags == XAResource.TMRESUME) return "TMRESUME";
    return "UNKNOWN(" + flags + ")";
}
```

### Test 2: Check PostgreSQL State

After xaCommit, add logging to check state:

```java
public void xaCommit(XidKey xid, boolean onePhase) throws XAException {
    // ... existing commit logic ...
    
    log.info("XA COMMIT DIAGNOSTIC: After commit, checking connection state");
    try {
        XABackendSession session = ctx.getSession();
        if (session != null) {
            XAResource xaResource = session.getXAResource();
            Connection conn = session.getConnection();
            log.info("XA COMMIT DIAGNOSTIC: connection.isClosed()={}, connection.getAutoCommit()={}", 
                     conn.isClosed(), conn.getAutoCommit());
        }
    } catch (Exception e) {
        log.warn("XA COMMIT DIAGNOSTIC: Error checking state: {}", e.getMessage());
    }
}
```

## Test Scenario to Reproduce

Create a test that:
1. Starts and commits a transaction (xid1)
2. Starts a second transaction (xid2) with TMJOIN or TMRESUME flags
3. Observe the failure at the second xaStart

Or:
1. Enable detailed XA flag logging
2. Run the existing failing integration test
3. Capture the flag sequence to understand Narayana's resource enlistment pattern

## Summary

**The root cause is a combination of two bugs:**

1. **Missing Flag Routing in `handleXAStartWithPooling`**: 
   - The method unconditionally calls `registerExistingSession` which only supports TMNOFLAGS
   - When Narayana (or any XA transaction manager) attempts to call xaStart with TMJOIN or TMRESUME flags, it fails with XAER_INVAL
   - This breaks compatibility with transaction managers that use these flags for resource enlistment patterns

2. **PostgreSQL XAConnection State Not Reset After Transaction**:
   - After xaCommit/xaRollback, the PostgreSQL XAConnection may remain in ENDED state instead of returning to IDLE
   - When the next transaction attempts to start (after the first bug is fixed), the connection might not be in a clean state
   - This causes PostgreSQL to throw "tried to call end without corresponding start call. state=ENDED"

**The immediate fix** requires implementing flag-based routing in `handleXAStartWithPooling` to:
- Use `registerExistingSession` for TMNOFLAGS (new transaction with the reused backend session)
- Use `registry.xaStart` for TMJOIN/TMRESUME (join/resume existing transaction context)

**The complete fix** also requires ensuring proper XA state management:
- Verify that the backend XAConnection is in a clean state when starting a new transaction
- Potentially implement connection refresh logic after transaction completion

**Further investigation** is needed to:
- Capture the actual XA flags being sent by Narayana in the failing scenario
- Understand Narayana's resource enlistment patterns and when it uses TMJOIN vs TMNOFLAGS
- Determine if connection state reset is needed and the best approach for it

**Why native XA datasource works**: Native XA datasources may create fresh XAConnection instances for each transaction, or handle state reset internally, avoiding the state carryover issue that OJP's session reuse pattern encounters.
