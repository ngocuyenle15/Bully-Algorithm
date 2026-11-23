# Đồ án loại trừ tương hỗ sử dụng Bully Algorithm

Hệ thống mô phỏng một cụm server bầu Coordinator bằng thuật toán Bully. Client xin quyền sử dụng một tài nguyên găng là `document-worker`; tại một thời điểm chỉ một client có session hợp lệ để xử lý PDF.

Project dùng Java 21 và gồm ba ứng dụng Maven độc lập:

| Ứng dụng | Trách nhiệm |
|---|---|
| `server-node` | Server discovery, Bully election, heartbeat, Coordinator và hàng đợi yêu cầu |
| `client-node` | Tìm Coordinator, xin/hủy/trả quyền và gửi tác vụ PDF |
| `document-worker` | Điểm loại trừ tương hỗ, lease Coordinator, session/token và xử lý PDF |

## 1. Project có đúng bản chất thuật toán Bully không?

Có. Phần bầu cử tuân theo các quy tắc cốt lõi của Bully:

1. Mỗi server có một ID số nguyên dương, duy nhất và cố định.
2. Node phát hiện Coordinator chết sẽ gửi `ELECTION` chỉ tới các node có ID lớn hơn.
3. Node lớn hơn trả `ELECTION_OK` (`Answer` trong đề) và tự phát động bầu cử.
4. Nếu ứng viên không nhận được `ELECTION_OK`, nó trở thành ứng viên thắng cuộc.
5. Node thắng gửi `COORDINATOR` để thông báo kết quả.
6. Nếu đã nhận `ELECTION_OK`, node chờ `COORDINATOR`; hết thời gian chờ mà chưa có kết quả thì bầu lại.
7. Khi node có ID lớn hơn chạy lại, nó tham gia bầu cử và cuối cùng giành lại vai trò Coordinator.
8. Coordinator hiện tại nhận `ELECTION` từ node thấp hơn (ví dụ node thấp vừa khởi động lại) sẽ trả `ELECTION_OK` và **công bố lại** `COORDINATOR`, nên các node thấp hội tụ ngay thay vì bầu lại vô hạn.
9. Node chỉ trả `ELECTION_OK` và chỉ tự ứng cử khi còn liên lạc được Worker. Node ID cao bị cắt khỏi Worker sẽ không chặn các node thấp hơn lên làm Coordinator.

Trong source, ba thông điệp của đề tương ứng:

| Trong đề | Trong project | Ý nghĩa |
|---|---|---|
| Election | `ELECTION` | Yêu cầu node ID lớn hơn tham gia bầu cử |
| Answer | `ELECTION_OK` | Node lớn hơn xác nhận còn sống |
| Coordinator | `COORDINATOR` | Công bố Coordinator mới |

UDP multicast discovery không thay thế Bully. Discovery chỉ cung cấp membership `ID → IP:port`; việc chọn Coordinator vẫn do các thông điệp và quy tắc Bully thực hiện.

Worker lease/generation cũng là lớp an toàn bổ sung, không phải một phần của Bully. Node thắng chỉ công bố `COORDINATOR` sau khi Worker chấp nhận lease, nhờ đó Coordinator cũ không thể tiếp tục cấp quyền sau failover.

## 2. Bully và loại trừ tương hỗ là hai nhiệm vụ khác nhau

Bully giải quyết câu hỏi: **Server nào được quyền điều phối?**

Worker giải quyết câu hỏi: **Client nào đang sở hữu tài nguyên găng?**

Luồng cấp quyền:

```text
Client --ACCESS_REQUEST--> Coordinator --RESERVE--> Worker
Client <--ACCESS_GRANTED-- Coordinator <--session/token-- Worker
Client -------- PDF + session/token -------------> Worker
Client --ACCESS_RELEASE--> Coordinator --RELEASE--> Worker
```

Worker tuần tự hóa mọi thay đổi trạng thái và chỉ cho phép tối đa một session hoạt động. Vì vậy hai request đồng thời không thể cùng được cấp quyền, kể cả khi Coordinator thay đổi.

## 3. Kiến trúc và giao thức

