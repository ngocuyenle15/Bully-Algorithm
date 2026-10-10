# Client Node — project độc lập

Java 21, Maven, JavaFX, TCP JSON và gRPC. Tự tìm Coordinator, xin/hủy/trả quyền, tự kết nối lại và gọi xử lý PDF. Không phụ thuộc mã nguồn hoặc artifact nội bộ của Server/Worker.

## Fat JAR ngay trong project

`pom.xml` của project này cấu hình Maven Shade Plugin ở phase `package`, đặt Main-Class, gộp provider gRPC và nhúng dependency runtime. Không cần project hoặc thư viện nội bộ khác.

```powershell
.\build-fat-jar.ps1
```

Script build, chạy các test mặc định và kiểm tra manifest/dependency trong JAR. Có thể bỏ qua chạy test bằng `.\build-fat-jar.ps1 -SkipTests`, hoặc dùng trực tiếp `mvn clean package`.

File chạy là `target/<artifactId>-1.0.0-SNAPSHOT.jar`; không dùng file `original-*.jar`. Copy JAR cùng cấu hình trong `config/` sang máy chạy có Java 21; cấu hình IP/port không bị nhúng vào JAR.

## Build và chạy

Từ thư mục `client-node`:

```powershell
mvn clean package
java -jar target/client-node-1.0.0-SNAPSHOT.jar --config config/client-1.properties
```

Chạy các client khác bằng `client-2.properties`/`client-3.properties`. Mỗi tiến trình cần client ID riêng. Sửa `servers` và `worker` thành IP LAN thực tế khi chạy nhiều máy.

Console tự xin quyền:

```powershell
java -jar target/client-node-1.0.0-SNAPSHOT.jar --headless --id client-4 --request true
```

Dùng JavaFX để chọn PDF, Merge/Split/Watermark và lưu kết quả. Worker và ít nhất một Server cần hoạt động để xin quyền.

## Cấu trúc

- `src/main/java/vn/bully/client`: ứng dụng, trạng thái client và giao diện.
- `src/main/java/vn/bully/client/support`: cấu hình, DTO TCP, networking, log và gRPC client nội bộ.
- `src/main/proto/document.proto`: hợp đồng gRPC; Maven tự sinh mã package `vn.bully.client.rpc`.
- `src/test`: kiểm thử cấu hình và JavaFX; kiểm tra giao diện bằng `mvn '-Dbully.ui=true' test`.
- `config`: cấu hình mẫu cho ba client.

Copy riêng project này sang thư mục khác vẫn build được bằng `mvn clean package`. Khi sửa RPC cần giữ hợp đồng tương thích với Server/Worker. Cấu hình IP/port nằm ngoài JAR; máy chạy cần Java 21. JavaFX native theo nền tảng build.
