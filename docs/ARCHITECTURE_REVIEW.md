# Rà soát kiến trúc Bully và loại trừ tương hỗ

## 1. Kết luận

Project hiện thực đúng ý chính của thuật toán Bully với ID server cố định và địa chỉ peer được phát hiện động:

- Mỗi server có một ID số nguyên dương, duy nhất; ID lớn hơn có độ ưu tiên cao hơn.
- Server nghi ngờ Coordinator chết sẽ gửi `ELECTION` cho các server có ID lớn hơn.
- Server còn sống trả `ELECTION_OK` (thông điệp `Answer` trong đề) và tự mở cuộc bầu cử của nó.
- Ứng viên không nhận được `ELECTION_OK` sẽ xin quyền điều phối, sau đó gửi `COORDINATOR` cho các server khác.
- Server có ID lớn hơn khi hoạt động lại sẽ khởi động bầu cử và giành lại vai trò Coordinator.

Bully chỉ chọn Coordinator; nó không tự tạo ra khóa loại trừ tương hỗ. Trong project này, `document-worker` mới là nơi giữ trạng thái khóa/session duy nhất. Coordinator quản lý hàng đợi FIFO và chỉ Coordinator có lease hợp lệ mới được yêu cầu Worker tạo hoặc trả session. Cách phân vai này giữ được tối đa một client có quyền ngay cả khi Coordinator bị chết giữa lúc trả lời.

## 2. Các thành phần

| Thành phần | Trách nhiệm chính | Giao thức |
|---|---|---|
| `server-node` | Multicast discovery, Bully, heartbeat, Coordinator, hàng đợi FIFO | UDP giữa server; TCP JSON giữa server/client; gRPC tới Worker |
| `client-node` | Tìm Coordinator, xin/hủy/trả quyền, giữ `requestId`, gửi tác vụ PDF | TCP JSON tới server; gRPC tới Worker |
| `document-worker` | Điểm loại trừ tương hỗ, lease Coordinator, session/token client, PDF | gRPC |

Luồng quyền truy cập là:

```text
Client --ACCESS_REQUEST--> Coordinator --RESERVE--> Worker
Client <--ACCESS_GRANTED-- Coordinator <--session/token-- Worker
Client -------- PDF + session/token -------------> Worker
Client --ACCESS_RELEASE--> Coordinator --RELEASE--> Worker
```

## 3. Các server tìm nhau như thế nào

Các server tự tìm nhau trong cùng LAN bằng UDP multicast. Mỗi node chỉ cấu hình ID, TCP port và cùng bộ `cluster-id`/multicast:

```properties
id=1
port=9001
bind-address=192.168.1.11
cluster-id=bully-demo
discovery-group=239.255.42.99
discovery-port=8999
```

`SERVER_HELLO` mang ID cố định, TCP port và `bootId`; IP được lấy từ nguồn gói UDP. Sau warm-up, mỗi node lấy snapshot peer còn hiệu lực để gửi `ELECTION` tới ID lớn hơn. `GET_COORDINATOR` đồng bộ trạng thái và `HEARTBEAT` kiểm tra Coordinator. `servers` vẫn được chấp nhận như seed/fallback tùy chọn khi multicast bị chặn.

Điều kiện cấu hình bắt buộc:

- ID server phải dương và không trùng.
- Các server cùng cluster phải có cùng `cluster-id`, multicast group và discovery port.
- Khi chạy LAN nên dùng IP `bind-address` cụ thể; nếu bỏ `port`, server dùng mặc định `9000 + id`.
- Các máy phải mở UDP discovery, TCP server và gRPC Worker.
- Hai process cùng ID chuyển sang `ID_CONFLICT` và không tham gia bầu cử cho đến khi xung đột biến mất.

## 4. Trình tự bầu cử

Ví dụ server 1 phát hiện Coordinator 3 chết trong cụm 1, 2, 3:

```text
Server 1              Server 2              Server 3
   | ELECTION ---------->|                     X
   |<----- ELECTION_OK   |                     X
   |                     | ELECTION ---------->X
   |                     | (không có Answer)   X
   |                     | CLAIM lease Worker
   |<------ COORDINATOR--|                     X
```

Trạng thái thực tế:

1. Follower gửi heartbeat định kỳ tới Coordinator.
2. Đủ `heartbeat-failures` lần lỗi thì xóa Coordinator cũ và gọi `startElection()`.
3. Ứng viên chỉ gửi `ELECTION` tới ID lớn hơn nó.
4. Nếu nhận `ELECTION_OK`, ứng viên chờ thông báo `COORDINATOR`. Nếu chưa có thông báo sau timeout, tick kế tiếp sẽ bầu lại.
5. Nếu không nhận `ELECTION_OK`, ứng viên `CLAIM` lease tại Worker.
6. Chỉ khi Worker chấp nhận lease, node mới đặt mình là Coordinator và gửi `COORDINATOR`.

Lease/generation tại Worker là lớp fencing chống hai Coordinator cùng sửa trạng thái mutex. Khi Coordinator cũ chết, ứng viên mới có thể phải chờ lease cũ hết hạn. Với cấu hình mẫu, thời gian failover thường gồm khoảng 3 lần heartbeat, thời gian bầu cử và tối đa một khoảng lease cũ.

## 5. Khi node có ID lớn nhất chạy lại

Node 3 multicast `SERVER_HELLO`, học membership trong warm-up rồi tự bầu cử. Vì không có ID cao hơn, nó thử `CLAIM`. Coordinator 2 đồng thời discovery node 3, gửi `ELECTION`, nhận `ELECTION_OK` rồi `DROP` lease. Sau khi claim thành công, node 3 công bố `COORDINATOR`.

