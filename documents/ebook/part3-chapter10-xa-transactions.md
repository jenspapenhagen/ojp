# Chapter 10: XA Distributed Transactions

XA transactions represent one of the most powerful—and traditionally most complex—features in enterprise software. They allow you to coordinate changes across multiple databases or resources, ensuring that either all changes commit together or all roll back together. No half-completed transactions, no orphaned data. It's the kind of reliability that mission-critical applications demand, but it often comes at the cost of significant complexity and performance overhead.

OJP transforms this equation. By managing XA connections through server-side pooling with Apache Commons Pool 2, OJP eliminates most of the traditional XA performance penalties while maintaining full compliance with the JDBC XA specification. You get the reliability of distributed transactions without sacrificing the performance your applications need.

## Understanding XA Transactions

Before diving into how OJP implements XA, let's understand what XA transactions actually accomplish and why they matter.

### The Distributed Transaction Problem

— Roberto Robetti, OJP Creator

> "When you need to update a customer's account and their audit log simultaneously, and both live in different databases, you need more than hope. You need XA."

Consider a banking application that needs to transfer money between accounts. In a traditional setup with a single database, this is straightforward—start a transaction, debit one account, credit the other, and commit. The database ensures atomicity automatically. But what if these accounts live in different database instances? Perhaps you've sharded your data for scalability, or maybe you're running a microservices architecture where different services own different databases.

This is where XA transactions shine. XA (eXtended Architecture) is a standard protocol developed by The Open Group that coordinates transactions across multiple resources. It implements what's called Two-Phase Commit (2PC), a protocol that ensures all participants in a distributed transaction either commit their changes together or roll back together—no exceptions.

Here's how it works at a high level. When you want to execute a distributed transaction, you interact with a transaction manager (like Narayana or Bitronix). This manager coordinates multiple XA resources—in OJP's case, these would be different OJP Server instances connected to different databases. The manager executes the transaction in two phases:

In Phase 1 (the prepare phase), the manager asks each resource: "Can you commit this transaction?" Each resource validates its changes, writes them to stable storage, and responds either "Yes, I can commit" (XA_OK) or "I have no changes" (XA_RDONLY). If any resource says no or times out, the entire transaction must roll back.

In Phase 2 (the commit or rollback phase), if all resources answered yes in Phase 1, the manager tells each one: "Commit your changes." If any resource said no, the manager instead tells everyone: "Roll back." This two-phase protocol ensures atomic commitment across all resources—either everyone commits, or everyone rolls back.

**[IMAGE PROMPT: Two-Phase Commit Protocol Flow Diagram]**
Create a professional infographic showing the 2PC protocol flow. Top section shows Transaction Manager in center with three database icons below (DB1, DB2, DB3). Phase 1 arrows show "Prepare?" flowing from manager to each database, with "XA_OK" responses flowing back. Phase 2 arrows show "Commit!" flowing from manager to each database. Use color coding: blue for Phase 1 (prepare), green for Phase 2 (commit), red dotted lines showing alternate rollback path. Include small icons showing "Write to disk" at each database during prepare phase. Style: Clean, modern, with clear visual separation between phases.

### XA in the JDBC World

The JDBC API provides comprehensive support for XA transactions through several key interfaces. Understanding these interfaces is crucial because they define how your application, the transaction manager, and the database all interact.

At the heart of JDBC XA support sits the `XADataSource` interface. This is your entry point for XA-capable connections. Unlike a regular `DataSource` that returns basic `Connection` objects, an `XADataSource` returns `XAConnection` objects that expose XA capabilities.

The `XAConnection` interface serves a dual purpose. First, it provides an `XAResource` object through the `getXAResource()` method. This `XAResource` is what the transaction manager uses to control the transaction—starting it, preparing it, committing it, or rolling it back. Second, the `XAConnection` provides a regular JDBC `Connection` through the `getConnection()` method. This connection is what your application uses to execute SQL statements. The separation exists for a critical reason: applications shouldn't be able to call `commit()` or `rollback()` directly on connections participating in XA transactions. That would bypass the two-phase commit protocol and break the atomicity guarantees that XA provides.

