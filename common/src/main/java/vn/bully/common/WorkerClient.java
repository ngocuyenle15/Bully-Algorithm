package vn.bully.common;

import io.grpc.*;
import vn.bully.common.rpc.*;
import java.util.concurrent.TimeUnit;

public final class WorkerClient implements AutoCloseable {
    private final ManagedChannel channel;
    public WorkerClient(NodeAddress address) {
        channel = ManagedChannelBuilder.forAddress(address.host(), address.port()).usePlaintext()
            .maxInboundMessageSize(64 * 1024 * 1024).build();
    }
    public DocumentWorkerGrpc.DocumentWorkerBlockingStub stub() {
        return DocumentWorkerGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS);
    }
    public DocumentWorkerGrpc.DocumentWorkerBlockingStub processingStub() {
        return DocumentWorkerGrpc.newBlockingStub(channel).withDeadlineAfter(120, TimeUnit.SECONDS);
    }
    public WorkerReply status() { return stub().getWorkerStatus(StatusRequest.getDefaultInstance()); }
    public WorkerReply control(ControlRequest request) { return stub().control(request); }
    @Override public void close() { channel.shutdownNow(); }
}