```text
                  UDP multicast discovery
            ┌──────────┬──────────┬──────────┐
            │ Server 1 │ Server 2 │ Server 3 │
            │   ID 1   │   ID 2   │   ID 3   │
            └──────────┴──────────┴──────────┘
                   TCP Bully / heartbeat
                            │
Client ───── TCP ─────> Coordinator ───── gRPC ─────> Worker
Client ─────────────────────── gRPC ─────────────────> Worker
```

| Kết nối | Giao thức | Chức năng |
|---|---|---|
| Server ↔ Server | UDP multicast | Tự tìm ID, IP và TCP port của peer |
| Server ↔ Server | TCP JSON | Bully, heartbeat và đồng bộ Coordinator |
| Client ↔ Server | TCP JSON | Tìm Coordinator, xin/hủy/trả quyền |
| Server ↔ Worker | gRPC | Lease, generation, reserve và release session |
| Client ↔ Worker | gRPC | Kiểm tra session và xử lý PDF |

## 4. Các server tìm nhau như thế nào?

Mỗi server phát định kỳ `SERVER_HELLO` tới multicast group:

```json
{"version":1,"clusterId":"bully-demo","type":"SERVER_HELLO","nodeId":3,"tcpPort":9003,"bootId":"uuid"}
```

Server nhận lấy IP trực tiếp từ địa chỉ nguồn UDP, không tin IP trong payload. Membership động lưu ID, địa chỉ, `bootId` và thời điểm `lastSeen`.

- `cluster-id` ngăn hai cụm trên cùng LAN discovery nhầm nhau.
- `bootId` phân biệt hai lần chạy của cùng một ID.
- `discovery-warmup-ms` cho phép học membership trước lần bầu cử đầu.
- `discovery-timeout-ms` loại peer không còn phát thông báo.
- Hai process cùng ID chuyển sang `ID_CONFLICT` và không tham gia bầu cử.
- `servers` phía server là seed/fallback tùy chọn khi multicast bị chặn.

ID không được tự sinh và không thay đổi lúc runtime. Mỗi server phải có ID cố định, dương và không trùng.

## 5. Điều gì xảy ra khi Coordinator chết?

Ví dụ Coordinator 3 chết:

```text
Server 1              Server 2              Server 3
   | heartbeat timeout   |                     X
   | ELECTION ---------->|                     X
   |<----- ELECTION_OK   |                     X
   |                     | ELECTION ---------->X
   |                     | không có Answer     X
   |                     | CLAIM lease Worker
   |<------ COORDINATOR--|                     X
```

Trình tự thực tế:

1. Follower gửi heartbeat tới Coordinator hiện tại.
2. Đủ `heartbeat-failures` lần lỗi thì xóa Coordinator cũ.
3. Các node chạy Bully trên snapshot membership còn hoạt động.
4. ID lớn nhất còn sống thắng.
5. Ứng viên mới chờ lease cũ hết hạn hoặc được nhường.
6. Worker tăng `generation` và cấp lease mới.
7. Node mới phát `COORDINATOR`.

Nếu Server 3 chạy lại, discovery tìm thấy ID 3. Vì 3 lớn hơn Coordinator 2, một cuộc bầu cử mới xảy ra và Server 3 cuối cùng trở lại làm Coordinator.

## 6. Client tìm Coordinator như thế nào?

Client không tham gia Bully và hiện không dùng multicast. Client vẫn giữ danh sách địa chỉ server trong cấu hình:

```properties
servers=1@192.168.1.11:9001,2@192.168.1.12:9002,3@192.168.1.13:9003
worker=192.168.1.10:9100
```

Client gửi `GET_COORDINATOR` lần lượt tới các server. Một server biết kết quả sẽ trả `COORDINATOR_INFO`; nếu cluster đang bầu cử, client nhận `ELECTION_IN_PROGRESS` và tiếp tục chờ.

Worker không tự discovery. Địa chỉ Worker vẫn được cấu hình thủ công tại cả Client và Server. `document-worker` chỉ mở cổng gRPC và chờ kết nối.

## 7. Coordinator chết đúng lúc Client xin quyền

Client tạo một `requestId` và giữ nguyên ID này cho đến khi request kết thúc. Khi TCP lỗi, client xóa cache Coordinator nhưng không xóa `requestId`, sau đó tìm Coordinator mới và gửi lại cùng request.