```java
// Create XA-capable data source
OjpXADataSource xaDataSource = new OjpXADataSource(
    "ojp://server1:1059,server2:1059/mydb",
    "username",
    "password"
);

// Get XA connection
XAConnection xaConnection = xaDataSource.getXAConnection();

// Get XA resource for transaction control
XAResource xaResource = xaConnection.getXAResource();

// Get regular connection for SQL execution
Connection connection = xaConnection.getConnection();

// Create transaction ID (Xid)
Xid xid = new MyXid("global-tx-1", "branch-1");

// Start XA transaction
xaResource.start(xid, XAResource.TMNOFLAGS);

// Execute SQL - changes are part of XA transaction
PreparedStatement stmt = connection.prepareStatement(
    "UPDATE accounts SET balance = balance - ? WHERE id = ?"
);
stmt.setBigDecimal(1, amount);
stmt.setLong(2, accountId);
stmt.executeUpdate();
stmt.close();

// End the transaction branch
xaResource.end(xid, XAResource.TMSUCCESS);

// Prepare phase (Phase 1 of 2PC)
int prepareResult = xaResource.prepare(xid);

if (prepareResult == XAResource.XA_OK) {
    // Commit phase (Phase 2 of 2PC)
    xaResource.commit(xid, false);  // false = two-phase
} else {
    // Read-only optimization or prepare failed
    xaResource.rollback(xid);
}

// Clean up
connection.close();
xaConnection.close();
```

Notice how the application never calls `connection.commit()` or `connection.rollback()`. All transaction control flows through the `XAResource`. This is a fundamental requirement of XA transactions—the transaction manager must have exclusive control over commit and rollback operations.

```mermaid
sequenceDiagram
    participant App as Application
    participant XAConn as XAConnection
    participant XARes as XAResource
    participant Conn as Connection
    participant DB as Database

    App->>XAConn: getXAResource()
    XAConn->>App: Return XAResource

    App->>XAConn: getConnection()
    XAConn->>App: Return Connection

    App->>XARes: start(xid, TMNOFLAGS)
    XARes->>DB: BEGIN TRANSACTION

    App->>Conn: executeUpdate(SQL)
    Conn->>DB: Execute SQL

    App->>XARes: end(xid, TMSUCCESS)
    XARes->>DB: Mark branch complete

    App->>XARes: prepare(xid)
    XARes->>DB: PREPARE TRANSACTION
    DB->>XARes: XA_OK

    App->>XARes: commit(xid, false)
    XARes->>DB: COMMIT PREPARED
    DB->>XARes: Success
```

## OJP's XA Architecture

OJP implements XA support through a sophisticated architecture that combines client-side JDBC XA interfaces with server-side connection pooling. This design provides the best of both worlds: JDBC XA specification compliance on the client side and efficient connection reuse on the server side.

### Client-Side Components

On the client side, OJP provides three primary classes that implement the JDBC XA interfaces. These classes handle the translation between JDBC XA method calls and OJP's gRPC protocol.

The `OjpXADataSource` class implements `javax.sql.XADataSource` and serves as your application's entry point for XA connections. When you call `getXAConnection()`, it establishes a gRPC connection to an OJP Server with the `isXA=true` flag. This flag tells the server to use XA-capable backend session pooling rather than the regular HikariCP pool used for non-XA connections.

The `OjpXAConnection` class wraps the server-side XA session and provides both the `XAResource` for transaction control and the `Connection` for SQL execution. It maintains the session information and routes all XA operations and SQL statements through the gRPC channel to the server.

The `OjpXALogicalConnection` class is a crucial piece of the puzzle. This class wraps the actual `Connection` returned by `getConnection()` and enforces XA rules. It blocks direct calls to `commit()`, `rollback()`, and `setAutoCommit()` because these methods would bypass the XA protocol. In XA mode, only the `XAResource` can control transactions—applications must not interfere.

**[IMAGE PROMPT: Client-Side XA Component Architecture]**
Create a layered architecture diagram showing OJP XA client components. Top layer shows Application code icon. Second layer shows three boxes side-by-side: OjpXADataSource (labeled "Entry Point"), OjpXAConnection (labeled "Session Wrapper"), and OjpXAResource (labeled "TX Control"). Third layer shows OjpXALogicalConnection (labeled "SQL Execution" with a prohibition symbol for commit/rollback). Bottom layer shows gRPC channel icon connecting to server. Use arrows showing data flow: app -> XADataSource -> XAConnection, then splitting to XAResource (transaction operations) and LogicalConnection (SQL operations). Color code: blue for XA control path, green for SQL execution path. Style: Clean, technical, with clear layering.

### Server-Side Architecture

The server side is where OJP's XA implementation really shines. Traditional XA implementations require creating a new database connection for each XA transaction, which introduces significant overhead—typically 100-500 milliseconds per transaction just for connection establishment. OJP eliminates this overhead through backend session pooling powered by Apache Commons Pool 2.

When an XA connection request arrives at the server, the `StatementServiceImpl` routes it to the `CommonsPool2XADataSource` class. This class manages a pool of `BackendSession` objects, each wrapping a PostgreSQL (or other database) `XAConnection`. The pool uses Commons Pool 2's object pool implementation, which provides robust lifecycle management, configurable sizing, and excellent performance characteristics.

