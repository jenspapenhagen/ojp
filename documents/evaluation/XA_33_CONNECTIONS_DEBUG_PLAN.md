# Debug Plan: XA 33 Connections Issue

**Current Status**: Debug logging added in commit 0abf0a4. Waiting for CI logs to analyze.

## What to Look For in Logs

### 1. Scenario: serverEndpoints is NULL
**Log Pattern**:
```
XA serverEndpoints list: null=true, size=0, endpoints=null
XA multinode coordination SKIPPED for {...}: serverEndpoints null or empty
XA pool AFTER multinode coordination for {...}: final max=22, min=20
```

**Root Cause**: Client not sending serverEndpoints list to server

**Fix Location**: Check `OjpXAConnection.getOrCreateSession()` - lines 96-99
- Verify `this.serverEndpoints` is not null
- Add logging to confirm list is being added

### 2. Scenario: serverEndpoints is EMPTY
**Log Pattern**:
```
XA serverEndpoints list: null=false, size=0, endpoints=[]
XA multinode coordination SKIPPED for {...}: serverEndpoints null or empty  
XA pool AFTER multinode coordination for {...}: final max=22, min=20
```

**Root Cause**: List created but no endpoints added

**Fix Location**: Check `MultinodeUrlParser.getOrCreateStatementService()` - lines 93-97
- Verify endpoints list is not empty before mapping to strings
- Check URL parsing logic

### 3. Scenario: serverEndpoints has ONLY 1 ENTRY
**Log Pattern**:
```
XA serverEndpoints list: null=false, size=1, endpoints=[localhost:10591]
XA multinode pool coordination for {...}: 1 servers, divided sizes: max=22, min=20
XA pool AFTER multinode coordination for {...}: final max=22, min=20
```

**Root Cause**: Only one endpoint in list (division by 1 = no change)

**Explanation**: Each server might be receiving a filtered list with only its own endpoint

**Fix Location**: Need to ensure ALL servers receive the FULL list of endpoints
- Check if ConnectionDetails sent to each server contains full list
- Verify MultinodeConnectionManager doesn't filter the list per server

### 4. Scenario: serverEndpoints CORRECT but Coordination Not Applied
**Log Pattern**:
```
XA serverEndpoints list: null=false, size=2, endpoints=[localhost:10591, localhost:10592]
XA multinode pool coordination for {...}: 2 servers, divided sizes: max=11, min=10
XA pool AFTER multinode coordination for {...}: final max=11, min=10
Creating XA pool with minIdle=20  <- BUG: Using wrong value
```

**Root Cause**: Divided sizes calculated correctly but XA pool provider ignoring them

**Fix Location**: Check lines 448-456 where pool config is built
```java
xaPoolConfig.put("xa.maxPoolSize", String.valueOf(maxPoolSize));
xaPoolConfig.put("xa.minIdle", String.valueOf(minIdle));
```

Verify these use the POST-coordination values, not pre-coordination values.

### 5. Scenario: Different Lists Per Server
**Log Pattern** (Server 1):
```
XA serverEndpoints list: null=false, size=1, endpoints=[localhost:10591]
```

**Log Pattern** (Server 2):
```
XA serverEndpoints list: null=false, size=1, endpoints=[localhost:10592]
```

**Root Cause**: Each server receives different list

**Fix**: The ConnectionDetails sent to EACH server should contain the FULL cluster list
- Not just the endpoint being connected to
- The entire list: `[localhost:10591, localhost:10592]`

## Expected Correct Logs

Both Server 1 and Server 2 should show:
```
XA pool BEFORE multinode coordination for {...}: requested max=22, min=20
XA serverEndpoints list: null=false, size=2, endpoints=[localhost:10591, localhost:10592]
XA multinode pool coordination for {...}: 2 servers, divided sizes: max=11, min=10  
XA pool AFTER multinode coordination for {...}: final max=11, min=10
Created XA pool for connHash {...} - maxPoolSize: 11, minIdle: 10, multinode: true
```

## Action Items Based on Logs

Once we see the actual log output:

1. **If serverEndpoints is null/empty**: 
   - Add logging in OjpXAConnection to trace where list is lost
   - Check OjpXADataSource.getServerEndpoints()

2. **If serverEndpoints has wrong size**:
   - Check MultinodeUrlParser endpoint extraction
   - Verify list isn't being filtered somewhere

3. **If coordination calculates correctly but pool ignores it**:
   - Check XAConnectionPoolProvider implementation
   - Verify config map keys match expected format

4. **If each server gets different list**:
   - This is the MOST LIKELY issue
   - Check how ConnectionDetails is constructed in connectToAllServers()
   - Ensure same ConnectionDetails (with full endpoint list) is sent to ALL servers

## Code Review Points

### OjpXAConnection.getOrCreateSession() - Lines 88-100
```java
ConnectionDetails.Builder connBuilder = ConnectionDetails.newBuilder()
        .setUrl(url)
        .setUser(user != null ? user : "")
        .setPassword(password != null ? password : "")
        .setClientUUID(ClientUUID.getUUID())
        .setIsXA(true);

// THIS IS THE CRITICAL PART
if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
    connBuilder.addAllServerEndpoints(serverEndpoints);
    log.info("Adding {} server endpoints to ConnectionDetails", serverEndpoints.size());
}
```

**Question**: Is `this.serverEndpoints` the full list or just current server?

### MultinodeConnectionManager.connectToAllServers() - Around line 440
```java
for (ServerEndpoint server : serverEndpoints) {
    // Build ConnectionDetails for THIS server
    // DOES IT INCLUDE THE FULL serverEndpoints LIST?
}
```

**Critical**: The ConnectionDetails sent to EACH individual server must contain the FULL cluster endpoint list, not just that server's endpoint.

## Most Probable Root Cause

Based on the symptoms (33 connections = ~16-17 per server instead of 10), the most likely scenario is:

**Each server receives a ConnectionDetails with only 1 endpoint (itself)**:
- Server 1 gets: `serverEndpoints=[localhost:10591]` → divides by 1 → opens 20 connections
- Server 2 gets: `serverEndpoints=[localhost:10592]` → divides by 1 → opens 20 connections  
- Total: 40 connections (but showing 33, some might not be idle yet)

OR

**serverEndpoints list is null/empty**:
- Both servers skip coordination
- Each opens 20 connections
- Total: 40 connections (showing 33)

## Next Steps

1. Review attached logs from user (comment_id 3689066283)
2. Identify which scenario matches the log output
3. Apply appropriate fix
4. Add more targeted logging if needed
5. Retest
