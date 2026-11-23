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
9. Node chỉ trả `ELECTION_OK` khi thực sự có thể tiếp quản: đã hết warm-up discovery, không trùng ID và còn liên lạc được Worker. Node ID cao đang khởi động hoặc bị cắt khỏi Worker sẽ không chặn các node thấp hơn lên làm Coordinator.
10. `ELECTION`, `COORDINATOR` và thăm dò peer được gửi song song; việc thăm dò chạy ở luồng riêng nên máy chết (connect timeout) không làm trễ việc gia hạn lease của Coordinator.

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
                             UDP multicast discovery (239.255.42.99:8999)
             ┌──────────────┬──────────────┬──────────────┬──────────────┬──────────────┐
             │   Server 1   │   Server 2   │   Server 3   │   Server 4   │   Server 5   │
             │ 192.168.0.101│ 192.168.0.102│ 192.168.0.103│ 192.168.0.104│ 192.168.0.105│
             │  ID 1 :9000  │  ID 2 :9000  │  ID 3 :9000  │  ID 4 :9000  │  ID 5 :9000  │
             │ (+ Client 1) │ (+ Client 2) │ (+ Client 3) │ (+ Client 4) │ (+ Client 5) │
             └──────────────┴──────────────┴──────────────┴──────────────┴──────────────┘
                                       TCP Bully / heartbeat (:9000)
                                                    │