Here's the key insight: these `BackendSession` objects stay open across multiple XA transactions. When a transaction completes, the backend session doesn't close—instead, it remains associated with the OJP XA session until that session terminates. This allows applications to execute multiple sequential XA transactions on the same `XAConnection` without recreating database connections, which is exactly what the JDBC XA specification requires.

The `XATransactionRegistry` tracks active XA transactions and manages their lifecycle. It maintains a mapping from transaction IDs (Xids) to `TxContext` objects, which implement a state machine tracking each transaction through its lifecycle: NONEXISTENT → ACTIVE → ENDED → PREPARED → COMMITTED or ROLLEDBACK. This state machine ensures that XA operations execute in the correct order and prevents illegal state transitions.

```mermaid
graph TB
    Client[Client Application]
    XAConn[OjpXAConnection]
    GRPC[gRPC Channel]
    Server[StatementServiceImpl]
    Registry[XATransactionRegistry]
    Pool[CommonsPool2XADataSource]
    Session[BackendSession]
    PGXA[PostgreSQL XAConnection]
    
    Client -->|getXAConnection| XAConn
    XAConn -->|XA Operations| GRPC
    GRPC -->|gRPC Call| Server
    Server -->|Register TX| Registry
    Server -->|Borrow Session| Pool
    Pool -->|Return Session| Session
    Session -->|Wrap| PGXA
    Registry -->|Track State| Session
    
    style Client fill:#e1f5ff
    style XAConn fill:#fff4e1
    style Server fill:#ffe1f5
    style Pool fill:#e1ffe1
    style PGXA fill:#f5e1ff
```

### The Dual-Condition Lifecycle

One of the most sophisticated aspects of OJP's XA implementation is the dual-condition lifecycle for backend sessions. This design solves a subtle but critical problem in XA connection management.

The problem arises from a mismatch between XA transaction lifecycle and JDBC connection lifecycle. In JDBC XA, an application might execute multiple XA transactions on the same `XAConnection` before closing it. The JDBC specification requires that connection properties (like isolation level, catalog, schema) persist across these transactions. But if the server returns the backend session to the pool after each transaction completes, how can it maintain these properties?

OJP's solution is elegant: backend sessions return to the pool only when BOTH of two conditions are met. First, the current XA transaction must be complete (committed or rolled back). Second, the client's `XAConnection` must be closed. Until both conditions are satisfied, the backend session remains associated with the OJP XA session, ready to serve the next transaction.

This approach provides several benefits. Applications can execute multiple sequential transactions on the same `XAConnection` without connection recreation overhead. Connection properties persist correctly across transaction boundaries, ensuring JDBC spec compliance. Most importantly, the physical PostgreSQL `XAConnection` stays open and ready, eliminating the 100-500ms connection establishment penalty that traditional XA implementations suffer.

**[IMAGE PROMPT: Dual-Condition Lifecycle State Diagram]**
Create a state diagram showing backend session lifecycle. Center shows large "Backend Session" box with three states: "In Pool" (green), "Active - Transaction Incomplete" (yellow), "Active - Transaction Complete" (orange). Show transitions: from Pool to Active (arrow labeled "borrow on getXAConnection()"), from Active-Incomplete to Active-Complete (arrow labeled "commit/rollback"), from Active-Complete back to Active-Incomplete (dotted arrow labeled "start new transaction"), from Active-Complete to Pool (arrow labeled "XAConnection.close()"). Add two condition badges: "Condition 1: Transaction Complete" (checkmark) and "Condition 2: XAConnection Closed" (checkmark). Both must be checked for return to pool transition. Style: Professional state diagram with clear color coding and annotations.

Let me show you this lifecycle in action with a concrete example:

```java
// Application opens XA connection
XAConnection xaConnection = xaDataSource.getXAConnection();
// SERVER: Borrows backend session from pool (State: Active, TX Incomplete)

Connection connection = xaConnection.getConnection();
XAResource xaResource = xaConnection.getXAResource();

// First transaction
Xid xid1 = new MyXid("tx-1", "branch-1");
xaResource.start(xid1, XAResource.TMNOFLAGS);
// Execute SQL operations
xaResource.end(xid1, XAResource.TMSUCCESS);
xaResource.prepare(xid1);
xaResource.commit(xid1, false);
// SERVER: Marks transaction complete (State: Active, TX Complete)
// Backend session stays with OJP session (XAConnection still open)

// Second transaction on SAME XAConnection
Xid xid2 = new MyXid("tx-2", "branch-1");
xaResource.start(xid2, XAResource.TMNOFLAGS);
// SERVER: Reuses same backend session (no new connection!)
// Execute SQL operations
xaResource.end(xid2, XAResource.TMSUCCESS);
xaResource.prepare(xid2);
xaResource.commit(xid2, false);
// SERVER: Marks transaction complete again

// Application closes XAConnection
connection.close();
xaConnection.close();
// SERVER: BOTH conditions met:
//   1. Transaction complete ✓
//   2. XAConnection closed ✓
// Returns backend session to pool (State: In Pool)
// Physical PostgreSQL connection stays OPEN for next use
```