Có ba trường hợp:

1. Coordinator chết trước khi nhận request: Coordinator mới xử lý request như mới.
2. Request đã ở queue nhưng chưa reserve: queue RAM cũ mất; client gửi lại và được đưa vào queue mới.
3. Worker đã reserve nhưng phản hồi bị mất: Coordinator mới gọi `RESERVE` với cùng `clientId/requestId`; Worker trả lại đúng session/token cũ, không tạo session thứ hai.

Nếu Client đã nhận `ACCESS_GRANTED`, session nằm ở Worker nên vẫn tồn tại khi Coordinator chết. Client tiếp tục xử lý PDF và gửi `ACCESS_RELEASE` tới Coordinator mới sau failover.

## 8. Trạng thái Worker và cơ chế fencing

| Lệnh | Ý nghĩa |
|---|---|
| `CLAIM` | Xin lease khi lease cũ hết hạn; tăng generation |
| `RENEW` | Gia hạn lease hiện tại |
| `DROP` | Nhường lease khi phát hiện node ID cao hơn |
| `STATUS` | Xác minh Coordinator/generation hiện tại |
| `RESERVE` | Tạo hoặc khôi phục session theo `clientId/requestId` |
| `RELEASE` | Trả session đúng client/session/token |

Ngoài `CLAIM`, mọi lệnh phải có đúng `coordinatorId`, `generation` và lease chưa hết hạn. Coordinator cũ hoặc node bị phân vùng không thể dùng generation cũ để cấp quyền.

## 9. Cấu trúc thư mục

```text
Bully/
├── client-node/       # Client JavaFX/headless
├── document-worker/   # Worker gRPC và PDF
├── server-node/       # Discovery, Bully và Coordinator
├── docs/              # Đặc tả giao thức và kiểm thử
├── logs/              # Log chạy ứng dụng
├── run/               # CMD chạy bằng Java đóng gói
├── runtime/           # JDK 21.0.10
├── scripts/           # Build, test và khởi động demo
└── README.md
```

Ba project Maven độc lập và không tham chiếu artifact nội bộ của nhau. Mỗi project có bản sao `document.proto`; `scripts/check-protocol.ps1` kiểm tra hợp đồng wire giống nhau.

## 10. Build

Build toàn bộ:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

Hoặc build riêng:

```powershell
mvn -f server-node/pom.xml clean package
mvn -f client-node/pom.xml clean package
mvn -f document-worker/pom.xml clean package
```

Fat JAR được tạo trong thư mục `target/` của từng project với tên `<project>-1.0.0-SNAPSHOT.jar`.

## 11. Chạy demo trên một máy

Build trước, sau đó mở các lệnh trong các cửa sổ riêng theo thứ tự:

```cmd
run\run-worker.cmd
run\run-server.cmd 1
run\run-server.cmd 2
run\run-server.cmd 3
run\run-client.cmd 1
run\run-client.cmd 2
run\run-client.cmd 3
```

Các CMD sử dụng `runtime/jdk-21.0.10` và tự xác định thư mục project, nên có thể double-click. Nếu không truyền số, server/client mặc định dùng cấu hình số 1.

Có thể mở toàn bộ demo bằng:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-demo.ps1
```

Nút `Stop Server` mô phỏng crash: server không chủ động trả lease, buộc cluster phát hiện lỗi và chờ lease hết hạn.

## 12. Chạy trên nhiều máy LAN

Ví dụ Server 1:

```properties
bind-address=192.168.1.11
id=1
port=9001
cluster-id=bully-demo
discovery-enabled=true
discovery-group=239.255.42.99
discovery-port=8999
discovery-interval-ms=1000
discovery-timeout-ms=5000
discovery-warmup-ms=2500
worker=192.168.1.10:9100
```

Server 2 và 3 chỉ thay `bind-address`, `id` và `port`. Tất cả server phải có cùng `cluster-id`, multicast group, discovery port và Worker address.

| Cổng | Giao thức | Thành phần |
|---|---|---|
| 8999 | UDP multicast | Server discovery |
| 9001–9003 | TCP | Server/Bully/Client request |
| 9100 | TCP gRPC | Document Worker |

Client vẫn phải cấu hình danh sách IP server và Worker thực tế. Không dùng `0.0.0.0` làm địa chỉ đích. Với máy nhiều card mạng, nên đặt `bind-address` thành IP card LAN cụ thể để multicast đi đúng interface.

## 13. Xử lý PDF

- Merge: cần ít nhất hai file.
- Split: một file và khoảng trang tính từ 1, bao gồm hai đầu.
- Watermark: một file và nội dung 1–200 ký tự.
- Giới hạn: 10 MB/file, 40 MB tổng input, 60 MB output.
- Hoàn thành job không tự release session; client giữ quyền đến khi Release hoặc timeout.

PDF chỉ là tài nguyên minh họa. Trọng tâm đồ án là Bully election, failover và loại trừ tương hỗ.

## 14. Kiểm thử toàn bộ

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 -Integration -Ui
```