Client (bất kỳ máy) ───── TCP (:9000) ────> Coordinator (Server 5) ───── gRPC (:9100) ────> Worker (192.168.0.100)
Client (bất kỳ máy) ──────────────────────────────────────────────────── gRPC (:9100) ────> Worker (192.168.0.100)
```

| Kết nối | Giao thức | Chức năng |
|---|---|---|
| Server ↔ Server | UDP multicast (`:8999`) | Tự tìm ID, IP và TCP port của peer |
| Server ↔ Server | TCP JSON (`:9000`) | Bully, heartbeat và đồng bộ Coordinator |
| Client ↔ Server | TCP JSON (`:9000`) | Tìm Coordinator, xin/hủy/trả quyền |
| Server ↔ Worker | gRPC (`:9100`) | Lease, generation, reserve và release session |
| Client ↔ Worker | gRPC (`:9100`) | Kiểm tra session và xử lý PDF |

## 4. Các server tìm nhau như thế nào?

Mỗi server phát định kỳ `SERVER_HELLO` tới multicast group:

```json
{"version":1,"clusterId":"bully-demo","type":"SERVER_HELLO","nodeId":5,"tcpPort":9000,"bootId":"uuid"}
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
servers=1@192.168.0.101:9000,2@192.168.0.102:9000,3@192.168.0.103:9000,4@192.168.0.104:9000,5@192.168.0.105:9000
worker=192.168.0.100:9100
```

Client gửi `GET_COORDINATOR` song song tới mọi server và chọn `COORDINATOR_INFO` có `generation` lớn nhất (generation do Worker cấp, tăng đơn điệu), nên một server còn nhớ Coordinator đã chết không thể chỉ client tới node chết; nếu cluster đang bầu cử, client nhận `ELECTION_IN_PROGRESS` và tiếp tục chờ.

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
├── docs/              # Đặc tả giao thức, kiểm thử và báo cáo Word (BaoCao_DoAn_Bully.docx)
├── logs/              # Log chạy ứng dụng
├── run/               # CMD chạy từng node theo ID/IP và mở firewall
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

## 11. Triển khai và vận hành cụm trên mạng LAN (`192.168.0.0/24`)

Giả sử hệ thống chạy trong mạng LAN **`192.168.0.0/24`**, các máy đã được DHCP/DNS cấp sẵn địa chỉ IP cố định và phân bổ dịch vụ như sau:

- **1 máy chạy `document-worker`** (`192.168.0.100`).
- **5 máy chạy `server-node`** (`192.168.0.101` → `192.168.0.105`), tương ứng Bully ID `1` → `5`. Vì mỗi Server nằm trên một IP riêng nên tất cả dùng chung port TCP **`9000`**.
- **`client-node` chạy trực tiếp ngay trên cùng các máy với `server-node`** (bao gồm cả máy `192.168.0.105` đang làm Coordinator). Client không mở cổng lắng nghe mà dùng cổng tạm thời (`local-port=0`) để kết nối đi ra, nên hoàn toàn không xung đột port với `server-node` trên cùng máy.

### 11.1. Bảng quy hoạch IP và dịch vụ trong LAN

| Máy | Địa chỉ IP (DHCP/DNS cấp sẵn) | Dịch vụ chạy trên máy | Cổng lắng nghe | File cấu hình sử dụng |
|---|---|---|---|---|
| **Worker Host** | `192.168.0.100` | `document-worker` | TCP `9100` (gRPC) | `document-worker/config/worker.properties` |
| **Node 1** | `192.168.0.101` | `server-node` (ID=1) + `client-node` (client-1) | TCP `9000`, UDP `8999` | `server-1.properties`, `client-1.properties` |
| **Node 2** | `192.168.0.102` | `server-node` (ID=2) + `client-node` (client-2) | TCP `9000`, UDP `8999` | `server-2.properties`, `client-2.properties` |
| **Node 3** | `192.168.0.103` | `server-node` (ID=3) + `client-node` (client-3) | TCP `9000`, UDP `8999` | `server-3.properties`, `client-3.properties` |
| **Node 4** | `192.168.0.104` | `server-node` (ID=4) + `client-node` (client-4) | TCP `9000`, UDP `8999` | `server-4.properties`, `client-4.properties` |
| **Node 5 (Coordinator)** | `192.168.0.105` | `server-node` (ID=5) + `client-node` (client-5) | TCP `9000`, UDP `8999` | `server-5.properties`, `client-5.properties` |

*(Ghi chú: Nếu chỉ có 5 máy vật lý, `document-worker` cũng có thể chạy chung luôn trên một trong 5 máy `192.168.0.101..105` vì port `9100` tách biệt với `9000` và `8999`).*

### 11.2. Cấu hình chi tiết từng thành phần (đã khai báo sẵn trong `config/`)

#### a) Cấu hình Document Worker (`document-worker/config/worker.properties` tại `192.168.0.100`)
```properties
bind-address=0.0.0.0
port=9100
lease-ms=5000
session-ms=60000
```

#### b) Cấu hình 5 Server Node (`server-node/config/server-1.properties` → `server-5.properties`)
Mỗi máy `i` (`i = 1..5`) dùng file `server-i.properties` tương ứng. Tất cả 5 file có chung cấu hình cluster, chỉ khác `bind-address` (`192.168.0.101` → `192.168.0.105`) và `id` (`1` → `5`). Ví dụ `server-5.properties` trên máy `192.168.0.105`:

```properties
bind-address=192.168.0.105
id=5
port=9000
cluster-id=bully-demo
discovery-enabled=true
discovery-group=239.255.42.99
discovery-port=8999
discovery-interval-ms=1000
discovery-timeout-ms=5000
discovery-warmup-ms=2500
worker=192.168.0.100:9100
heartbeat-ms=1000
heartbeat-timeout-ms=1000
heartbeat-failures=3
election-ms=2000
queue-timeout-ms=3000
```

- `bind-address` đặt đúng IP card mạng LAN (`192.168.0.10x`) để MulticastSocket gắn đúng interface LAN và công bố đúng IP nguồn cho các node khác.
- Nếu mạng switch/Wi-Fi chặn gói UDP multicast `239.255.42.99:8999`, có thể thêm dòng seed dự phòng vào mỗi file `server-i.properties`:
  `servers=1@192.168.0.101:9000,2@192.168.0.102:9000,3@192.168.0.103:9000,4@192.168.0.104:9000,5@192.168.0.105:9000`

#### c) Cấu hình 5 Client Node chạy chung trên các máy Server (`client-node/config/client-1.properties` → `client-5.properties`)
Ví dụ `client-5.properties` (chạy ngay trên máy `192.168.0.105` cùng với Coordinator Server 5):

```properties
bind-address=0.0.0.0
local-port=0
grpc-local-port=0
id=client-5
username=Client 5
servers=1@192.168.0.101:9000,2@192.168.0.102:9000,3@192.168.0.103:9000,4@192.168.0.104:9000,5@192.168.0.105:9000
worker=192.168.0.100:9100
```

### 11.3. Mở tường lửa (Firewall) trên các máy trong LAN

Cách nhanh: chạy `run\setup-firewall.cmd worker` trên máy Worker và `run\setup-firewall.cmd server` trên 5 máy node (quyền Administrator; rule chỉ nhận nguồn `192.168.0.0/24`). Đặt network profile Windows là **Private**. IP các máy nên được gán bằng **DHCP reservation** trên router — nhiều router cấp dải động bắt đầu từ `.100`, dễ trùng với Worker.

Hoặc làm thủ công: trước khi chạy, mở PowerShell (quyền Administrator) trên các máy để cho phép kết nối LAN:

- **Tại máy Worker (`192.168.0.100`):**
  ```powershell
  New-NetFirewallRule -DisplayName "Bully-Worker-gRPC" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 9100
  ```
- **Tại 5 máy Node 1 → Node 5 (`192.168.0.101` → `192.168.0.105`):**
  ```powershell
  New-NetFirewallRule -DisplayName "Bully-Server-TCP" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 9000
  New-NetFirewallRule -DisplayName "Bully-Discovery-UDP" -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8999
  ```

### 11.4. Các bước khởi động từng máy

#### Bước 1: Tại máy `192.168.0.100` — Khởi động Document Worker
```powershell
java -jar document-worker/target/document-worker-1.0.0-SNAPSHOT.jar --config document-worker/config/worker.properties
```
*(Hoặc chạy `run\run-worker.cmd`; script cảnh báo nếu máy không có IP `192.168.0.100`).*

#### Bước 2: Tại từng máy `192.168.0.101` → `192.168.0.105` — Khởi động Server Node
Trên mỗi máy, mở một cửa sổ terminal và chạy đúng cấu hình của máy đó:

- **Trên máy `192.168.0.101` (Node 1):**
  ```powershell
  java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --config server-node/config/server-1.properties
  ```
- **Trên máy `192.168.0.102` (Node 2):**
  ```powershell
  java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --config server-node/config/server-2.properties
  ```
- **Trên máy `192.168.0.103` (Node 3):**
  ```powershell
  java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --config server-node/config/server-3.properties
  ```
- **Trên máy `192.168.0.104` (Node 4):**
  ```powershell
  java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --config server-node/config/server-4.properties
  ```
- **Trên máy `192.168.0.105` (Node 5 — Coordinator):**
  ```powershell
  java -jar server-node/target/server-node-1.0.0-SNAPSHOT.jar --config server-node/config/server-5.properties
  ```
*(Hoặc dùng `run\run-server.cmd <id>` với `<id>` từ `1` đến `5`. Bỏ trống `<id>` thì script tự suy ra ID từ IP `192.168.0.10x` của máy; script từ chối chạy nếu máy không có IP `192.168.0.10<id>` khớp với `bind-address` của cấu hình).*

Sau khoảng 2.5 giây warm-up, 5 Server tự phát hiện nhau qua multicast `239.255.42.99:8999` và **Server 5 (`192.168.0.105:9000`)** được bầu làm **COORDINATOR**.

#### Bước 3: Ngay trên các máy `192.168.0.101` → `192.168.0.105` — Khởi động Client Node cùng máy với Server
Trên mỗi máy đang chạy Server (kể cả máy `192.168.0.105` đang là Coordinator), mở thêm một cửa sổ terminal thứ hai và chạy Client tương ứng:

- **Trên máy `192.168.0.101` (cùng máy Server 1):**
  ```powershell
  java -jar client-node/target/client-node-1.0.0-SNAPSHOT.jar --config client-node/config/client-1.properties
  ```
- **Trên máy `192.168.0.102` (cùng máy Server 2):**
  ```powershell
  java -jar client-node/target/client-node-1.0.0-SNAPSHOT.jar --config client-node/config/client-2.properties
  ```
- **Trên máy `192.168.0.103` (cùng máy Server 3):**
  ```powershell
  java -jar client-node/target/client-node-1.0.0-SNAPSHOT.jar --config client-node/config/client-3.properties
  ```
- **Trên máy `192.168.0.104` (cùng máy Server 4):**
  ```powershell
  java -jar client-node/target/client-node-1.0.0-SNAPSHOT.jar --config client-node/config/client-4.properties
  ```
- **Trên máy `192.168.0.105` (cùng máy Coordinator Server 5):**
  ```powershell
  java -jar client-node/target/client-node-1.0.0-SNAPSHOT.jar --config client-node/config/client-5.properties
  ```
*(Hoặc dùng `run\run-client.cmd <id>` với `<id>` từ `1` đến `5`; bỏ trống thì tự suy ra từ IP máy).*

Khi bấm `Stop Server` trên Server 5 (`192.168.0.105`), Server 4 (`192.168.0.104`) tự thắng bầu cử Bully lên làm Coordinator mới; đồng thời `Client 5` đang nằm trên máy `192.168.0.105` vẫn tiếp tục hoạt động bình thường và tự chuyển kết nối sang Server 4 (`192.168.0.104:9000`).

---

## 12. Chạy gom toàn bộ 5 Server + 5 Client trên một máy duy nhất (Localhost)

Khi muốn test nhanh toàn bộ 11 tiến trình trên **1 máy duy nhất (`127.0.0.1`)**, chỉ cần ghi đè `--bind-address 127.0.0.1` và cấp 5 port khác nhau (`9001..9005`) qua tham số dòng lệnh mà không cần sửa lại file `.properties`:

```powershell
# 1. Worker
Start-Process java -ArgumentList '-jar','document-worker/target/document-worker-1.0.0-SNAPSHOT.jar','--bind-address','127.0.0.1','--port','9100'

