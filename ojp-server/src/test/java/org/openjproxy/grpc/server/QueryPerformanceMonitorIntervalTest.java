package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for QueryPerformanceMonitor interval-based global average update functionality.
 */
class QueryPerformanceMonitorIntervalTest {

    private MockTimeProvider mockTimeProvider;
    private QueryPerformanceMonitor monitor;

    // Mock time provider for testing
    private static class MockTimeProvider implements TimeProvider {
        private long currentTimeSeconds = 1000L; // Start at some arbitrary time

        public void advanceTime(long seconds) {
            currentTimeSeconds += seconds;
        }


        @Override
        public long currentTimeSeconds() {
            return currentTimeSeconds;
        }
    }

    @BeforeEach
    void setUp() {
        mockTimeProvider = new MockTimeProvider();
    }

    @Test
    void testDefaultBehavior_AlwaysUpdate() {
        // Test always-update behavior (interval = 0) - note: 300 seconds is the actual default
        monitor = new QueryPerformanceMonitor(0L, mockTimeProvider);

        // Record first operation
        monitor.recordExecutionTime("op1", 100.0);
        assertEquals(100.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time significantly
        mockTimeProvider.advanceTime(1000);

        // Record another operation on same hash - should still update
        monitor.recordExecutionTime("op1", 200.0);
        assertEquals(120.0, monitor.getOverallAverageExecutionTime(), 0.001); // (100*4 + 200)/5 = 120
    }

    @Test
    void testIntervalBasedUpdate_WithinInterval() {
        // Set 60-second update interval
        monitor = new QueryPerformanceMonitor(60L, mockTimeProvider);

        // Record first operation - should update global average immediately
        monitor.recordExecutionTime("op1", 100.0);
        assertEquals(100.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time by 30 seconds (within interval)
        mockTimeProvider.advanceTime(30);

        // Record same operation again - should NOT update global average
        monitor.recordExecutionTime("op1", 200.0);
        assertEquals(100.0, monitor.getOverallAverageExecutionTime(), 0.001); // Should remain 100

        // Advance time past interval (total 70 seconds)
        mockTimeProvider.advanceTime(40);

        // Record same operation again - should now update global average
        monitor.recordExecutionTime("op1", 300.0);
        double expectedAvg = ((((100.0 * 4) + 200.0) / 5.0) * 4 + 300.0) / 5.0; // 140ms
        assertEquals(expectedAvg, monitor.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testNewUniqueQuery_ImmediateUpdate() {
        // Set 60-second update interval
        monitor = new QueryPerformanceMonitor(60L, mockTimeProvider);

        // Record first operation
        monitor.recordExecutionTime("op1", 100.0);
        assertEquals(100.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time by only 10 seconds (well within interval)
        mockTimeProvider.advanceTime(10);

        // Record a NEW operation - should update global average immediately despite interval
        monitor.recordExecutionTime("op2", 200.0);
        assertEquals(150.0, monitor.getOverallAverageExecutionTime(), 0.001); // (100 + 200) / 2

        // Advance time by another 10 seconds (still within interval for next update)
        mockTimeProvider.advanceTime(10);

        // Record another NEW operation - should update immediately again
        monitor.recordExecutionTime("op3", 300.0);
        assertEquals(200.0, monitor.getOverallAverageExecutionTime(), 0.001); // (100 + 200 + 300) / 3
    }

    @Test
    void testNewUniqueQuery_ResetsInterval() {
        // Set 60-second update interval
        monitor = new QueryPerformanceMonitor(60L, mockTimeProvider);

        // Record first operation
        monitor.recordExecutionTime("op1", 100.0);
        assertEquals(100.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time by 50 seconds
        mockTimeProvider.advanceTime(50);

        // Record a NEW operation - should update immediately and reset interval timer
        monitor.recordExecutionTime("op2", 200.0);
        assertEquals(150.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time by 50 seconds from the new update
        mockTimeProvider.advanceTime(50);

        // Record existing operation - should NOT update (within new interval)
        monitor.recordExecutionTime("op1", 150.0);
        assertEquals(150.0, monitor.getOverallAverageExecutionTime(), 0.001); // Should remain unchanged

        // Advance time by another 20 seconds (total 70 seconds from last update)
        mockTimeProvider.advanceTime(20);

        // Record existing operation - should now update
        monitor.recordExecutionTime("op1", 400.0);
        // Calculate op1 average step by step:
        // Initial: 100ms
        // After 150ms: ((100 * 4) + 150) / 5 = 110ms
        // After 400ms: ((110 * 4) + 400) / 5 = 168ms
        double expectedGlobalAvg = (168.0 + 200.0) / 2.0; // (168 + 200) / 2 = 184ms
        assertEquals(expectedGlobalAvg, monitor.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testMultipleOperations_IntervalRespected() {
        // Set 30-second update interval
        monitor = new QueryPerformanceMonitor(30L, mockTimeProvider);

        // Record multiple operations initially
        monitor.recordExecutionTime("op1", 100.0);
        monitor.recordExecutionTime("op2", 200.0); // New operation, triggers update
        monitor.recordExecutionTime("op3", 300.0); // New operation, triggers update
        assertEquals(200.0, monitor.getOverallAverageExecutionTime(), 0.001); // (100 + 200 + 300) / 3

        // Advance time by 20 seconds (within interval)
        mockTimeProvider.advanceTime(20);

        // Update existing operations - should NOT trigger global average update
        monitor.recordExecutionTime("op1", 150.0); // avg becomes 130ms
        monitor.recordExecutionTime("op2", 250.0); // avg becomes 220ms
        monitor.recordExecutionTime("op3", 350.0); // avg becomes 320ms

        // Global average should remain unchanged
        assertEquals(200.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time past interval
        mockTimeProvider.advanceTime(20); // Total 40 seconds

        // Next update should trigger global average recalculation
        monitor.recordExecutionTime("op1", 170.0);
        // Calculate step by step:
        // op1: 100 -> 110 (after 150ms) -> 122 (after 170ms)
        // op2: 200 -> 210 (after 250ms)
        // op3: 300 -> 310 (after 350ms)
        // Expected global average: (122 + 210 + 310) / 3 = 214.0
        assertEquals(214.0, monitor.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testClear_ResetsInterval() {
        monitor = new QueryPerformanceMonitor(60L, mockTimeProvider);

        // Record some operations
        monitor.recordExecutionTime("op1", 100.0);
        monitor.recordExecutionTime("op2", 200.0);
        assertEquals(150.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time
        mockTimeProvider.advanceTime(30);

        // Clear should reset everything including interval timer
        monitor.clear();
        assertEquals(0.0, monitor.getOverallAverageExecutionTime(), 0.001);
        assertEquals(0, monitor.getTrackedOperationCount());

        // Record operation immediately after clear - should update global average
        monitor.recordExecutionTime("op1", 300.0);
        assertEquals(300.0, monitor.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testZeroInterval_AlwaysUpdateBehavior() {
        monitor = new QueryPerformanceMonitor(0L, mockTimeProvider);

        // Record operation
        monitor.recordExecutionTime("op1", 100.0);
        assertEquals(100.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time significantly
        mockTimeProvider.advanceTime(1000);

        // Any update should trigger global average update
        monitor.recordExecutionTime("op1", 200.0);
        assertEquals(120.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Another update should also trigger global average update
        monitor.recordExecutionTime("op1", 300.0);
        assertEquals(156.0, monitor.getOverallAverageExecutionTime(), 0.001); // ((120*4 + 300)/5)
    }

    @Test
    void testInitialOperation_AlwaysUpdates() {
        monitor = new QueryPerformanceMonitor(60L, mockTimeProvider);

        // First operation should always update global average regardless of interval
        monitor.recordExecutionTime("op1", 100.0);
        assertEquals(100.0, monitor.getOverallAverageExecutionTime(), 0.001);
        assertEquals(1, monitor.getTrackedOperationCount());
    }
}