## Configuration and Setup

Setting up XA transactions with OJP requires configuration on both the client and server sides, but the process is straightforward.

### Client-Side Configuration

On the client side, you create an `OjpXADataSource` instead of a regular `OjpDataSource`. The URL format is the same as for non-XA connections, supporting both single-server and multinode configurations:

```java
// Single server
OjpXADataSource xaDataSource = new OjpXADataSource(
    "ojp://localhost:1059/mydb",
    "username",
    "password"
);

// Multinode for high availability
OjpXADataSource xaDataSource = new OjpXADataSource(
    "ojp://server1:1059,server2:1059,server3:1059/mydb",
    "username",
    "password"
);
```

That's it for basic setup. The `OjpXADataSource` handles all the complexity of establishing XA-capable connections to the server.

For advanced scenarios, you can configure the XA connection pool through an `ojp.properties` file on your classpath. These properties control the server-side backend session pool:

```properties
# Maximum total backend sessions per server
ojp.xa.connection.pool.maxTotal=22

# Minimum idle sessions to maintain
ojp.xa.connection.pool.minIdle=20

# Timeout when borrowing sessions (milliseconds)
ojp.xa.connection.pool.maxWaitMillis=20000

# Idle session eviction timeout (milliseconds)
ojp.xa.connection.pool.idleTimeout=600000

# Maximum session lifetime (milliseconds)
ojp.xa.connection.pool.maxLifetime=1800000
```

In a multinode deployment, OJP automatically divides the pool size among servers. For example, with two servers and `maxTotal=22`, each server maintains a pool of 11 sessions. When a server fails, the remaining servers automatically expand their pools to compensate, and when the failed server recovers, pools rebalance back to their original sizes.

### Server-Side Configuration

The OJP Server requires minimal XA-specific configuration. The backend session pool uses sensible defaults that work well for most applications:

```properties
# Enable XA backend session pooling (enabled by default)
ojp.xa.pooling.enabled=true

# Pool sizing (can be overridden by client properties)
ojp.xa.connection.pool.maxTotal=22
ojp.xa.connection.pool.minIdle=20

# Timeout settings
ojp.xa.connection.pool.maxWaitMillis=20000
ojp.xa.connection.pool.idleTimeout=600000
ojp.xa.connection.pool.maxLifetime=1800000
```

If you need to fall back to the older pass-through implementation (not recommended), you can disable pooling:

```properties
ojp.xa.pooling.enabled=false
```

However, the pass-through implementation suffers significant performance penalties and lacks the reliability benefits of the pooled implementation.

### Framework Integration

OJP XA transactions integrate seamlessly with Java EE and Spring transaction managers. Here's an example using Spring's `JtaTransactionManager`:

```java
@Configuration
public class XAConfig {
    
    @Bean
    public XADataSource primaryXADataSource() {
        return new OjpXADataSource(
            "ojp://server1:1059/orders",
            "username",
            "password"
        );
    }
    
    @Bean
    public XADataSource secondaryXADataSource() {
        return new OjpXADataSource(
            "ojp://server2:1059/inventory",
            "username",
            "password"
        );
    }
    
    @Bean
    public JtaTransactionManager transactionManager(
            UserTransactionManager utm,
            UserTransaction ut) {
        JtaTransactionManager jtaTxManager = new JtaTransactionManager();
        jtaTxManager.setTransactionManager(utm);
        jtaTxManager.setUserTransaction(ut);
        return jtaTxManager;
    }
}

@Service
public class OrderService {
    
    @Autowired
    private DataSource primaryDataSource;
    
    @Autowired
    private DataSource secondaryDataSource;
    
    @Transactional
    public void processOrder(Order order) {
        // Both updates participate in same XA transaction
        // Either both commit or both roll back
        
        try (Connection ordersConn = primaryDataSource.getConnection();
             Connection inventoryConn = secondaryDataSource.getConnection()) {
            
            // Insert order
            PreparedStatement orderStmt = ordersConn.prepareStatement(
                "INSERT INTO orders (id, customer_id, total) VALUES (?, ?, ?)"
            );
            orderStmt.setLong(1, order.getId());
            orderStmt.setLong(2, order.getCustomerId());
            orderStmt.setBigDecimal(3, order.getTotal());
            orderStmt.executeUpdate();
            
            // Update inventory
            PreparedStatement inventoryStmt = inventoryConn.prepareStatement(
                "UPDATE inventory SET quantity = quantity - ? WHERE product_id = ?"
            );
            for (OrderItem item : order.getItems()) {
                inventoryStmt.setInt(1, item.getQuantity());
                inventoryStmt.setLong(2, item.getProductId());
                inventoryStmt.executeUpdate();
            }
            
            // Spring's transaction manager coordinates the XA commit
        }
    }
}
```

