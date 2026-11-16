# Kiểm thử và demo

## Kết quả xác nhận ngày 08/11/2026

Build thành công cả ba project độc lập trên Java 21. Tổng cộng 56 test: 9 cấu hình, 10 Bully/cluster/discovery, 3 client/lưu kết quả, 10 session/lease, 5 PDF, 4 tích hợp nhiều JVM, 3 JavaFX và 12 kiểm tra kích thước cửa sổ. Kết quả là 0 failure, 0 error và 0 skipped khi chạy đủ các cờ. Ba JAR ứng dụng đều có Main-Class hợp lệ và hợp đồng Protobuf đồng nhất.

Các nhóm được chạy riêng theo cờ tương ứng; không phải toàn bộ 56 test chạy trong lệnh Maven mặc định. Có thể tái hiện bằng `scripts/build.ps1 -Integration -Ui`.

## Các kiểm thử tự động

| Nhóm | Số test | Nội dung |
|---|---:|---|
| ConfigTest trong mỗi ứng dụng | 9 | Cấu hình UTF-8, ghi đè từ dòng lệnh, bind/port và từ chối cấu hình node không hợp lệ |
| BullyElectionTest, ServerClusterTest, PeerDiscoveryTest | 10 | Vòng bầu cử, Answer/Coordinator, retry khi thiếu công bố, timeout, node ID cao phục hồi, membership động, ID trùng và cluster không liên quan |
| ClientNodeTest, ResultSaveTest | 3 | Reconnect giữ nguyên request, cancel/release không phụ thuộc Worker endpoint và ghi kết quả an toàn |
| WorkerStateTest | 10 | 20 yêu cầu đồng thời, token, lease, generation cũ, request gửi lại, timeout, job dài, release khi đang xử lý |
| PdfServiceTest | 5 | Merge, Split, Watermark, dữ liệu lỗi/quá dung lượng, tài nguyên kế thừa; PDF mẫu chứa chữ và hình ảnh |
| DistributedIntegrationTest | 4 | JVM riêng, server không nhận danh sách `servers` và tự discovery multicast: FIFO/PDF/failover/restart/khôi phục grant/timeout; ba request đồng thời; ba client thật reconnect và client giữ quyền chết; Coordinator chết với ba request chưa đọc phản hồi |
| Các ViewTest | 3 | Mở/render JavaFX, nút Stop Server, trạng thái Client vô hiệu hóa xử lý khi chưa có quyền |
| WindowSizingTest trong mỗi ứng dụng | 12 | DPI, taskbar, nhiều màn hình, tọa độ âm và giới hạn kích thước cửa sổ |

Integration và UI được bật riêng; mặc định Maven bỏ qua hai nhóm này. Test tích hợp dùng timeout ngắn hơn demo để không phải chờ 60 giây; WorkerStateTest kiểm tra ranh giới 60 giây bằng đồng hồ giả lập. Phiên đang xử lý vượt quá 60 giây cũng được kiểm tra bằng đồng hồ giả lập.

## Đối chiếu TC01–TC16

| ID | Bằng chứng tự động |
|---|---|
| TC01 | Cluster khởi động server theo thứ tự 2, 1, 3; đợi Coordinator 3 |
| TC02 | Kill Coordinator 3; đợi Worker chấp nhận Coordinator 2 |
| TC03 | Khởi động lại server 3; kiểm tra chuyển lại Coordinator 3 |
| TC04 | Ba ACCESS_REQUEST đồng thời: một grant, hai queued |
| TC05 | Hai client chờ trong FIFO khi client a giữ phiên |
| TC06 | Merge hoàn thành; sessionId vẫn giữ nguyên |
| TC07 | Release a cấp b; Release b trả AVAILABLE |
| TC08 | Unit test mốc 60 giây; tích hợp hết hạn với cấu hình 10 giây |
| TC09 | Sau failover giữ nguyên sessionId và vẫn Split qua gRPC được |
| TC10 | Gửi ba request, không đọc phản hồi, kill Coordinator và resend cùng ID |
| TC11 | Kill JVM client giữ quyền; sau timeout client đang chờ nhận quyền |
| TC12 | RPC token rỗng/sai bị PERMISSION_DENIED |
| TC13 | Control với Coordinator/generation cũ bị từ chối |
| TC14 | Merge trả PDF với đúng số trang và nội dung |
| TC15 | Split trả đúng khoảng trang và nội dung |
| TC16 | Watermark xuất hiện trên mọi trang, giữ nội dung gốc |

Các test này kiểm tra trên localhost Windows. Chưa thay thế kiểm tra firewall và kết nối trên nhiều máy LAN, hoặc thao tác thủ công toàn bộ FileChooser.

## Kịch bản demo

1. Build rồi chạy `scripts/start-demo.ps1`; đợi Server 3 là Coordinator.
2. Client 1 Request Access; Client 2 và Client 3 lần lượt Request Access. Kiểm tra Worker giữ Client 1, hàng đợi là Client 2 → Client 3.
3. Client 1 Merge `document-worker/samples/sample-1.pdf` và `document-worker/samples/sample-2.pdf`; lưu và mở kết quả 5 trang. Kiểm tra Worker trở về RESERVED.
4. Client 1 Split trang 1–2 hoặc Watermark; vẫn giữ cùng session.
5. Stop Server 3; đợi Server 2 là Coordinator. Client 1 vẫn xử lý được; Client 2/3 tự kết nối lại và gửi lại yêu cầu. Thứ tự queue sau lỗi có thể thay đổi.
6. Release Access tại Client 1; một client chờ được cấp quyền, chỉ client đó được bật nút Process.
7. Chạy lại Server 3 bằng cấu hình server-3; sau bầu chọn Server 3 nhận quyền điều phối, phiên client không bị hủy.
8. Đóng client đang giữ phiên mà không Release; chờ 60 giây mặc định, kiểm tra client chờ tiếp theo được cấp quyền.
9. Hủy một yêu cầu đang chờ và kiểm tra hàng đợi cập nhật.

Log có requestId/sessionId nhưng không in accessToken. Log demo trong `logs/`; log test nhiều JVM trong `document-worker/target/integration-*/`.
