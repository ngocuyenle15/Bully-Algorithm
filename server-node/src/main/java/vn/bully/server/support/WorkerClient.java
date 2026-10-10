package vn.bully.server.support;

import io.grpc.*;
import vn.bully.server.rpc.*;
import java.util.concurrent.TimeUnit;
import java.net.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

public final class WorkerClient implements AutoCloseable {
    private final ManagedChannel channel;
    public WorkerClient(NodeAddress address) {
        this(address, null, 0);
    }
    public WorkerClient(NodeAddress address, InetAddress localAddress, int localPort) {
        var builder = NettyChannelBuilder.forAddress(address.host(), address.port()).usePlaintext()
            .maxInboundMessageSize(64 * 1024 * 1024);
        if (localAddress != null) builder.localSocketPicker(new NettyChannelBuilder.LocalSocketPicker() {
            @Override public SocketAddress createSocketAddress(SocketAddress remoteAddress, Attributes attributes) {
                return new InetSocketAddress(localAddress, localPort);
            }
        });
        channel = builder.build();
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