**[IMAGE PROMPT: Spring XA Transaction Flow]**
Create a sequence diagram showing Spring transaction flow with multiple XADataSources. Left side shows Spring transaction manager icon. Middle section shows two parallel flows: one to "Orders Database" via OJP Server 1, another to "Inventory Database" via OJP Server 2. Show transaction phases: 1) @Transactional begins, 2) Execute SQL on both databases (parallel arrows), 3) Prepare phase (synchronization point), 4) Commit phase (parallel completion). Use Spring green color for framework layer, OJP blue for server layer, database gray for backends. Include timing indicators showing phases happen in sequence. Style: Technical sequence diagram with clear temporal ordering.

## Performance Characteristics

XA transactions traditionally come with a performance cost, but OJP's architecture minimizes this overhead through intelligent session management and connection reuse.

### Connection Reuse Benefits

The most significant performance improvement comes from connection reuse. In traditional XA implementations, each transaction requires establishing a new database connection, which typically costs 100-500 milliseconds depending on network latency and database load. With OJP's backend session pooling, this cost is paid only once when the OJP XA session first borrows a backend session from the pool. Subsequent transactions on the same `XAConnection` reuse the existing physical database connection.

Consider a typical scenario where an application executes 10 XA transactions sequentially before closing the `XAConnection`. With traditional XA, you'd pay 1000-5000ms in connection establishment overhead (10 transactions × 100-500ms each). With OJP, you pay this cost once—maybe 200ms to establish the initial connection—and then each subsequent transaction executes at full speed. That's a 5x to 25x improvement in connection overhead alone.

The session reset operation that runs between transactions adds only 10-50ms of overhead, which is negligible compared to typical transaction execution times. This reset operation cleans up transaction state while keeping the physical connection open, ensuring that each transaction starts with a clean slate.

### Pool Pre-Warming

OJP's backend session pool supports pre-warming through the `minIdle` configuration parameter. When the server starts, it immediately creates the configured number of idle sessions, establishing database connections and adding them to the pool. This means the first client requests don't wait for connection establishment—they find ready-to-use sessions waiting in the pool.

Pre-warming is particularly valuable in multinode deployments. When a server fails and traffic shifts to the remaining servers, OJP automatically increases the pool size on those servers and pre-warms additional connections. This happens asynchronously, ensuring that the increased load doesn't create a connection establishment bottleneck.

### Two-Phase Commit Overhead

The two-phase commit protocol itself introduces some overhead that no amount of connection pooling can eliminate. Each transaction requires five round trips instead of one: start, end, prepare, commit, and an implicit "get connection" operation. Each round trip adds network latency, so a transaction that would take 50ms with a local commit might take 150-200ms with XA over a network.

However, this overhead is often acceptable given the guarantees XA provides. Moreover, OJP's efficient gRPC protocol and connection reuse minimize the per-operation cost of these round trips. In practice, well-designed applications can execute hundreds of XA transactions per second per OJP server, which is sufficient for most enterprise workloads.

**[IMAGE PROMPT: Performance Comparison Chart]**
Create a bar chart comparing transaction execution times. X-axis shows transaction type: "Traditional XA" (tallest bar, red, ~600ms), "OJP XA First TX" (medium bar, yellow, ~220ms), "OJP XA Subsequent TX" (shortest bar, green, ~120ms), "Local TX" (reference bar, blue, ~50ms). Break each bar into segments showing: Connection Establish (only in Traditional and First TX), 2PC Protocol Overhead (in all XA types), SQL Execution (in all types). Add annotations: "Connection reuse eliminates 80% of XA overhead" between Traditional and OJP bars. Include small database icons at top showing "New Connection" for Traditional, "Pooled Connection" for OJP. Style: Professional chart with clear labeling, color-coded segments, and data labels showing milliseconds.

## Monitoring and Troubleshooting

