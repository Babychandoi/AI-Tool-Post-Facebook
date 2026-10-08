# Backup AI Automatic Post Facebook — 08/10/2026

Đây là gói chuyển máy gồm dữ liệu, mã nguồn, cấu hình, file chạy và Docker images **Linux AMD64** để triển khai trên Windows Intel/AMD qua Docker Linux containers.

## Bắt đầu

1. Giải nén toàn bộ vào thư mục cố định, ví dụ `C:\AI-Marketing`. Giữ kín gói vì có token/App Secret/khóa mã hóa.
2. AI triển khai đọc **[AI_HANDOFF.md](project/migration/AI_HANDOFF.md)** và **[MIGRATE_TO_WINDOWS.md](project/migration/MIGRATE_TO_WINDOWS.md)**.
3. Cài Docker Linux containers và Python >= 3.10 theo đúng Windows 10/11 hoặc Windows Server trong hướng dẫn.
4. Trong PowerShell ở thư mục này:

```powershell
py -3 .\project\migration\restore.py --verify-only
py -3 .\project\migration\restore.py
```

Trong WSL dùng `python3 project/migration/restore.py` tương ứng. Nếu máy ARM64 cần `--build-from-artifacts` và Internet.

5. Mở http://127.0.0.1:5173, đăng nhập app, cài tiện ích `project/tools/chatgpt-web-images/extension` trong Cốc Cốc, đăng nhập ChatGPT và ghép mã mới trong app.

Toàn bộ dữ liệu đã bấm lưu được mang theo; ảnh nằm trong backup MinIO. Phiên đăng nhập ChatGPT của trình duyệt cần đăng nhập lại. Lịch và đăng Facebook thật được giữ tắt khi khôi phục. App Mac đã được mở lại sau snapshot; không bật lịch đồng thời trên hai máy.

`Restore-Windows.ps1` khôi phục lần đầu; `Start-Windows.ps1` / `Stop-Windows.ps1` mở/dừng sau đó. Không xóa Docker volumes và không đổi `TOKEN_ENCRYPTION_KEY`.

Chi tiết snapshot: `inventory/snapshot.json`. Tính toàn vẹn: `MANIFEST.json`, `SHA256SUMS.txt`; checksum ZIP nằm ở file `.sha256` bên cạnh ZIP. Image AMD64 đã build; khôi phục trên máy Windows thật sẽ thực hiện ở máy đích.
