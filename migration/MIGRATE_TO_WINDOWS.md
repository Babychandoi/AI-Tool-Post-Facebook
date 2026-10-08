# Chuyển AI Automatic Post Facebook sang Windows

## 1. Bản sao này chứa gì?

Bản chụp ngày **08/10/2026, 11:22–11:23 giờ Việt Nam** được lấy khi các dịch vụ ghi dữ liệu đã tạm dừng. Sau đó app trên Mac được mở lại. Bản sao chỉ phản ánh thời điểm này; dữ liệu tạo sau đó cần sao lưu bổ sung trước khi chuyển hẳn.

Thông tin đã lưu trong app nằm trong PostgreSQL; file ảnh nằm trong MinIO. Bản chụp có 1 thương hiệu, 3 tài liệu kiến thức, 8 nội dung/phiên bản, 8 tài sản ảnh và 3 bản ghi xuất bản, cùng tài khoản, lịch sử, cấu hình, hàng đợi và kết nối. Số lượng của toàn bộ 33 bảng được ghi trong `inventory/snapshot.json` và `MANIFEST.json`.

| Đường dẫn trong gói | Nội dung / cách dùng |
| --- | --- |
| `project/` | Toàn bộ mã nguồn, tài liệu, cấu hình và tiện ích trình duyệt của dự án |
| `project/.env` | Cấu hình thực tế đã đối chiếu với container đang chạy; cần giữ bí mật |
| `project/migration/artifacts/` | JAR backend, giao diện đã build và nginx.conf lấy trực tiếp từ app đang chạy |
| `images/linux-amd64-images.tar.gz` | 6 Docker image dành cho CPU Intel/AMD 64 bit; API và worker dùng chung image |
| `data/postgres.dump` | PostgreSQL custom dump; đây là nguồn khôi phục database sang Windows |
| `data/postgres-globals.sql` | Bản lưu các role PostgreSQL để tham khảo; script không tự áp dụng |
| `data/minio-data.tar.gz` | Toàn bộ kho ảnh MinIO và metadata |
| `data/redis-data.tar.gz` | Dữ liệu Redis/AOF |
| `data/image-bridge-state.tar.gz` | Trạng thái broker kết nối tiện ích tạo ảnh |
| `data/postgres-data.tar.gz` | Dữ liệu PostgreSQL thô của Mac, chỉ lưu dự phòng; không dùng để khôi phục Windows |
| `legacy-runtime/mac-native-runtime.tar.gz` | Dữ liệu PostgreSQL/media chạy native trước đây, log và bằng chứng thử nghiệm; không tự nhập vào app mới |
| `inventory/` | Thời điểm, thống kê, danh sách ảnh/image, log build và cấu hình nguồn |
| `MANIFEST.json`, `SHA256SUMS.txt` | Kiểm tra tính toàn vẹn của các file |
| `Restore-Windows.ps1`, `Start-Windows.ps1`, `Stop-Windows.ps1` | Khôi phục lần đầu, mở và dừng app |

Không mang theo phiên đăng nhập/cookie/profile Cốc Cốc hoặc ChatGPT. Nội dung chưa bấm lưu trong biểu mẫu không thuộc bản sao. `node_modules`, cache build và JDK dành riêng cho Mac không cần để chạy bộ Docker này.

**Gói có App Secret, token, khóa giải mã và dữ liệu riêng.** Chuyển nguyên ZIP qua nơi lưu trữ riêng; không đưa lên GitHub/public link. Giữ nguyên `TOKEN_ENCRYPTION_KEY` trong `.env`: đổi khóa sẽ khiến các token đã lưu trong database không giải mã được. Không in `.env`, `source.env`, `source-compose-private.json` hoặc token vào chat/log.

## 2. Chọn môi trường Windows

Ước lượng cho dự án: CPU 4 luồng trở lên, RAM 8 GB tối thiểu, 16 GB dễ dùng hơn, ít nhất 20 GB trống cho Docker và dữ liệu. Không cần GPU vì việc tạo nội dung/ảnh được xử lý ở dịch vụ AI bên ngoài. Dung lượng ảnh tăng dần theo thời gian.

### Windows 10/11