| Nhóm | Số test | Nội dung |
|---|---:|---|
| Config | 9 | UTF-8, CLI override, bind/port, multicast và ID |
| Bully/cluster/discovery | 10 | Vòng bầu cử, Answer/Coordinator, timeout, node phục hồi, ID trùng và membership động |
| Client và lưu kết quả | 3 | Retry giữ nguyên request, cancel/release và ghi file an toàn |
| WorkerState | 10 | Lease, generation, token, timeout và request đồng thời |
| PDF | 5 | Merge, Split, Watermark và giới hạn dữ liệu |
| Integration đa JVM | 4 | Discovery không seed, FIFO, failover, restart, retry và client recovery |
| JavaFX | 3 | Mở/render Server, Client và Worker |
| Window sizing | 12 | DPI, taskbar, nhiều màn hình và giới hạn cửa sổ |

Tổng cộng 56 test riêng biệt, 0 failure, 0 error và 0 skipped trong lần xác nhận ngày 08/11/2026. Báo cáo nằm trong `target/surefire-reports/`; log integration nằm trong `document-worker/target/integration-*`.

## 15. Kịch bản bảo vệ đề tài

1. Khởi động Worker và ba Server; quan sát Server 3 được bầu.
2. Cho ba Client xin quyền; xác nhận một client được cấp, hai client chờ FIFO.
3. Dừng Server 3; quan sát heartbeat timeout, `ELECTION`, `ELECTION_OK`, Server 2 claim lease và phát `COORDINATOR`.
4. Xác nhận client đang giữ quyền vẫn dùng session cũ.
5. Release client hiện tại; client kế tiếp được cấp.
6. Khởi động lại Server 3; discovery tìm thấy ID cao hơn và Server 3 giành lại Coordinator.
7. Dừng client đang giữ quyền; sau session timeout, client kế tiếp được cấp.

Các log quan trọng: `Discovery started`, `Discovered server`, `ELECTION`, `ELECTION_OK (Answer)`, `COORDINATOR`, heartbeat timeout, generation và session grant/release.

## 16. Giới hạn phạm vi

- Membership UDP là eventual consistency; multicast có thể bị firewall, router hoặc Wi-Fi client isolation chặn.
- Client chưa tự discovery và vẫn cần danh sách server tĩnh.
- Worker là single point of failure và giữ trạng thái trong RAM.
- Queue Coordinator không replicate; sau failover, client gửi lại request nhưng thứ tự FIFO cũ có thể thay đổi.
- Không có quorum/consensus như Raft hoặc Paxos. Worker lease chỉ fencing quyền cấp session.
- Không có TLS hoặc xác thực; giả định LAN tin cậy.
- Heartbeat không phân biệt process chết với lỗi mạng.
- Worker restart làm mất session hiện có; phục hồi Worker nằm ngoài phạm vi đồ án.

Các giới hạn này không làm sai thuật toán Bully. Trong phạm vi đồ án, hệ thống bảo đảm eventual election của node ID lớn nhất còn hoạt động và tối đa một client sở hữu tài nguyên găng tại một thời điểm.

## 17. Tài liệu bổ sung

- `docs/PROTOCOL.md`: wire protocol, session và fencing.
- `docs/TESTING.md`: test case và bằng chứng kiểm thử.
- `docs/ARCHITECTURE_REVIEW.md`: phân tích kiến trúc và kịch bản failover.
- README riêng trong từng project: hướng dẫn build độc lập.
