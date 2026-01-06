package org.openjproxy.grpc.server.action;

import io.grpc.stub.StreamObserver;

/**
 * Action interface for bidirectional streaming operations (e.g., createLob).
 * Used when the action needs to return a StreamObserver for client-to-server streaming.
 * 
 * @param <TRequest> The gRPC request type (streamed from client)
 * @param <TResponse> The gRPC response type (sent to client)
 */
@FunctionalInterface
public interface StreamingAction<TRequest, TResponse> {
    /**
     * Execute the action and return a StreamObserver for receiving client requests.
     * 
     * @param responseObserver The gRPC response observer for sending responses to client
     * @return StreamObserver for receiving streaming requests from client
     */
    StreamObserver<TRequest> execute(StreamObserver<TResponse> responseObserver);
}
