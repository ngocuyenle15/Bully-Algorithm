package vn.bully.worker;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import vn.bully.worker.rpc.*;
import vn.bully.worker.support.EventLog;

public final class DocumentServiceImpl extends DocumentWorkerGrpc.DocumentWorkerImplBase {
    private final WorkerState state;
    private final PdfService pdf = new PdfService();
    private final EventLog log;
    public DocumentServiceImpl(WorkerState state, EventLog log) { this.state = state; this.log = log; }
    @Override public void control(ControlRequest r, StreamObserver<WorkerReply> out) {
        out.onNext(state.control(r)); out.onCompleted();
    }
    @Override public void getWorkerStatus(StatusRequest r, StreamObserver<WorkerReply> out) {
        out.onNext(state.status()); out.onCompleted();
    }
    @Override public void mergePdf(PdfRequest r, StreamObserver<PdfReply> out) { process("Merge", r, out); }
    @Override public void splitPdf(PdfRequest r, StreamObserver<PdfReply> out) { process("Split", r, out); }
    @Override public void addWatermark(PdfRequest r, StreamObserver<PdfReply> out) { process("Watermark", r, out); }
    private void process(String name, PdfRequest r, StreamObserver<PdfReply> out) {
        boolean started = false;
        try {
            state.begin(r, name); started = true;
            byte[] result = pdf.process(name, r);
            state.finish(); started = false;
            out.onNext(PdfReply.newBuilder().setContent(ByteString.copyFrom(result)).setFilename(name.toLowerCase() + ".pdf").build());
            out.onCompleted();
        } catch (StatusRuntimeException e) { out.onError(e); }
        catch (Exception e) {
            log.info("PDF failed: " + e.getMessage());
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } finally { if (started) state.finish(); }
    }
}