Đây là đặc trưng “bully”: tiến trình còn sống có ID lớn nhất cuối cùng sẽ làm Coordinator.

## 6. Client tìm server và xin quyền

Client cũng có danh sách `servers` tĩnh. Nó lần lượt gửi `GET_COORDINATOR` cho các địa chỉ cho đến khi nhận `COORDINATOR_INFO`. Client không cần biết node nào đang là Coordinator từ trước và không mở cổng lắng nghe.

Sau khi người dùng bấm xin quyền:

1. Client tạo một `requestId` UUID và giữ nguyên ID này cho đến khi hủy, trả quyền hoặc session hết hạn.
2. Client gửi lặp lại `ACCESS_REQUEST` với cùng `requestId`; thao tác này idempotent.
3. Coordinator đưa yêu cầu vào `LinkedHashMap`, tương ứng hàng đợi FIFO.
4. Nếu Worker rảnh, Coordinator gọi `RESERVE` và trả `ACCESS_GRANTED` gồm `sessionId`, `accessToken` và thời gian còn lại.
5. Nếu Worker bận, Coordinator trả `ACCESS_QUEUED` cùng vị trí hiện tại.
6. Client chỉ được gọi xử lý PDF khi có đúng session/token.

Khi Coordinator chết, client giữ nguyên `requestId`, tìm Coordinator mới và gửi lại yêu cầu. Session đã được Worker cấp vẫn còn hiệu lực. Hàng đợi nằm trong RAM của Coordinator nên thứ tự cũ không được bảo toàn qua failover; các client chờ sẽ tạo lại hàng đợi theo thứ tự chúng kết nối lại.

## 7. Vì sao vẫn bảo đảm loại trừ tương hỗ

Worker tuần tự hóa các thao tác `CLAIM`, `RENEW`, `RESERVE`, `RELEASE` và xử lý PDF. Nó chỉ giữ tối đa một session hoạt động. Mọi lệnh từ server (trừ `CLAIM`) phải có đúng `coordinatorId`, `generation` và lease chưa hết hạn; mọi tác vụ client phải có đúng `clientId`, `sessionId`, `accessToken`.

Do đó:

- Hai request đồng thời chỉ có một request nhận quyền.
- Coordinator cũ sống lại hoặc bị cô lập không thể dùng generation cũ để cấp quyền.
- Coordinator mới không làm mất session mà client đang giữ.
- Mất phản hồi `ACCESS_GRANTED` không tạo session thứ hai; gửi lại cùng `clientId/requestId` khôi phục đúng token cũ.

## 8. Phạm vi và giới hạn nên nêu khi bảo vệ

- Membership server là eventual consistency qua UDP; một node mới có thể gây một cuộc bầu cử bổ sung sau khi được discovery.
- Multicast có thể bị Wi-Fi client isolation, router hoặc firewall chặn; `servers` seed là phương án fallback.
- Client chưa dùng multicast và vẫn giữ danh sách `servers` tĩnh; Worker vẫn được cấu hình thủ công.
- Không có consensus/quorum. Worker lease ngăn split-brain ở tầng cấp quyền, nhưng hệ thống không phải Raft/Paxos.
- Worker là single point of failure và giữ trạng thái trong RAM; khôi phục Worker không thuộc phạm vi đồ án.
- Hàng đợi không được replicate nên mất thứ tự FIFO cũ khi Coordinator chết.
- Mạng LAN được xem là tin cậy; chưa có TLS và xác thực server.
- Heartbeat timeout chỉ cho biết “không liên lạc được”, không phân biệt process chết với lỗi mạng.
- PDF là chức năng minh họa tài nguyên găng; correctness của Bully và session quan trọng hơn tính năng PDF.

Các giới hạn trên chấp nhận được với đồ án Bully nhỏ. Discovery chỉ cung cấp địa chỉ peer; nó không thay thế Bully bằng consensus hay làm thay đổi cơ chế mutex.

## 9. Kịch bản demo tối thiểu

1. Khởi động Worker, server 1, 2, 3; xác nhận server 3 trở thành Coordinator.
2. Cho ba client xin quyền; xác nhận đúng một client được cấp, hai client vào hàng đợi FIFO.
3. Dừng server 3; xác nhận server 2 thắng bầu cử và client vẫn tìm được Coordinator.
4. Nếu một client đang giữ quyền, xác nhận session/token vẫn dùng được sau failover.
5. Trả quyền; xác nhận client chờ tiếp theo được cấp.
6. Khởi động lại server 3; xác nhận server 3 giành lại Coordinator.
7. Dừng client đang giữ quyền; chờ session timeout và xác nhận client kế tiếp được cấp.

Log cần chỉ rõ các mốc `ELECTION`, `ELECTION_OK (Answer)`, `COORDINATOR`, heartbeat timeout, `CLAIM` generation và cấp/trả session.

## 10. Tiêu chí hoàn thành

- Build độc lập cả ba project và hợp đồng Protobuf giống nhau.
- Các server không nhận danh sách seed vẫn discovery đủ cluster và ID lớn nhất thắng.
- Không có hai session đồng thời trong test request song song.
- Kill ID lớn nhất thì ID lớn nhất còn sống được bầu.
- Restart ID lớn nhất thì nó trở lại làm Coordinator.
- Client tự tìm Coordinator mới và tái gửi đúng `requestId`.
- Coordinator/generation cũ bị Worker từ chối.
- Cấu hình LAN được kiểm tra trên các máy thật, bao gồm firewall và IP đích.