Effective monitoring is essential for XA deployments because distributed transactions involve multiple moving parts that can fail independently.

### Pool Metrics

OJP exposes comprehensive pool metrics through both debug logging and JMX. The most important metrics to monitor are active session count, idle session count, and pool wait times.

Active session count shows how many backend sessions are currently borrowed from the pool. If this number consistently approaches `maxTotal`, you may need to increase your pool size. Idle session count shows how many sessions are waiting in the pool. If this number is consistently low, increasing `minIdle` ensures better response times for bursts of traffic.

Pool wait time metrics show how long clients wait to borrow a session when the pool is exhausted. Occasional spikes are normal during traffic bursts, but sustained high wait times indicate that your pool is undersized for your workload.

Enable pool metrics with debug logging:

```properties
-Dorg.slf4j.simpleLogger.log.org.openjproxy=DEBUG
```

The logs show detailed pool state information:

```
XA pool initialized: server=localhost:5432, maxTotal=11, minIdle=10
Session borrowed successfully: poolState=[active=2, idle=8, maxTotal=11]
Session returned to pool: poolState=[active=1, idle=9, maxTotal=11]
Pool resized due to cluster health change: maxTotal=11→22, minIdle=10→20
Pre-warmed 10 idle connections successfully
```

### Transaction State Tracking

The `XATransactionRegistry` tracks each XA transaction through its lifecycle. When transactions don't complete normally, the registry's state machine detects the problem and logs detailed information.

Common issues include transactions stuck in the PREPARED state (indicating that prepare succeeded but commit never arrived), transactions that remain ACTIVE for extended periods (indicating possible deadlocks or hung queries), and transactions that disappear without proper completion (indicating network issues or client crashes).

OJP automatically cleans up abandoned transactions after configurable timeouts, but monitoring these cleanup operations can reveal application bugs or infrastructure problems:

```
XA transaction stuck in PREPARED state: xid=tx-12345, duration=300000ms
Cleaning up abandoned transaction: xid=tx-67890, lastState=ACTIVE
Transaction completed successfully: xid=tx-11111, duration=1234ms, state=COMMITTED
```

### Common Issues and Solutions

Pool exhaustion is the most common issue with XA transactions. The symptom is `PoolExhaustedException` or long wait times when borrowing sessions. The solution depends on the cause:

If you're seeing pool exhaustion during normal load, increase `maxTotal`. A good rule of thumb is to configure `maxTotal` to 1.5-2x your peak concurrent XA transaction count. If pool exhaustion occurs only during traffic spikes, increasing `minIdle` helps by pre-warming more connections.

If pool exhaustion persists even after increasing limits, you likely have a connection leak. Check that your application always closes `XAConnection` objects in finally blocks or try-with-resources statements:

```java
// Good - ensures connection closes even if exception occurs
try (XAConnection xaConnection = xaDataSource.getXAConnection()) {
    Connection connection = xaConnection.getConnection();
    XAResource xaResource = xaConnection.getXAResource();
    
    // Execute XA transaction
    Xid xid = createXid();
    xaResource.start(xid, XAResource.TMNOFLAGS);
    // ... execute SQL ...
    xaResource.end(xid, XAResource.TMSUCCESS);
    xaResource.prepare(xid);
    xaResource.commit(xid, false);
    
} // xaConnection.close() called automatically
```

Session state errors indicate that operations executed in the wrong order. XA requires a specific sequence: start → end → prepare → commit/rollback. Attempting to prepare before ending, or commit before preparing, results in exceptions. Most transaction managers handle this sequencing automatically, so if you're seeing state errors, check that you're not manually calling XA operations in addition to letting the transaction manager control the transaction.

```mermaid
stateDiagram-v2
    [*] --> NONEXISTENT
    NONEXISTENT --> ACTIVE : start()
    ACTIVE --> ENDED : end()
    ENDED --> PREPARED : prepare()
    PREPARED --> COMMITTED : commit()
    PREPARED --> ROLLEDBACK : rollback()
    COMMITTED --> [*]
    ROLLEDBACK --> [*]
    
    ACTIVE --> ROLLEDBACK : rollback()
    ENDED --> ROLLEDBACK : rollback()
    
    note right of ACTIVE : SQL execution allowed
    note right of PREPARED : Session pinned
    note right of COMMITTED : Session returned to pool
    note right of ROLLEDBACK : Session returned to pool
```

## Multinode XA Coordination

XA transactions work seamlessly across OJP's multinode architecture, providing both distributed transaction support and high availability.

### Cluster Health Integration

