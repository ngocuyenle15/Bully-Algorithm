# Giao thức và trạng thái

## UDP multicast discovery giữa server

Các server cùng `cluster-id` tham gia `discovery-group:discovery-port` và phát định kỳ thông điệp nhỏ:

```json
{"version":1,"clusterId":"bully-demo","type":"SERVER_HELLO","nodeId":3,"tcpPort":9003,"bootId":"uuid"}
```

IP peer được lấy từ địa chỉ nguồn UDP, không lấy từ payload. `bootId` chỉ đại diện lần chạy hiện tại; ID Bully vẫn được cấu hình cố định. Server chờ `discovery-warmup-ms` trước lần bầu cử đầu, loại peer động sau `discovery-timeout-ms`, và từ chối tham gia bầu cử khi phát hiện một process khác dùng cùng ID. `servers` là seed tĩnh tùy chọn và không hết hạn. Discovery chỉ thay thế membership server–server; các giao thức client/Worker bên dưới giữ nguyên.

## TCP

JSON UTF-8, một thông điệp mỗi dòng, tối đa 64 KB. Mỗi kết nối hiện thực hiện một request/response rồi đóng; Client poll để nhận quyền và cập nhật vị trí hàng đợi. Không cần kết nối TCP lâu dài.

```json
{"type":"ACCESS_REQUEST","requestId":"uuid","senderId":"client-1","payload":{"clientId":"client-1","username":"Client 1"}}
```

Server–server: ELECTION, ELECTION_OK, COORDINATOR, HEARTBEAT, HEARTBEAT_ACK, GET_COORDINATOR, COORDINATOR_INFO.

Client–server: Client gửi lặp lại ACCESS_REQUEST với cùng requestId để poll hoặc khôi phục yêu cầu sau failover. Server vẫn chấp nhận ACCESS_STATUS như một truy vấn tương thích. Server trả ACCESS_GRANTED (sessionId/accessToken/remainingMs), ACCESS_QUEUED (position), COORDINATOR_INFO hoặc ELECTION_IN_PROGRESS. ACCESS_RELEASE cần clientId/sessionId/accessToken. ACCESS_CANCEL cần requestId/clientId. ACCESS_STATUS trong phản hồi release/cancel có accepted/error.

Client giữ requestId đến khi hủy, trả quyền hoặc phiên hết hạn. Nếu hàng đợi mất sau failover, client gửi lại cùng ID; thứ tự FIFO mới là thứ tự tiếp nhận lại. Nếu Worker đã tạo phiên trước lúc mất phản hồi, Coordinator mới khôi phục token bằng RESERVE với cùng clientId/requestId.

## gRPC

Định nghĩa tại `src/main/proto/document.proto` trong từng project. Worker giới hạn thông điệp 64 MB; PDF tối đa 10 MB/file, 40 MB tổng đầu vào, 60 MB đầu ra. Merge cần ít nhất hai file; Split dùng khoảng trang từ 1 và bao gồm cả hai đầu; Watermark dài 1–200 ký tự. Trên Windows dùng Arial để hỗ trợ Unicode; nền tảng khác dùng Helvetica và chỉ hỗ trợ bộ ký tự của font đó.

Control actions:

| Action | Ý nghĩa |
|---|---|
| CLAIM | Xin lease khi lease hiện tại hết hạn (kể cả cùng ID: process trùng ID không cướp được lease còn hạn); tăng generation |
| RENEW | Gia hạn lease có ID/generation hợp lệ |
| DROP | Nhường lease khi phát hiện node cao hơn; giữ nguyên phiên client |
| STATUS | Kiểm tra quyền điều phối và lấy trạng thái |
| RESERVE | Tạo phiên hoặc lấy lại phiên có cùng clientId/requestId |
| RELEASE | Trả phiên đúng client/session/token; chờ job hoàn thành nếu đang xử lý |

Ngoài CLAIM, mọi Control yêu cầu coordinatorId và generation khớp cùng lease chưa hết hạn. GetWorkerStatus công khai không trả token. Worker không xác thực tài khoản Coordinator vì phạm vi là LAN tin cậy; generation là cơ chế fencing, không phải cơ chế chống giả mạo.

## Quy tắc an toàn

- Worker đồng bộ mọi quyết định tạo phiên, lease, release và bắt đầu job.
- Tối đa một session ACTIVE và một job PROCESSING.
- Kết thúc job trả Worker về RESERVED; không tự giải phóng phiên.
- Session timeout dựa trên đồng hồ đơn điệu; trong PROCESSING không tính idle, sau khi xong bắt đầu lại thời hạn idle.
- Lease Coordinator và session client độc lập. Generation mới không hủy token client hiện có.
- Node cao hơn quay lại khiến Coordinator hiện tại nhường lease sau khi nhận ELECTION_OK; ứng viên mới chỉ công bố sau khi Worker chấp nhận.
- Coordinator nhận ELECTION từ node thấp hơn thì trả ELECTION_OK và gửi lại COORDINATOR. Follower nhận ELECTION_OK không xóa Coordinator đang biết; follower chỉ nhận Coordinator mới (qua HEARTBEAT/GET_COORDINATOR) khi Worker xác nhận lease của ID đó còn hiệu lực.
- Node chỉ trả ELECTION_OK khi đủ điều kiện (`eligible`): đã hết warm-up, không trùng ID và đã liên lạc được Worker trong `heartbeat-ms × heartbeat-failures + 3 s`. Ngược lại trả ELECTION_IN_PROGRESS và không tự ứng cử (trạng thái `WORKER_UNREACHABLE`). `COORDINATOR_INFO`/`ELECTION_IN_PROGRESS` kèm cờ `eligible`; Coordinator chỉ nhường cho node cao hơn đủ điều kiện.
- Client hỏi song song mọi server và chọn `COORDINATOR_INFO` có `generation` lớn nhất.
- Coordinator gặp lỗi RENEW tạm thời (timeout gRPC) vẫn giữ vai trò tới hạn lease cục bộ (đo từ lúc gửi RENEW nên không muộn hơn Worker) rồi mới hạ cấp; mọi thao tác cấp quyền vẫn bị Worker fencing.
- Entry trong hàng đợi bị xóa nếu client không poll lại trong `queue-timeout-ms` (mặc định 3000; client poll mỗi 700 ms). Phiên do Coordinator RESERVE cho client đầu hàng mà client không nhận `ACCESS_GRANTED` trong khoảng này sẽ bị thu hồi (chỉ khi chưa xử lý job).
- Stop Server không DROP lease, để mô phỏng crash và chờ lease hết hạn.
- Worker crash mất toàn bộ trạng thái RAM, nằm ngoài phạm vi phục hồi.
