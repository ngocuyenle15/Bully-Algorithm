# Server Node — project độc lập

Java 21, Maven, JavaFX, TCP JSON và gRPC. Chứa Bully, heartbeat, Coordinator và hàng đợi FIFO. Tất cả mã nguồn, định nghĩa giao thức và dependency cần thiết nằm trong project này; không cần build/install project khác.

## Fat JAR ngay trong project

`pom.xml` của project này cấu hình Maven Shade Plugin ở phase `package`, đặt Main-Class, gộp provider gRPC và nhúng dependency runtime. Không cần project hoặc thư viện nội bộ khác.

```powershell
.\build-fat-jar.ps1
```

Script build, chạy các test mặc định và kiểm tra manifest/dependency trong JAR. Có thể bỏ qua chạy test bằng `.\build-fat-jar.ps1 -SkipTests`, hoặc dùng trực tiếp `mvn clean package`.

File chạy là `target/<artifactId>-1.0.0-SNAPSHOT.jar`; không dùng file `original-*.jar`. Copy JAR cùng cấu hình trong `config/` sang máy chạy có Java 21; cấu hình IP/port không bị nhúng vào JAR.

## Build và chạy

Từ thư mục `server-node`:

```powershell
mvn clean package
java -jar target/server-node-1.0.0-SNAPSHOT.jar --config config/server-1.properties
```

Chạy thêm tiến trình bằng `server-2.properties` và `server-3.properties`. Ba server dùng cùng JAR, mỗi tiến trình có ID/port riêng. Sửa danh sách `servers` và địa chỉ `worker` trong cấu hình khi chạy LAN. Document Worker cần hoạt động để ứng viên Coordinator nhận lease.

Thêm `--headless` để chạy console. Tham số dòng lệnh ghi đè file cấu hình. File cấu hình nằm ngoài JAR.

## Cấu trúc

- `src/main/java/vn/bully/server`: ứng dụng, điều phối và giao diện.
- `src/main/java/vn/bully/server/support`: cấu hình, DTO TCP, networking, log và gRPC client nội bộ.
- `src/main/proto/document.proto`: hợp đồng gRPC; Maven sinh mã vào `target/generated-sources`, package `vn.bully.server.rpc`.
- `src/test`: kiểm thử cấu hình và JavaFX. Bật kiểm tra giao diện bằng `mvn '-Dbully.ui=true' test`.
- `config`: cấu hình mẫu cho ba tiến trình server.

Project chỉ trao đổi với Client/Worker qua TCP/gRPC, không tham chiếu mã nguồn hoặc artifact Maven của ứng dụng khác. Khi thay đổi RPC, cập nhật hợp đồng tương ứng ở Client và Worker, giữ nguyên wire package, tên RPC và field number tương thích.

Fat JAR cần Java 21 trên máy chạy. JavaFX native theo nền tảng build; build lại nếu đổi hệ điều hành/kiến trúc.

## Cấu hình interface mạng và cổng

Sửa file trong thư mục config của từng project hoặc truyền tham số khi chạy JAR. Tham số dòng lệnh ghi đè file cấu hình.

| Project | Interface/IP cục bộ | Cổng |
| --- | --- | --- |
| server-node | bind-address | port: TCP lắng nghe, mặc định theo node 9001/9002/9003 |
| document-worker | bind-address | port: gRPC lắng nghe, mặc định 9100 |
| client-node | bind-address: IP nguồn cho TCP và gRPC | local-port: TCP nguồn; grpc-local-port: gRPC nguồn; mặc định cả hai là 0 |

bind-address là địa chỉ IP được gán cho card mạng trên máy chạy process, ví dụ 192.168.1.11. Có thể xem bằng ipconfig. Giá trị 0.0.0.0 cho phép Server/Worker lắng nghe trên mọi interface; với kết nối đi, hệ điều hành chọn interface phù hợp. Địa chỉ không thuộc máy cục bộ bị từ chối khi khởi động.

Client truy vấn Server/Worker nên không có socket lắng nghe. Cổng nguồn 0 để hệ điều hành cấp tự động là cấu hình khuyến nghị; cổng TCP nguồn cố định có thể gặp xung đột hoặc TIME_WAIT khi kết nối lại liên tục.

Ví dụ ghi đè khi chạy JAR:

```powershell
java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --config server-node/config/server-1.properties --bind-address 192.168.1.11 --port 9001
java -jar document-worker/target/document-worker-1.0.0-SNAPSHOT.jar --config document-worker/config/worker.properties --bind-address 192.168.1.10 --port 9100
java -jar client-node/target/client-node-1.0.0-SNAPSHOT.jar --config client-node/config/client-1.properties --bind-address 192.168.1.20 --local-port 0 --grpc-local-port 0
```

Khi chạy LAN, cập nhật servers và worker thành IP/cổng đích thực tế mà các máy khác truy cập được. Không dùng 0.0.0.0 làm địa chỉ đích. Cổng của mỗi node trong servers phải khớp port lắng nghe của node đó.