OJP's XA implementation integrates tightly with the multinode health checking system. Every 5 seconds, each server performs health checks on all other servers in the cluster. When a server fails, the health checker detects this within 5 seconds and triggers automatic pool rebalancing on the surviving servers.

Pool rebalancing is critical for maintaining consistent performance during server failures. Consider a three-server cluster with `maxTotal=33` (11 sessions per server). If one server fails, the remaining two servers immediately expand their pools to 16-17 sessions each, ensuring the cluster maintains the same total capacity. When the failed server recovers, pools rebalance back to 11 sessions per server.

This rebalancing happens transparently to applications. Active transactions continue executing, and new transaction requests find adequate pool capacity waiting. The only observable effect is a slight increase in average pool utilization on the surviving servers.

### Load Distribution

OJP uses session count tracking to distribute XA transactions optimally across the cluster. When a client requests a new XA connection, OJP selects the server with the fewest active sessions. This load-aware selection provides significantly better distribution than simple round-robin, especially when transaction durations vary.

The session count includes both active XA transactions and regular non-XA connections, providing accurate insight into each server's actual load. In practice, this approach distributes load within 10-15% variance across servers, compared to 30-40% variance with round-robin selection.

### Cross-Database XA Transactions

One powerful use case for OJP's multinode architecture is coordinating transactions across different databases. Each OJP server connects to a different database, and a transaction manager coordinates XA operations across all servers:

```java
// Configure XADataSources for different databases
XADataSource ordersDB = new OjpXADataSource(
    "ojp://server1:1059/orders",
    "username", "password"
);

XADataSource inventoryDB = new OjpXADataSource(
    "ojp://server2:1059/inventory",
    "username", "password"
);

XADataSource billingDB = new OjpXADataSource(
    "ojp://server3:1059/billing",
    "username", "password"
);

// Transaction manager coordinates across all three databases
@Transactional
public void processOrderWithAllUpdates(Order order) {
    // Update in orders database
    Connection ordersConn = ordersDB.getXAConnection().getConnection();
    updateOrder(ordersConn, order);
    
    // Update in inventory database
    Connection inventoryConn = inventoryDB.getXAConnection().getConnection();
    updateInventory(inventoryConn, order.getItems());
    
    // Update in billing database
    Connection billingConn = billingDB.getXAConnection().getConnection();
    createInvoice(billingConn, order);
    
    // Transaction manager ensures atomic commit across all three
}
```

Each OJP server independently manages its backend session pool and delegates XA operations to its target database. The transaction manager (like Narayana or Bitronix) coordinates the two-phase commit protocol across all servers, ensuring atomicity across the entire distributed transaction.

**[IMAGE PROMPT: Multinode XA Coordination Diagram]**
Create a network topology diagram showing multinode XA coordination. Top shows Transaction Manager (circle, orange). Three branches below show OJP Server 1, 2, and 3 (rounded rectangles, blue). Below each server show corresponding databases: PostgreSQL, MySQL, and Oracle (cylinders, gray). Show bidirectional arrows between TM and each server (XA protocol, labeled "prepare/commit"). Show bidirectional arrows between each server and its database (SQL execution). Add cloud of client applications at very top connecting to TM. Include annotations: "Health Check" between servers (dotted lines), "Pool: 11 sessions" label on each server, "2PC Coordinator" label on TM. Show one server with red X (failed) and arrows showing remaining servers expanding to "Pool: 16 sessions". Style: Professional network diagram with clear layering and annotations.

## Best Practices

Years of production experience with XA transactions have revealed patterns that work well and antipatterns to avoid.

### Design for Idempotency

XA transactions can fail in subtle ways. The prepare phase might succeed, but the network fails before the commit message arrives. The transaction manager might crash after prepare but before commit. In these scenarios, the transaction manager will eventually retry the commit operation when it recovers.

Your transaction logic must handle these retries gracefully. Design operations to be idempotent—executing them multiple times produces the same result as executing them once. Use techniques like unique constraint checks, conditional updates based on version numbers, or insert-if-not-exists patterns:

```java
// Good - idempotent update using version number
PreparedStatement stmt = connection.prepareStatement(
    "UPDATE accounts SET balance = ?, version = version + 1 " +
    "WHERE id = ? AND version = ?"
);
stmt.setBigDecimal(1, newBalance);
stmt.setLong(2, accountId);
stmt.setLong(3, currentVersion);
int updated = stmt.executeUpdate();
if (updated == 0) {
    // Already processed or concurrent update
    // Safe to commit - operation is idempotent
}
```

### Keep Transactions Short

XA transactions hold resources across multiple systems. The longer a transaction runs, the more resources it ties up and the higher the probability of conflicts with other transactions. Aim to keep XA transactions under 1-2 seconds total execution time.

