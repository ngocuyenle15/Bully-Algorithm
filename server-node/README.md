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

Chạy thêm tiến trình bằng `server-2.properties` và `server-3.properties`. Ba server dùng cùng JAR, mỗi tiến trình có ID/port riêng và tự tìm nhau bằng UDP multicast. Khi chạy LAN, đặt `bind-address` thành IP card mạng của từng máy; giữ `cluster-id`, `discovery-group` và `discovery-port` giống nhau. `worker` vẫn được cấu hình thủ công. Document Worker cần hoạt động để ứng viên Coordinator nhận lease.

Thêm `--headless` để chạy console. Tham số dòng lệnh ghi đè file cấu hình. File cấu hình nằm ngoài JAR.

## Cấu trúc

- `src/main/java/vn/bully/server`: ứng dụng, điều phối và giao diện.
- `src/main/java/vn/bully/server/support`: cấu hình, DTO TCP, networking, log và gRPC client nội bộ.
- `src/main/proto/document.proto`: hợp đồng gRPC; Maven sinh mã vào `target/generated-sources`, package `vn.bully.server.rpc`.
- `src/test`: kiểm thử cấu hình và JavaFX. Bật kiểm tra giao diện bằng `mvn '-Dbully.ui=true' test`.
- `config`: cấu hình mẫu cho ba tiến trình server.

Project chỉ trao đổi với Client/Worker qua TCP/gRPC, không tham chiếu mã nguồn hoặc artifact Maven của ứng dụng khác. Khi thay đổi RPC, cập nhật hợp đồng tương ứng ở Client và Worker, giữ nguyên wire package, tên RPC và field number tương thích.

## Discovery giữa các server

Server phát `SERVER_HELLO` JSON qua UDP multicast. IP peer được lấy từ địa chỉ nguồn UDP, còn ID và TCP port nằm trong thông điệp. Sau thời gian warm-up, Bully sử dụng snapshot các peer đã discovery để gửi `ELECTION`; TCP Bully không thay đổi.

```properties
id=1
port=9001
bind-address=192.168.1.11
cluster-id=bully-demo
discovery-enabled=true
discovery-group=239.255.42.99
discovery-port=8999
discovery-interval-ms=1000
discovery-timeout-ms=5000
discovery-warmup-ms=2500
```

`servers` là tùy chọn seed/fallback khi multicast bị chặn. ID vẫn phải cấu hình cố định, dương và không trùng. Hai process cùng ID sẽ chuyển sang `ID_CONFLICT` và không tham gia bầu cử. Nếu bỏ `port`, mặc định là `9000 + id`. Khi chạy LAN nên đặt `bind-address` thành IP card mạng cụ thể; `advertise-address` chỉ cần khi địa chỉ quảng bá phải khác địa chỉ bind.

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

Khi chạy LAN, mở UDP `discovery-port`, các cổng TCP server và cổng gRPC Worker. Client vẫn giữ danh sách `servers` và địa chỉ `worker` như trước; chỉ membership server–server được auto-discovery.
