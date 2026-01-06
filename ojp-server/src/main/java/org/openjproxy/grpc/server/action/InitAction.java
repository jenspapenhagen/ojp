package org.openjproxy.grpc.server.action;

/**
 * Action interface for initialization operations that don't take request/response parameters.
 * Used for setup actions like initializing the XA Pool Provider.
 * 
 * <p>Example: InitializeXAPoolProviderAction
 */
@FunctionalInterface
public interface InitAction {
    /**
     * Execute the initialization action.
     */
    void execute();
}