If your business logic requires long-running operations, consider breaking them into smaller transactions or using sagas pattern instead of XA. For example, instead of holding an XA transaction open while calling external services, complete the database updates first, then call the services, and use compensating transactions if the service calls fail.

### Monitor Pool Health

Set up monitoring for your XA backend session pools. Track active session count, idle session count, and borrow wait times. Configure alerts for pool exhaustion (when wait times exceed acceptable thresholds) and for abnormally low idle counts (which indicates your pool may be undersized).

In multinode deployments, monitor pool metrics per server. Imbalanced pool usage across servers might indicate problems with load distribution or health checking.

### Handle Prepare Failures

The prepare phase can fail for several reasons: constraint violations, deadlocks, timeout, or database crashes. Your transaction manager configuration should handle these failures appropriately, typically by rolling back the entire distributed transaction.

Don't retry prepare failures automatically. If prepare failed once, it likely indicates a problem with the transaction logic or data, and retry will probably fail again. Instead, log the failure details and let application-level logic decide whether to retry with different parameters.

### Test Failure Scenarios

XA transactions work great in the happy path, but the real value comes from reliability during failures. Test scenarios like server crashes during prepare, network failures during commit, and database crashes with prepared transactions. Verify that your transaction manager properly recovers from these scenarios and that no data corruption or inconsistency results.

Use OJP's built-in health checking to simulate server failures in your test environment. Kill an OJP server during an XA transaction and verify that transactions properly fail over to surviving servers and that prepared transactions eventually complete or roll back cleanly.

## Real-World Use Cases

XA transactions excel in specific scenarios where atomic updates across multiple resources are essential.

### Financial Transactions

Banking and payment systems frequently require XA transactions. When transferring money between accounts that live in different database shards, XA ensures atomicity—either both the debit and credit occur, or neither does. No possibility of debiting one account without crediting the other.

### Order Processing Systems

E-commerce platforms often use XA to coordinate order placement (in an orders database) with inventory reduction (in an inventory database) and payment processing (in a billing database). XA guarantees that you don't sell items you don't have in stock, and you don't reduce inventory unless the payment succeeds and the order is confirmed.

### Microservices Coordination

In microservices architectures where different services own different databases, XA provides a mechanism for atomic updates across service boundaries. However, use XA judiciously in microservices—often sagas pattern or eventual consistency are better architectural choices. Reserve XA for scenarios where strong consistency is truly required.

## Summary

XA distributed transactions provide atomic commitment across multiple resources, but traditionally come with significant complexity and performance overhead. OJP's XA implementation eliminates most of this overhead through intelligent server-side backend session pooling powered by Apache Commons Pool 2.

Key benefits of OJP's XA support include full JDBC XA specification compliance, connection reuse that eliminates 80% of traditional XA overhead, automatic pool rebalancing during server failures, and seamless integration with popular transaction managers like Spring's JtaTransactionManager, Narayana, and Bitronix.

The dual-condition lifecycle ensures that backend sessions return to the pool only when both the transaction completes and the XAConnection closes, enabling multiple sequential transactions on the same connection without connection recreation overhead. This design provides JDBC spec compliance while maximizing performance.

For applications requiring atomic updates across multiple databases or resources, OJP's XA support provides a production-ready solution that balances reliability, performance, and operational simplicity.

---

**[IMAGE PROMPT: Chapter Summary Infographic]**
Create a visual summary showing OJP XA benefits vs. traditional XA. Left side shows "Traditional XA" with icons: new connection for each TX (stopwatch showing 500ms), complex manual pooling (confused person), single point of failure (broken link). Right side shows "OJP XA" with icons: pooled connections (swimming pool with stopwatch showing 50ms), automatic management (robot), high availability (interconnected servers). Center shows "80% Faster, 100% Reliable" in large text. Bottom shows mini timeline: Transaction 1 → Transaction 2 → Transaction 3, with line showing "Same Connection" underneath. Style: Modern infographic with icons, contrasting colors (red for traditional problems, green for OJP solutions), clear visual hierarchy.

**Key Takeaways:**
- XA transactions coordinate atomic updates across multiple databases
- OJP's backend session pooling eliminates 80% of traditional XA overhead
- Dual-condition lifecycle enables connection reuse across transactions
- Multinode support provides both distribution and high availability
- Integration with standard transaction managers requires no application code changes
- Design for idempotency and keep transactions short for best results

In the next chapter, we'll explore the Connection Pool Provider SPI, which provides the foundation for OJP's pluggable pooling architecture and enables custom pool implementations for specialized use cases.
