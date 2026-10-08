# Bàn giao cho AI triển khai trên máy mới

## Mục tiêu được giao

Khôi phục bản sao dự án AI Automatic Post Facebook từ Mac sang Windows, giữ đầy đủ dữ liệu và kết nối. Đọc `../README.md`, `MIGRATE_TO_WINDOWS.md`, `compose.windows.yaml` và `restore.py` trước khi thao tác. Thư mục gói có `START_HERE.md`, `MANIFEST.json` và script chạy ở cấp trên thư mục `project`.

## Trạng thái ở thời điểm backup

- Snapshot: 08/10/2026 khoảng 11:23 giờ Việt Nam; nguồn Docker ARM64 trên Mac.
- 33 bảng PostgreSQL; 1 brand, 3 knowledge documents, 8 content items/revisions, 8 assets, 3 publication records. Chi tiết chính xác trong `inventory/snapshot.json`.
- Fanpage mục tiêu: Công ty TNHH Poip Legal, ID `675256752343426`; brand `25796d97-64a2-4c3d-a79b-331fca560ece`.
- AUTO đã chọn nhưng lịch tắt, live publishing tắt. 5 workflow chờ duyệt, 12 workflow lỗi lịch sử; không tạo lại hoặc xóa chúng trong migration.
- Đã chứng minh tạo ảnh tự động bằng thao tác “Tạo bài thử” duy nhất trong app. Không cần AI gõ prompt trong Cốc Cốc. Bằng chứng trong archive `legacy-runtime/mac-native-runtime.tar.gz` ở `verification/poip-app-only-image-result.json` và ảnh preview tương ứng.
- Image extension source ở `project/tools/chatgpt-web-images/extension`, bản 0.1.1, ID dự kiến `gedjjpmaogkijdpblbekoofeahdbmiih`.
- Backend JAR/nginx/giao diện trong `project/migration/artifacts` lấy từ container đang chạy, không phải bản build nguồn chưa đối chiếu.

## Việc cần làm

1. Xác định Windows 10/11 hay Windows Server và CPU AMD64/ARM64. Docker phải chạy Linux containers. Windows Server dùng WSL/Ubuntu + Docker Engine hoặc môi trường Linux phù hợp; không cài Docker Desktop như thể Server được hỗ trợ.
2. Giữ gói ở đường dẫn cố định. Kiểm tra SHA256 ZIP và chạy `project/migration/restore.py --verify-only` trên gói nguyên.
3. Chạy `restore.py` bằng Python >= 3.10 trong môi trường có Docker CLI; default project `ai-marketing-windows`. Image AMD64 đã có sẵn. Không rebuild backend/frontend từ đầu nếu chỉ cần chuyển máy.
4. Script tự tạo volume mới, dùng logical dump PostgreSQL, khôi phục kho ảnh/Redis/broker và so số dòng. Không giải nén dữ liệu PostgreSQL thô của Mac vào cluster Windows.
5. Đọc `restore-state.json`, kiểm tra dịch vụ, mở app 5173 và đối chiếu thương hiệu/dữ liệu/ảnh. Nếu người dùng chưa yêu cầu chạy thử sinh ảnh thì chỉ kiểm tra dữ liệu đã có; không tự đăng bài thật.
6. Hướng dẫn người dùng đăng nhập ChatGPT trực tiếp trong Cốc Cốc trên Windows. Cài tiện ích unpacked đúng thư mục, tạo mã trong app và ghép lại. Không hỏi mật khẩu trong chat hoặc lấy cookie/profile.
7. OAuth writer và Meta token đã lưu; nếu hết hạn thì dùng luồng đăng nhập/kết nối chính thức của app. Giữ nguyên khóa mã hóa và App Secret.
8. Báo rõ kết quả, vấn đề còn lại, đường dẫn cấu hình. Khi chủ dự án yêu cầu chuyển hẳn, phối hợp dừng worker máy Mac trước khi bật worker/lịch có quyền đăng ở Windows.

## Các ràng buộc cần giữ

- Không in/copy bí mật vào câu trả lời. `.env`, `inventory/source.env`, `inventory/source-compose-private.json`, dump và kho dữ liệu đều riêng tư.
- Không đổi `TOKEN_ENCRYPTION_KEY`; không thay database bằng demo seed. Seed hiện tại nhận ra workspace đã có và bỏ qua.
- Không `docker compose down -v`, xóa volume, ghi đè dữ liệu đang tồn tại, chạy lại job lịch sử hoặc tự reset trạng thái bản nháp.
- Giữ live publishing và lịch tắt trong migration. Việc khôi phục không phải yêu cầu đăng bài hay duyệt nội dung pháp lý.
- Không chạy test suite hoặc tạo dữ liệu thử nếu chủ dự án chưa yêu cầu. Kiểm tra checksum/khôi phục và số dòng là phần cần thiết của migration.
- Chỉ một host chính chạy lịch/worker sau chuyển hẳn. Có thể cần sao lưu lại nếu nguồn đã phát sinh dữ liệu sau 11:23 ngày 08/10/2026.
- Broker 58110 giữ loopback. Trình duyệt desktop đang đăng nhập phải hoạt động để route ChatGPT Web tạo ảnh được; server headless không đủ.
- Nếu restore lỗi, đọc lỗi, giữ volume hiện tại và xử lý nguyên nhân. Script từ chối ghi đè là chủ ý bảo vệ dữ liệu.

## Các nguồn hướng dẫn chính thức

- [Docker Desktop Windows](https://docs.docker.com/desktop/setup/install/windows-install/)
- [WSL trên Windows Server](https://learn.microsoft.com/en-us/windows/wsl/install-on-server)
- [Docker Engine Ubuntu](https://docs.docker.com/engine/install/ubuntu/)
- [Mạng WSL](https://learn.microsoft.com/en-us/windows/wsl/networking/)
