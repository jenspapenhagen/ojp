package org.openjproxy.grpc.server.action;

/**
 * Action interface for internal operations that return a value instead of using StreamObserver.
 * Used for helper actions that are called by other actions and need to return results directly.
 * 
 * <p>This is NOT for gRPC endpoints, but for internal action composition.
 * 
 * <p>Examples:
 * <ul>
 *   <li>ExecuteUpdateInternalAction - returns OpResult</li>
 *   <li>FindLobContextAction - returns LOB context information</li>
 *   <li>SessionConnectionAction - returns connection details</li>
 * </ul>
 * 
 * @param <TRequest> The request type
 * @param <TResult> The result type returned by the action
 */
@FunctionalInterface
public interface ValueAction<TRequest, TResult> {
    /**
     * Execute the action and return a result.
     * 
     * @param request The request object
     * @return The result of the action
     * @throws Exception if the action fails
     */
    TResult execute(TRequest request) throws Exception;
}
