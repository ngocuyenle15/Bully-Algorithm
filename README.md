# Hệ thống xử lý tài liệu phân tán

Ba project Maven độc lập, Java 21: `server-node`, `client-node`, `document-worker`. Mỗi project chứa mã nguồn hỗ trợ, Protobuf, cấu hình và POM riêng. Có thể copy một project sang thư mục/máy khác rồi build trực tiếp bằng `mvn clean package`; không phụ thuộc project hoặc artifact nội bộ khác.

## Chức năng đã triển khai

- Ba Server Node: TCP JSON theo dòng, Bully Algorithm, heartbeat, phát hiện lỗi và bầu lại Coordinator.
- Coordinator: hàng đợi FIFO, chống yêu cầu trùng, xin/hủy/trả quyền, cấp session qua Worker.
- Worker: gRPC, lease và generation chặn Coordinator cũ, session/token, timeout 60 giây mặc định, xử lý một job tại một thời điểm.
- PDF: Merge, Split theo khoảng trang, Watermark; trả bytes kết quả cho client.
- Client: tìm Coordinator, tự kết nối lại, gửi lại requestId cũ, giữ phiên trong thời gian failover.
- JavaFX: cửa sổ Server, Client, Worker; trạng thái và event log. Có chế độ console/headless để kiểm thử.
- Kiểm thử logic, PDF, nhiều JVM và render giao diện JavaFX.

## Build

Đóng gói fat JAR riêng bằng `./scripts/build-fat-jars.ps1 -Project server-node` (hoặc `client-node`, `document-worker`). Bỏ `-Project` để build cả ba. Chi tiết tại `docs/FAT_JAR.md`.

Cần JDK 21, Maven và mạng để tải dependency lần đầu. Từ thư mục gốc:

```powershell
./scripts/build.ps1
```

Hoặc build từng project theo thứ tự bất kỳ:

```powershell
mvn -f server-node/pom.xml package
mvn -f client-node/pom.xml package
mvn -f document-worker/pom.xml package
```

JAR JavaFX được đóng gói cho nền tảng build hiện tại; build lại trên nền tảng khác.

## Chạy demo 7 tiến trình

```powershell
./scripts/start-demo.ps1
```

Script mở 1 Worker, 3 Server và 3 Client bằng JavaFX, ghi log vào `logs/`. Đợi Server 3 trở thành Coordinator, rồi nhấn Request Access tại các client. Mỗi client cần ID riêng.

Chạy từng tiến trình riêng:

```powershell
java -jar document-worker/target/document-worker-1.0.0-SNAPSHOT.jar --config document-worker/config/worker.properties
java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --config server-node/config/server-1.properties
java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --config server-node/config/server-2.properties
java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --config server-node/config/server-3.properties
java -jar client-node/target/client-node-1.0.0-SNAPSHOT.jar --config client-node/config/client-1.properties
```

Lặp lệnh client với client-2/client-3. Đóng cửa sổ để dừng ứng dụng. Nút Stop Server mô phỏng crash: không chủ động trả lease Coordinator.

Console/headless:

```powershell
java -jar document-worker/target/document-worker-1.0.0-SNAPSHOT.jar --headless
java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --headless --id 3
java -jar client-node/target/client-node-1.0.0-SNAPSHOT.jar --headless --id client-4 --request true
```

Client headless có thể tự xin quyền để mô phỏng, không có menu thao tác PDF; dùng JavaFX để chọn file và lưu kết quả. Dừng console bằng Ctrl+C.

## PDF mẫu

Hai file demo được đặt trong `document-worker/samples/`. Có thể tạo lại bằng:

```powershell
java -cp document-worker/target/document-worker-1.0.0-SNAPSHOT.jar vn.bully.worker.SamplePdfGenerator document-worker/samples
```

Merge chọn hai file theo thứ tự hiển thị trong danh sách. Split chọn đúng một file, nhập khoảng trang từ 1. Watermark chọn đúng một file và nhập nội dung. Process cho phép chọn đường dẫn lưu trước khi gửi job; hoàn thành job vẫn giữ phiên đến khi Release hoặc timeout.

## Kiểm thử

Logic session/lease và PDF chạy trong build thông thường. Tích hợp dùng các JVM riêng và tự dừng các tiến trình do test tạo; không dùng port mặc định của demo:

```powershell
./scripts/build.ps1 -Integration
```

Hoặc sau khi đã build ba JAR:

```powershell
mvn -f document-worker/pom.xml '-Dbully.integration=true' '-Dtest=DistributedIntegrationTest' test
```

Kiểm thử mở/render JavaFX (cần môi trường desktop, có cửa sổ xuất hiện ngắn):

```powershell
mvn -f server-node/pom.xml '-Dbully.ui=true' '-Dtest=*ViewTest' test
mvn -f client-node/pom.xml '-Dbully.ui=true' '-Dtest=*ViewTest' test
mvn -f document-worker/pom.xml '-Dbully.ui=true' '-Dtest=*ViewTest' test
```

Báo cáo tại `target/surefire-reports/` của từng project. Ảnh kiểm tra giao diện tại `target/ui-preview.png`. Log kiểm thử tích hợp tại `document-worker/target/integration-*/`.

Có thể chạy đầy đủ bằng `./scripts/build.ps1 -Integration -Ui`.

## Chạy LAN và cấu hình

Sửa `servers` và `worker` trong thư mục `config/` của từng project thành IP LAN thực tế, giữ danh sách server nhất quán. `port` là cổng listen cục bộ; mở firewall cho TCP 9001–9003 và 9100. Các tham số dòng lệnh ghi đè file cấu hình.

| Tham số | Mặc định |
|---|---:|
| heartbeat-ms | 1000 |
| heartbeat-timeout-ms | 1000 |
| heartbeat-failures | 3 |
| election-ms | 2000 |
| lease-ms tại Worker | 5000 |
| session-ms tại Worker | 60000 |

Không tăng heartbeat/election timeout tùy ý mà giữ lease quá ngắn. Khi failover, Coordinator mới có thể phải chờ lease cũ hết hạn trước khi được Worker chấp nhận. Queue position được cập nhật khi client poll; khi Coordinator chết hàng đợi được tạo lại và không bảo toàn thứ tự cũ.

## Tài liệu

- `docs/PROTOCOL.md`: giao thức, trạng thái, fencing và giới hạn file. Mỗi project có `src/main/proto/document.proto` riêng; giữ nội dung hợp đồng đồng nhất khi sửa RPC, kiểm tra bằng `scripts/check-protocol.ps1`.
- `docs/TESTING.md`: tình huống kiểm thử và kịch bản demo.

Phạm vi LAN tin cậy, không có xác thực tài khoản hay TLS. Worker crash mất trạng thái RAM; không phục hồi Worker, không đồng bộ hàng đợi, không database. Kiểm thử tự động thực hiện trên một máy Windows; triển khai nhiều máy LAN cần kiểm tra cấu hình mạng/firewall thực tế.
