package org.openjproxy.grpc.server.pool;

import com.openjproxy.grpc.ConnectionDetails;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.CircuitBreaker;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.StatementServiceImpl;
import com.openjproxy.grpc.SessionInfo;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Non-XA unpooled connection management.
 * Tests the lifecycle and management of unpooled connections.
 */
public class NonXAUnpooledConnectionManagementTest {

    private StatementServiceImpl statementService;
    private SessionManager sessionManager;
    private CircuitBreaker circuitBreaker;
    private ServerConfiguration serverConfiguration;

    @BeforeEach
    public void setUp() {
        sessionManager = mock(SessionManager.class);
        circuitBreaker = mock(CircuitBreaker.class);
        serverConfiguration = new ServerConfiguration();
        statementService = new StatementServiceImpl(sessionManager, circuitBreaker, serverConfiguration);
        
        // Clear DataSourceConfigurationManager cache
        DataSourceConfigurationManager.clearCache();
    }

    @Test
    public void testUnpooledConnectionDetailsCreation() throws Exception {
        // Create connection request with pool disabled
        Map<String, Object> propertiesMap = new HashMap<>();
        propertiesMap.put(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        propertiesMap.put(CommonConstants.DATASOURCE_NAME_PROPERTY, "unpooledDS");
        
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059]_h2:mem:testdb")
                .setUser("testuser")
                .setPassword("testpass")
                .setClientUUID("test-client-uuid")
                .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap))
                .build();

        StreamObserver<SessionInfo> responseObserver = mock(StreamObserver.class);

        // Call connect
        statementService.connect(connectionDetails, responseObserver);

        // Verify response was sent
        ArgumentCaptor<SessionInfo> sessionCaptor = ArgumentCaptor.forClass(SessionInfo.class);
        verify(responseObserver).onNext(sessionCaptor.capture());
        verify(responseObserver).onCompleted();

        SessionInfo sessionInfo = sessionCaptor.getValue();
        assertNotNull(sessionInfo);
        assertFalse(sessionInfo.getIsXA());

        // Use reflection to check unpooledConnectionDetailsMap
        Field unpooledMapField = StatementServiceImpl.class.getDeclaredField("unpooledConnectionDetailsMap");
        unpooledMapField.setAccessible(true);
        Map<?, ?> unpooledMap = (Map<?, ?>) unpooledMapField.get(statementService);

        // Verify unpooled connection details were stored
        assertFalse(unpooledMap.isEmpty(), "Unpooled connection details should be stored");
        
        // Verify no datasource was created in datasourceMap
        Field datasourceMapField = StatementServiceImpl.class.getDeclaredField("datasourceMap");
        datasourceMapField.setAccessible(true);
        Map<?, ?> datasourceMap = (Map<?, ?>) datasourceMapField.get(statementService);
        
        // The datasourceMap might have entries from other tests, but not for this connHash
        String connHash = sessionInfo.getConnHash();
        assertFalse(datasourceMap.containsKey(connHash), 
            "No HikariDataSource should be created when pool is disabled");
    }

    @Test
    public void testPooledConnectionCreatesDataSource() throws Exception {
        // Create connection request with pool ENABLED
        Map<String, Object> propertiesMap = new HashMap<>();
        propertiesMap.put(CommonConstants.POOL_ENABLED_PROPERTY, "true");
        propertiesMap.put(CommonConstants.DATASOURCE_NAME_PROPERTY, "pooledDS");
        
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059]_h2:mem:pooleddb")
                .setUser("testuser")
                .setPassword("testpass")
                .setClientUUID("test-client-uuid-pooled")
                .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap))
                .build();

        StreamObserver<SessionInfo> responseObserver = mock(StreamObserver.class);

        // Call connect
        statementService.connect(connectionDetails, responseObserver);

        // Verify response was sent
        ArgumentCaptor<SessionInfo> sessionCaptor = ArgumentCaptor.forClass(SessionInfo.class);
        verify(responseObserver).onNext(sessionCaptor.capture());
        verify(responseObserver).onCompleted();

        SessionInfo sessionInfo = sessionCaptor.getValue();
        
        // Use reflection to check unpooledConnectionDetailsMap
        Field unpooledMapField = StatementServiceImpl.class.getDeclaredField("unpooledConnectionDetailsMap");
        unpooledMapField.setAccessible(true);
        Map<?, ?> unpooledMap = (Map<?, ?>) unpooledMapField.get(statementService);

        String connHash = sessionInfo.getConnHash();
        
        // Verify NO unpooled details were stored
        assertFalse(unpooledMap.containsKey(connHash), 
            "Unpooled connection details should NOT be stored when pool is enabled");
        
        // Verify datasource WAS created in datasourceMap
        Field datasourceMapField = StatementServiceImpl.class.getDeclaredField("datasourceMap");
        datasourceMapField.setAccessible(true);
        Map<?, ?> datasourceMap = (Map<?, ?>) datasourceMapField.get(statementService);
        
        assertTrue(datasourceMap.containsKey(connHash), 
            "DataSource should be created when pool is enabled");
    }

    @Test
    public void testUnpooledConnectionDetailsStoredCorrectly() throws Exception {
        Map<String, Object> propertiesMap = new HashMap<>();
        propertiesMap.put(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        propertiesMap.put(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "20000");
        
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059]_h2:mem:detailstest")
                .setUser("detailsuser")
                .setPassword("detailspass")
                .setClientUUID("details-client-uuid")
                .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap))
                .build();

        StreamObserver<SessionInfo> responseObserver = mock(StreamObserver.class);
        statementService.connect(connectionDetails, responseObserver);

        // Verify connection details structure via reflection
        Field unpooledMapField = StatementServiceImpl.class.getDeclaredField("unpooledConnectionDetailsMap");
        unpooledMapField.setAccessible(true);
        Map<String, ?> unpooledMap = (Map<String, ?>) unpooledMapField.get(statementService);

        assertFalse(unpooledMap.isEmpty());
        
        // Get the stored details object
        Object detailsObj = unpooledMap.values().iterator().next();
        assertNotNull(detailsObj);
        
        // Verify the details object has the expected structure
        Class<?> detailsClass = detailsObj.getClass();
        assertTrue(detailsClass.getName().contains("UnpooledConnectionDetails"));
    }

    @Test
    public void testMultipleUnpooledConnections() throws Exception {
        // Create first unpooled connection
        Map<String, Object> props1 = new HashMap<>();
        props1.put(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props1.put(CommonConstants.DATASOURCE_NAME_PROPERTY, "unpooled1");
        
        ConnectionDetails conn1 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059]_h2:mem:db1")
                .setUser("user1")
                .setPassword("pass1")
                .setClientUUID("client1")
                .addAllProperties(ProtoConverter.propertiesToProto(props1))
                .build();

        StreamObserver<SessionInfo> observer1 = mock(StreamObserver.class);
        statementService.connect(conn1, observer1);

        // Create second unpooled connection
        Map<String, Object> props2 = new HashMap<>();
        props2.put(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props2.put(CommonConstants.DATASOURCE_NAME_PROPERTY, "unpooled2");
        
        ConnectionDetails conn2 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059]_h2:mem:db2")
                .setUser("user2")
                .setPassword("pass2")
                .setClientUUID("client2")
                .addAllProperties(ProtoConverter.propertiesToProto(props2))
                .build();

        StreamObserver<SessionInfo> observer2 = mock(StreamObserver.class);
        statementService.connect(conn2, observer2);

        // Verify both connections stored their details
        Field unpooledMapField = StatementServiceImpl.class.getDeclaredField("unpooledConnectionDetailsMap");
        unpooledMapField.setAccessible(true);
        Map<?, ?> unpooledMap = (Map<?, ?>) unpooledMapField.get(statementService);

        assertEquals(2, unpooledMap.size(), "Both unpooled connections should be stored");
    }

    @Test
    public void testMixedPooledAndUnpooledConnections() throws Exception {
        // Create pooled connection
        Map<String, Object> pooledProps = new HashMap<>();
        pooledProps.put(CommonConstants.POOL_ENABLED_PROPERTY, "true");
        pooledProps.put(CommonConstants.DATASOURCE_NAME_PROPERTY, "pooled");
        
        ConnectionDetails pooled = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059]_h2:mem:pooleddb")
                .setUser("pooleduser")
                .setPassword("pooledpass")
                .setClientUUID("pooled-client")
                .addAllProperties(ProtoConverter.propertiesToProto(pooledProps))
                .build();

        StreamObserver<SessionInfo> pooledObserver = mock(StreamObserver.class);
        statementService.connect(pooled, pooledObserver);

        // Create unpooled connection
        Map<String, Object> unpooledProps = new HashMap<>();
        unpooledProps.put(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        unpooledProps.put(CommonConstants.DATASOURCE_NAME_PROPERTY, "unpooled");
        
        ConnectionDetails unpooled = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059]_h2:mem:unpooleddb")
                .setUser("unpooleduser")
                .setPassword("unpooledpass")
                .setClientUUID("unpooled-client")
                .addAllProperties(ProtoConverter.propertiesToProto(unpooledProps))
                .build();

        StreamObserver<SessionInfo> unpooledObserver = mock(StreamObserver.class);
        statementService.connect(unpooled, unpooledObserver);

        // Capture the session info from both connections
        ArgumentCaptor<SessionInfo> pooledCaptor = ArgumentCaptor.forClass(SessionInfo.class);
        verify(pooledObserver).onNext(pooledCaptor.capture());
        String pooledConnHash = pooledCaptor.getValue().getConnHash();
        
        ArgumentCaptor<SessionInfo> unpooledCaptor = ArgumentCaptor.forClass(SessionInfo.class);
        verify(unpooledObserver).onNext(unpooledCaptor.capture());
        String unpooledConnHash = unpooledCaptor.getValue().getConnHash();

        // Verify correct storage in respective maps
        Field datasourceMapField = StatementServiceImpl.class.getDeclaredField("datasourceMap");
        datasourceMapField.setAccessible(true);
        Map<String, ?> datasourceMap = (Map<String, ?>) datasourceMapField.get(statementService);

        Field unpooledMapField = StatementServiceImpl.class.getDeclaredField("unpooledConnectionDetailsMap");
        unpooledMapField.setAccessible(true);
        Map<String, ?> unpooledMap = (Map<String, ?>) unpooledMapField.get(statementService);

        // Verify pooled connection is in datasourceMap
        assertTrue(datasourceMap.containsKey(pooledConnHash), 
            "Pooled connection should be in datasourceMap");
        
        // Verify unpooled connection is in unpooledMap
        assertTrue(unpooledMap.containsKey(unpooledConnHash), 
            "Unpooled connection should be in unpooledMap");
        
        // Verify unpooled connection is NOT in datasourceMap
        assertFalse(datasourceMap.containsKey(unpooledConnHash),
            "Unpooled connection should NOT be in datasourceMap");
        
        // Verify pooled connection is NOT in unpooledMap
        assertFalse(unpooledMap.containsKey(pooledConnHash),
            "Pooled connection should NOT be in unpooledMap");
    }

    @Test
    public void testUnpooledConnectionWithDefaultDatasourceName() throws Exception {
        // Create connection without explicit datasource name
        Map<String, Object> propertiesMap = new HashMap<>();
        propertiesMap.put(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059]_h2:mem:defaultdb")
                .setUser("user")
                .setPassword("pass")
                .setClientUUID("default-client")
                .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap))
                .build();

        StreamObserver<SessionInfo> responseObserver = mock(StreamObserver.class);
        statementService.connect(connectionDetails, responseObserver);

        // Verify connection succeeds
        verify(responseObserver).onNext(any(SessionInfo.class));
        verify(responseObserver).onCompleted();

        // Verify unpooled details stored
        Field unpooledMapField = StatementServiceImpl.class.getDeclaredField("unpooledConnectionDetailsMap");
        unpooledMapField.setAccessible(true);
        Map<?, ?> unpooledMap = (Map<?, ?>) unpooledMapField.get(statementService);

        assertFalse(unpooledMap.isEmpty());
    }
}