Cài Docker Desktop đáp ứng yêu cầu phiên bản Windows hiện hành, bật WSL 2 và chế độ **Linux containers**, rồi cài Python **3.10+**. Tham khảo [hướng dẫn Docker Desktop cho Windows](https://docs.docker.com/desktop/setup/install/windows-install/) và [Docker với WSL 2](https://docs.docker.com/desktop/features/wsl/).

### Windows Server

Docker Desktop không hỗ trợ Windows Server. Với Server 2022/2025, có thể dùng WSL 2 + Ubuntu + Docker Engine trong Ubuntu; xem [WSL trên Windows Server](https://learn.microsoft.com/en-us/windows/wsl/install-on-server) và [cài Docker Engine trên Ubuntu](https://docs.docker.com/engine/install/ubuntu/). Lệnh cài WSL chạy trong PowerShell quản trị và có thể cần khởi động lại máy.

Chạy lệnh khôi phục bằng `python3` **trong Ubuntu/WSL**, nơi Docker CLI truy cập được Docker Engine. Có thể đặt gói ở `C:\AI-Marketing` và dùng `/mnt/c/AI-Marketing` từ WSL. Dữ liệu database/MinIO thực tế nằm trong Docker named volumes, không đặt trực tiếp database trên thư mục NTFS.

Cốc Cốc và ChatGPT cần một phiên desktop Windows đã đăng nhập. Máy chỉ chạy dịch vụ nền, không có trình duyệt/phiên desktop hoạt động, chưa đáp ứng cách tạo ảnh ChatGPT Web hiện tại. Nếu dùng VM Linux riêng thay WSL thì cần thiết kế kết nối loopback/forwarding trước khi ghép tiện ích; không công khai cổng broker. Windows thường truy cập dịch vụ WSL qua localhost theo [tài liệu mạng WSL](https://learn.microsoft.com/en-us/windows/wsl/networking/).

## 3. Khôi phục lần đầu

1. Chép nguyên ZIP và file `.sha256` sang máy mới; đối chiếu `Get-FileHash <file.zip> -Algorithm SHA256` với file checksum bên cạnh.
2. Giải nén. Đặt thư mục **chứa `START_HERE.md`** ở vị trí cố định, ví dụ `C:\AI-Marketing`. Các script lưu đường dẫn tuyệt đối sau khôi phục; không di chuyển thư mục đó khi app đang sử dụng.
3. Kiểm tra `docker info` cho thấy Docker chạy Linux containers, Docker Compose v2 hoạt động, Python >= 3.10. Bản image đi kèm dành cho `amd64/x86_64`.
4. Từ PowerShell, ở thư mục chứa `START_HERE.md`, chạy:

```powershell
py -3 .\project\migration\restore.py --verify-only
py -3 .\project\migration\restore.py
```

Nếu máy không có launcher `py`, dùng `python` tương ứng. Có thể chạy wrapper `Restore-Windows.ps1` nếu chính sách PowerShell cho phép script local. Không cần thay đổi chính sách thực thi của toàn máy.

Trong WSL:

```bash
cd /mnt/c/AI-Marketing
python3 project/migration/restore.py --verify-only
python3 project/migration/restore.py
```

Script kiểm tra SHA256, nạp Docker image, tạo project **`ai-marketing-windows`** với 4 volume mới, giải nén MinIO/Redis/broker, nhập database bằng `pg_restore`, so số dòng tất cả bảng, rồi mở app. Không cần Maven, Java hay Node cài trên Windows để chạy bản đóng gói. Không đưa binary dump qua pipeline văn bản PowerShell.

Script từ chối ghi đè volume có sẵn. Nếu bị dừng giữa chừng, giữ dữ liệu và đọc lỗi; không xóa volume để thử lại tùy tiện. AI cần xem trạng thái đã nhập đến đâu hoặc dùng một `--project` mới khi phù hợp. `--verify-only` phải dùng trên bản giải nén còn nguyên: `.env` được cập nhật trong lần restore nên kiểm tra lại toàn bộ manifest sau restore có thể báo thay đổi hợp lệ.

Nếu CPU ARM64, dùng `--build-from-artifacts`; cần Internet để lấy/build image. Chế độ này cũng dùng JAR và giao diện đã đóng gói, không chạy test/build Maven. CPU Intel/AMD thông thường nên dùng image đi kèm.

## 4. Mở app và nối lại ChatGPT

Mở **http://127.0.0.1:5173** trên máy Windows. Tài khoản app hiện có giữ nguyên trong database. Với tài khoản demo, mật khẩu theo `DEMO_PASSWORD` trong `.env`; không tự tạo workspace mới để thay dữ liệu cũ.

### Nội dung chữ

Các kết nối ChatGPT/OAuth lưu trong database đã được mang theo. Giữ nguyên khóa mã hóa. Nếu refresh token hết hạn hoặc máy mới báo cần xác thực, đăng nhập lại bằng luồng của app. Không sao chép cookie trình duyệt hay file auth của Codex để thay bước đăng nhập. Tránh dùng đồng thời cùng một kết nối trên hai worker trong lúc chuyển máy.

### Ảnh ChatGPT Web thử nghiệm

1. Cài/mở Cốc Cốc trên Windows và tự đăng nhập tài khoản ChatGPT còn lượt tạo ảnh.
2. Mở trang quản lý tiện ích, bật chế độ nhà phát triển, **Load unpacked / Tải tiện ích đã giải nén**: chọn `project\tools\chatgpt-web-images\extension`.
3. Mở app → phần ảnh bài viết → chọn ChatGPT Web → tạo mã ghép nối.
4. Mở popup tiện ích “AI Marketing – Ảnh ChatGPT tự động”, nhập mã đó và kết nối.
5. Giữ trình duyệt và phiên desktop hoạt động; cho phép tiện ích mở tab ChatGPT. Tiện ích sẽ tự gửi prompt, lấy ảnh và chuyển về app. Không cần AI điều khiển ChatGPT thủ công.

Manifest có khóa công khai cố định; ID tiện ích dự kiến **`gedjjpmaogkijdpblbekoofeahdbmiih`**. Broker mặc định `http://127.0.0.1:58110`. Không đổi host/port nếu chưa sửa đồng bộ quyền tiện ích và cấu hình. Với WSL, đường dẫn tiện ích có thể dùng thư mục Windows `C:\AI-Marketing\project\tools\chatgpt-web-images\extension`.

Trạng thái broker trong backup giữ lịch sử ghép nối nhưng không thay thế việc cài/đăng nhập/ghép trên máy mới. Phiên bản source có cả bản sửa tránh poll trùng lúc tiện ích khởi động. Cách này phụ thuộc giao diện và hạn mức ChatGPT; không có cam kết API chính thức. Máy không được sleep nếu muốn chạy lịch đều.

## 5. Fanpage, lịch đăng và chuyển máy chính thức

Fanpage đang dùng: **Công ty TNHH Poip Legal**, ID **675256752343426**. Token Page mã hóa nằm trong database; Meta App ID/Secret nằm trong cấu hình. Không cần tạo app Meta mới chỉ vì đổi máy, nhưng token hết hạn/thu hồi thì cần kết nối lại theo yêu cầu của Meta.

Trước snapshot, app có **0 lịch hằng ngày đang bật**, `LIVE_PUBLISHING=false`. Gói khôi phục cũng ép `LIVE_PUBLISHING=false` ở API và worker, đồng thời tắt `enabled/live_consent` của lịch. Thương hiệu, mục tiêu, chủ đề, prompt ảnh và lựa chọn AUTO vẫn được lưu.

Sau khi máy mới chạy được, kiểm tra dữ liệu và kết nối trong app. Việc đăng một bài thật hay bật lịch cần chỉ dẫn của chủ dự án; không tự làm để “test migration”. Một số bản nháp đang chờ duyệt do nội dung pháp lý/thương mại; giữ nguyên trạng thái, không tự duyệt. Chỉ cho **một máy** chạy worker/lịch chính khi chuyển hẳn để tránh đăng trùng. Mac hiện đã mở lại sau backup; chưa ngừng sử dụng vĩnh viễn.

Để bật đăng thật sau khi được yêu cầu, AI cần sửa cả override `project/migration/compose.windows.yaml` (đang ép false), `.env` và bật lịch/đồng ý trong app, rồi recreate dịch vụ cần thiết. Không chỉ đổi một biến `.env` rồi kết luận đã bật.

## 6. Vận hành

PowerShell wrapper đọc `restore-state.json` tạo sau khôi phục:

```powershell
.\Start-Windows.ps1
.\Stop-Windows.ps1
```

Lệnh trực tiếp dùng được cả PowerShell/WSL, chạy từ thư mục gói:

```text
docker compose --project-directory ./project --env-file ./project/.env -p ai-marketing-windows -f ./project/compose.yaml -f ./project/migration/compose.windows.yaml ps
docker compose --project-directory ./project --env-file ./project/.env -p ai-marketing-windows -f ./project/compose.yaml -f ./project/migration/compose.windows.yaml up -d --no-build --pull never
docker compose --project-directory ./project --env-file ./project/.env -p ai-marketing-windows -f ./project/compose.yaml -f ./project/migration/compose.windows.yaml stop
```

Nếu dùng `--project` khác, thay tên tương ứng. Không dùng `down -v`: lệnh đó xóa dữ liệu. `restart: unless-stopped` giúp dịch vụ mở lại khi Docker khởi động; vẫn cần Docker/WSL tự chạy sau reboot và Cốc Cốc/desktop hoạt động để tạo ảnh.

Các cổng mặc định đều giới hạn trên loopback:

| Cổng | Dịch vụ |
| --- | --- |
| 5173 | Giao diện |
| 58080 | API |
| 58110 | Broker tiện ích ảnh |
| 55432 | PostgreSQL |
| 56379 | Redis |
| 59000 / 59001 | MinIO / console |

Ban đầu truy cập bằng desktop/RDP của máy Windows. Không mở công khai DB, Redis, MinIO console hoặc broker. Nếu cần domain/HTTPS, AI cần cấu hình reverse proxy, origin, callback ChatGPT/Meta và cookie phù hợp với địa chỉ triển khai thật.

## 7. Phạm vi đã kiểm tra

Bộ image đã build thành công cho AMD64. Bản sao đã kiểm tra checksum, cấu trúc dump/archives và ảnh so với metadata. App Mac đã được khởi động lại. Chưa chạy restore trên một máy Windows thật trong lần bàn giao này; AI máy mới cần đọc `AI_HANDOFF.md` và thực hiện các bước khôi phục ở trên.
