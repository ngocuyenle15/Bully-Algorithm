# Kiểm thử và demo

## Kết quả xác nhận ngày 23/11/2026

Bảng dưới liệt kê đúng các lớp test đang có trong repo (bản trước ghi 56 test và các lớp `BullyElectionTest`, `ServerClusterTest`, `PeerDiscoveryTest`, `ResultSaveTest`, `WindowSizingTest` không tồn tại).

| Lệnh | Kết quả |
|---|---|
| `powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 -Integration` | 36 test không cần màn hình đạt, 0 failure, 0 error; 3 `*ViewTest` bỏ qua (cần `-Ui`) |
| `powershell -ExecutionPolicy Bypass -File .\scripts\test-run-scripts.ps1` | 45 case cho `run\*.cmd` (kể cả mutation), 0 failed/survived |
| `bash scripts/mutation-java.sh` | Mutation các guard Java; xem bảng trong báo cáo review |

## Các kiểm thử tự động

| Bộ test | Số test | Nội dung |
|---|---:|---|
| `ConfigTest` trong mỗi ứng dụng | 3 × 3 | Cấu hình UTF-8, ghi đè từ dòng lệnh, bind/port và từ chối cấu hình node không hợp lệ |
| `ClientNodeTest` | 2 | Client theo `generation` lớn nhất chứ không theo server trả lời đầu tiên; reply hỏng không làm client ngừng hoạt động |
| `WorkerStateTest` | 11 | 20 yêu cầu đồng thời, token, lease, generation cũ, ID trùng không cướp lease còn hạn, request gửi lại, timeout, job dài, release khi đang xử lý |
| `PdfServiceTest` | 5 | Merge, Split, Watermark, dữ liệu lỗi/quá dung lượng |
| `DistributedIntegrationTest` | 9 | JVM riêng, discovery multicast: FIFO/PDF/failover/restart/timeout; request đồng thời; client thật reconnect; Coordinator chết với request chưa đọc phản hồi; node thấp restart; node cao mất Worker; client chờ bị ngắt; node đang warm-up không trả ELECTION_OK; 10 peer không phản hồi không làm mất lease |
| `*ViewTest` | 3 | Mở/render JavaFX (chỉ chạy với `-Ui`) |

Tổng cộng 39 test Java. Integration và UI được bật riêng; mặc định Maven bỏ qua hai nhóm này.

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

1. Build rồi chạy `scripts/start-demo.ps1` (chạy cả 11 tiến trình trên một máy, ghi đè sang `127.0.0.1`, port 9001–9005); đợi Server 5 là Coordinator.
2. Client 1 Request Access; Client 2 và Client 3 lần lượt Request Access. Kiểm tra Worker giữ Client 1, hàng đợi là Client 2 → Client 3.
3. Client 1 Merge `document-worker/samples/sample-1.pdf` và `document-worker/samples/sample-2.pdf`; lưu và mở kết quả 5 trang. Kiểm tra Worker trở về RESERVED.
4. Client 1 Split trang 1–2 hoặc Watermark; vẫn giữ cùng session.
5. Stop Server 5; đợi Server 4 là Coordinator. Client 1 vẫn xử lý được; Client 2/3 tự kết nối lại và gửi lại yêu cầu. Thứ tự queue sau lỗi có thể thay đổi.
6. Release Access tại Client 1; một client chờ được cấp quyền, chỉ client đó được bật nút Process.
7. Chạy lại Server 5 (cùng tham số ghi đè như `start-demo.ps1`); sau bầu chọn Server 5 nhận quyền điều phối, phiên client không bị hủy.
8. Đóng client đang giữ phiên mà không Release; chờ 60 giây mặc định, kiểm tra client chờ tiếp theo được cấp quyền.
9. Hủy một yêu cầu đang chờ và kiểm tra hàng đợi cập nhật.

Log có requestId/sessionId nhưng không in accessToken. Log demo trong `logs/`; log test nhiều JVM trong `document-worker/target/integration-*/`.
