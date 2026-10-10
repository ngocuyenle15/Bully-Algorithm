# Build fat JAR cho từng project

`server-node`, `client-node`, `document-worker` sử dụng Maven Shade Plugin ở phase `package`. Mỗi JAR ứng dụng chứa mã nguồn của chính project và các dependency runtime, có Main-Class riêng và chạy bằng `java -jar`. Cấu hình IP/port vẫn nằm ngoài JAR.

Mỗi project tự quản lý thư viện bên thứ ba và tự sinh mã gRPC từ Protobuf nội bộ. Không có dependency giữa các project và không cần Maven install một project khác trước khi build.

## Build bằng script

Mỗi project có script riêng ngay cạnh `pom.xml`. Mở terminal tại project cần build:

```powershell
.\build-fat-jar.ps1
.\build-fat-jar.ps1 -SkipTests
```

Script này hoạt động với riêng thư mục project, không gọi script hay POM từ project khác. Sau khi build, script kiểm tra Main-Class, các lớp dependency và provider gRPC trong fat JAR.

Các lệnh dưới đây là tiện ích khi đứng tại thư mục gốc chứa cả ba project:

```powershell
# Build cả ba fat JAR; chạy kiểm thử mặc định.
./scripts/build-fat-jars.ps1

# Build riêng một ứng dụng; không cần build các project khác.
./scripts/build-fat-jars.ps1 -Project server-node
./scripts/build-fat-jars.ps1 -Project client-node
./scripts/build-fat-jars.ps1 -Project document-worker

# Bỏ qua chạy test khi cần đóng gói nhanh.
./scripts/build-fat-jars.ps1 -Project client-node -SkipTests
```

## Build bằng Maven

Chạy từ thư mục gốc; thứ tự build tùy ý:

```powershell
mvn -f server-node/pom.xml clean package
mvn -f client-node/pom.xml clean package
mvn -f document-worker/pom.xml clean package
```

Có thể copy riêng thư mục project sang máy khác, mở terminal trong đó và chạy `mvn clean package`. Mỗi project có README và thư mục config riêng.

## File đầu ra

| Project | JAR | Main-Class |
|---|---|---|
| server-node | `server-node/target/server-node-1.0.0-SNAPSHOT.jar` | `vn.bully.server.ServerApplication` |
| client-node | `client-node/target/client-node-1.0.0-SNAPSHOT.jar` | `vn.bully.client.ClientApplication` |
| document-worker | `document-worker/target/document-worker-1.0.0-SNAPSHOT.jar` | `vn.bully.worker.WorkerApplication` |

File `original-*.jar` là JAR trước khi Shade nhúng dependency; dùng file trong bảng để chạy và phân phối. Tên đầu ra lấy từ artifactId/version trong POM.

## Chạy trên máy khác

Cần Java 21, JAR ứng dụng và file cấu hình tương ứng; không cần Maven trên máy chạy.

```powershell
java -jar server-node-1.0.0-SNAPSHOT.jar --config server-1.properties
java -jar client-node-1.0.0-SNAPSHOT.jar --config client-1.properties
java -jar document-worker-1.0.0-SNAPSHOT.jar --config worker.properties
```

Copy các file cấu hình từ `config/` của project tương ứng, sửa IP LAN rồi khởi động ứng dụng. JavaFX có thư viện native theo nền tảng build: JAR build trên Windows dùng cho Windows cùng kiến trúc; build lại trên nền tảng khác. Fat JAR chứa dependency, không chứa JRE.

## Các thiết lập Shade quan trọng

- ManifestResourceTransformer đặt Main-Class từ `app.mainClass`.
- ServicesResourceTransformer gộp META-INF/services để giữ các provider gRPC.
- Không minimize để giữ các lớp được nạp qua reflection/provider.
- Loại chữ ký của JAR dependency vì chữ ký không còn hợp lệ sau khi gộp.
- Loại module-info vì các ứng dụng chạy theo classpath.
- Fat JAR thay artifact chính; giữ nguyên tên JAR cho các script demo và kiểm thử tích hợp.

Tham khảo cấu hình chính thức: [Executable JAR](https://maven.apache.org/plugins/maven-shade-plugin/examples/executable-jar.html), [Resource transformers](https://maven.apache.org/plugins/maven-shade-plugin/examples/resource-transformers.html).
