# Document Worker — project độc lập

Java 21, Maven, gRPC, PDFBox và JavaFX. Quản lý lease Coordinator, session/token, timeout và ba tác vụ Merge/Split/Watermark. Không cần thư viện nội bộ từ Server/Client để build.

## Build và chạy

Từ thư mục `document-worker`:

```powershell
mvn clean package
java -jar target/document-worker-1.0.0-SNAPSHOT.jar --config config/worker.properties
```

Thêm `--headless` để chạy console. Cổng mặc định 9100, lease 5 giây, idle session timeout 60 giây. Worker có thể khởi động trước Server/Client.

Tạo lại PDF mẫu:

```powershell
java -cp target/document-worker-1.0.0-SNAPSHOT.jar vn.bully.worker.SamplePdfGenerator samples
```

## Cấu trúc và kiểm thử

- `src/main/java/vn/bully/worker`: gRPC service, trạng thái, PDF và giao diện.
- `src/main/java/vn/bully/worker/support`: cấu hình, địa chỉ, log và enum session nội bộ.
- `src/main/proto/document.proto`: hợp đồng gRPC; tự sinh mã package `vn.bully.worker.rpc`.
- `config`: cấu hình Worker.
- `samples`: PDF demo.
- `src/test`: kiểm thử cấu hình, session/lease, PDF và JavaFX.
- `src/test/java/vn/bully/worker/testsupport`: TCP/gRPC client chỉ phục vụ kiểm thử tích hợp, không đóng gói vào JAR.

`mvn clean package` chỉ cần project này. Kiểm thử tích hợp được bật riêng, cần ba JAR đã build và các thư mục Server/Client đặt cạnh Worker:

```powershell
mvn '-Dbully.integration=true' '-Dtest=DistributedIntegrationTest' test
```

Kiểm tra JavaFX bằng `mvn '-Dbully.ui=true' '-Dtest=*ViewTest' test`. Khi thay đổi RPC, cập nhật hợp đồng tương thích ở Server và Client. Fat JAR cần Java 21; JavaFX native theo nền tảng build.