# 2. 5 Server (ID 1..5 trên port 9001..9005)
1..5 | ForEach-Object {
    Start-Process java -ArgumentList '-jar','server-node/target/server-node-1.0.0-SNAPSHOT.jar','--config',"server-node/config/server-$_.properties",'--bind-address','127.0.0.1','--port',(9000 + $_),'--worker','127.0.0.1:9100'
}

# 3. 5 Client
$localServers = '1@127.0.0.1:9001,2@127.0.0.1:9002,3@127.0.0.1:9003,4@127.0.0.1:9004,5@127.0.0.1:9005'
1..5 | ForEach-Object {
    Start-Process java -ArgumentList '-jar','client-node/target/client-node-1.0.0-SNAPSHOT.jar','--config',"client-node/config/client-$_.properties",'--servers',$localServers,'--worker','127.0.0.1:9100'
}
```

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

| Bộ test | Số test | Nội dung |
|---|---:|---|
| `ConfigTest` (server/client/worker) | 3 × 3 | UTF-8, CLI override, bind/port, multicast và ID |
| `WorkerStateTest` | 11 | Lease, generation, fencing, re-claim của chủ lease, token, timeout và request đồng thời |
| `PdfServiceTest` | 5 | Merge, Split, Watermark và giới hạn dữ liệu |
| `DistributedIntegrationTest` (đa JVM) | 7 | Discovery không seed, FIFO, failover, restart node cao, node thấp restart không gây bầu vô hạn, node cao mất Worker không chặn bầu cử, client recovery, client chờ bị ngắt |
| `*ViewTest` (JavaFX) | 3 | Mở/render Server, Client và Worker (chỉ chạy với `-Ui`, cần màn hình) |

Tổng cộng 35 test. Lần xác nhận ngày 23/11/2026 (`build.ps1 -Integration`): 32 test không cần giao diện đều đạt, 0 failure, 0 error; 3 test JavaFX bỏ qua khi không bật `-Ui`. Báo cáo nằm trong `target/surefire-reports/`; log integration nằm trong `document-worker/target/integration-*`.

## 15. Kịch bản bảo vệ đề tài (trên mạng LAN 5 Node)

1. Khởi động Worker (`192.168.0.100:9100`) và 5 Server (`192.168.0.101..105:9000`); quan sát **Server 5** được bầu làm Coordinator.
2. Mở các Client nằm ngay trên các máy Server (bao gồm Client 5 nằm chung máy với Coordinator Server 5) và bấm **Request Access**; xác nhận đúng một client được cấp quyền (`ACTIVE`), các client còn lại xếp hàng FIFO (`QUEUED`).
3. Dừng **Server 5**; quan sát heartbeat timeout, `ELECTION`, `ELECTION_OK`, **Server 4 (`192.168.0.104`)** claim lease và phát `COORDINATOR`. Client 5 trên máy `192.168.0.105` tự chuyển sang gọi Server 4.
4. Xác nhận client đang giữ quyền vẫn tiếp tục xử lý PDF bằng session cũ.
5. Release client hiện tại; client kế tiếp trong hàng đợi được cấp quyền.
6. Khởi động lại **Server 5** (`192.168.0.105`); discovery tìm thấy ID 5 cao hơn và Server 5 giành lại vai trò Coordinator từ Server 4.
7. Dừng một client đang xếp hàng hoặc đang giữ quyền:
   - Nếu client đang xếp hàng bị tắt: sau `queue-timeout-ms` (3 giây), Coordinator tự loại khỏi hàng đợi để không chặn client phía sau.
   - Nếu client đang giữ quyền bị tắt: sau `session-ms` (60 giây), Worker thu hồi phiên và cấp cho client kế tiếp.

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
