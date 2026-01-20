package org.openjproxy.xa.pool.commons.housekeeping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Factory for creating thread executors for housekeeping tasks.
 * <p>
 * OJP Server requires Java 21+ and uses virtual threads for efficient resource management.
 * This factory uses reflection to access virtual threads while maintaining compatibility
 * with Java 11 compilation target. Falls back to daemon threads for testing on older Java versions.
 * </p>
 * 
 * <h3>Benefits of Virtual Threads (Java 21+):</h3>
 * <ul>
 *   <li><strong>Reduced Memory Footprint:</strong> Virtual threads use ~KB of stack vs ~2MB for platform threads</li>
 *   <li><strong>Better Scalability:</strong> Can create millions of virtual threads vs thousands of platform threads</li>
 *   <li><strong>Lower Overhead:</strong> Cheaper to create and manage, ideal for periodic housekeeping tasks</li>
 *   <li><strong>JVM-Managed:</strong> Automatically scheduled on carrier threads by the JVM</li>
 * </ul>
 * 
 * @see Thread.Builder.OfVirtual
 * @see Executors#newThreadPerTaskExecutor
 */
public class ThreadFactory {
    private static final Logger log = LoggerFactory.getLogger(ThreadFactory.class);
    private static final boolean VIRTUAL_THREADS_AVAILABLE;
    
    static {
        VIRTUAL_THREADS_AVAILABLE = checkVirtualThreadSupport();
        if (VIRTUAL_THREADS_AVAILABLE) {
            log.info("Using virtual threads for XA pool housekeeping tasks (Java 21+)");
        } else {
            log.warn("Virtual threads not available - using platform daemon threads. OJP Server requires Java 21+ for production use.");
        }
    }
    
    private static boolean checkVirtualThreadSupport() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
    
    /**
     * Creates a single-threaded scheduled executor service.
     * <p>
     * On Java 21+ (production): Uses virtual threads for optimal performance.
     * On Java 11-20 (testing): Falls back to platform daemon threads.
     * </p>
     *
     * @param threadName the name prefix for threads
     * @return a new ScheduledExecutorService
     */
    public static ScheduledExecutorService createHousekeepingExecutor(String threadName) {
        if (VIRTUAL_THREADS_AVAILABLE) {
            return createVirtualThreadExecutor(threadName);
        } else {
            return createPlatformThreadExecutor(threadName);
        }
    }
    
    private static ScheduledExecutorService createVirtualThreadExecutor(String threadName) {
        try {
            // Use reflection to call Thread.ofVirtual().name(...).factory()
            Method ofVirtualMethod = Thread.class.getMethod("ofVirtual");
            Object builder = ofVirtualMethod.invoke(null);
            
            Class<?> builderClass = Class.forName("java.lang.Thread$Builder$OfVirtual");
            Method nameMethod = builderClass.getMethod("name", String.class, long.class);
            builder = nameMethod.invoke(builder, threadName + "-", 0L);
            
            Method factoryMethod = builderClass.getMethod("factory");
            java.util.concurrent.ThreadFactory virtualThreadFactory = 
                (java.util.concurrent.ThreadFactory) factoryMethod.invoke(builder);
            
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(virtualThreadFactory);
            log.debug("Created virtual thread-based scheduled executor: {}", threadName);
            return executor;
            
        } catch (Exception e) {
            log.warn("Failed to create virtual thread executor, falling back to platform threads", e);
            return createPlatformThreadExecutor(threadName);
        }
    }
    
    private static ScheduledExecutorService createPlatformThreadExecutor(String threadName) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        log.debug("Created platform daemon thread-based scheduled executor: {}", threadName);
        return executor;
    }
}
