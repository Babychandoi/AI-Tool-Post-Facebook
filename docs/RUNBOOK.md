# Runbook

## Đăng bị chặn

Mở Vận hành/Lịch, đọc lỗi đã che secret. Kiểm tra brand pause, approval policy/version/expiry, nguồn, deadline và quyền của người tạo lịch. Nếu sửa bài, tạo revision mới rồi duyệt. Lịch nội bộ BLOCKED có thể hủy; lập lịch mới sau khi xử lý nguyên nhân. Không sửa trực tiếp snapshot bằng SQL.

## UNKNOWN hoặc lease hết hạn khi DISPATCHING

1. Pause thương hiệu để chặn dispatch tiếp theo.
2. Lưu publication ID, operation key, attempts, thời gian và provider request ID. Không retry hoặc đổi intent để né UNKNOWN.
3. Kiểm tra Page. Nếu có bài đúng, nhập `PageID_PostID` tại “Đối soát”. Backend xác minh Page/message qua Graph; operator cần kiểm tra ảnh và thời gian vì Meta không cho đối chiếu checksum PNG ở đây.
4. Không tìm được bằng chứng thì giữ UNKNOWN. Chỉ xử lý lại sau khi xác minh chắc chắn provider chưa nhận; phiên bản MVP không có nút “force retry” cho UNKNOWN.
5. Ghi bằng chứng vận hành rồi mở lại brand khi các lịch tương tự đã an toàn.

## Token mất quyền

Kết nối lại bằng Page token hợp lệ thuộc app đã cấu hình. Không đưa token vào log/issue/screenshot. AES-GCM cần cùng encryption key với lúc lưu token. Cấu hình Graph version/phạm vi quyền phải được xác minh trên app thực. Token revoked trả BLOCKED, không retry vô hạn.

## 429 / outage

429 dùng Retry-After khi parse được, exponential backoff + jitter, cap 5 attempts và deadline 1 giờ sau due. 5xx/timeout khi POST chuyển UNKNOWN. Metrics là read-only; job lỗi hiện xuất hiện FAILED để vận hành kiểm tra, không tạo số 0 thay cho thiếu dữ liệu.

## Nguồn hết hạn / policy đổi

Nguồn hết hiệu lực không được dùng ở workflow hoặc dispatch. Thay brand config tăng policy_version, approval cũ không qua gate. Người duyệt cần đọc body/ảnh/evidence; heuristic risk flags không thay thế fact-check. Không bật model autopilot khi chưa có eval.

## Jobs / cadence

Worker claim bằng SKIP LOCKED; heartbeat 20 giây, lease 120 giây. Worker mất lease không được commit kết quả. Run AI đang chạy hết lease bị fail và quyết toán khoản giữ trước vì có thể provider đã tính tiền. Daily campaign mặc định tắt; bật trong giao diện, một run/ngày theo timezone; pause hoặc thu hồi quyền creator chặn run mới.

## Docker filesystem chỉ đọc

Không xóa volume hoặc prune toàn bộ Docker để tự sửa. Giải phóng dung lượng bằng thao tác người vận hành xác nhận, kiểm tra Docker Desktop rồi khởi động lại engine khi không ảnh hưởng workload khác. Build lại `docker compose up -d --build` và kiểm tra health. Có thể dùng `scripts/run-local.sh` cho pilot macOS Apple Silicon trong lúc phục hồi Docker.

## Backup / restore

Production cần backup PostgreSQL/PITR và S3 version/lifecycle từ hạ tầng đã chọn. Mẫu dump cho Compose:

```bash
mkdir -p .runtime/backups
docker compose exec -T postgres pg_dump -U marketing -Fc marketing > .runtime/backups/marketing.dump
```

Giữ encryption key ngoài bản dump ở nơi an toàn; không lưu khóa vào repo. Dump đơn lẻ không chứng minh RPO 15 phút. Chưa có restore drill hoặc RTO đo được.

Sau restore, giữ `LIVE_PUBLISHING=false`, pause brand và đối soát các publication có thể đã gửi sau thời điểm backup. Khi đã đối soát, kiểm tra overdue/deadline trước mở publisher. Không mặc định READY nghĩa là remote chưa nhận.

## Production

Dùng DB mới, tắt DEMO_MODE, IdP OIDC với email verified, membership đã provision, HTTPS/Secure cookie, S3 private và credentials riêng. Session API hiện trong process; scale API cần sticky session hoặc bổ sung Spring Session. Chốt retention/delete cho contact, MFA, quyền S3 least privilege và ngân sách provider trước nhập lead thật. Không mở các port DB/Redis/S3 console ra Internet bằng Compose mẫu.

## ChatGPT local

Mở Kết nối AI để chọn tài khoản đã đăng ký hoặc thêm mới. Callback bắt buộc HTTP loopback trên `127.0.0.1`, đường `/v1/ai/chatgpt/callback`; cấu hình cùng port dashboard trong `.env`. Khóa `TOKEN_ENCRYPTION_KEY` cần giữ qua restart/backup; credentials mã hóa nằm trong PostgreSQL local. Host ID lưu tại `chatgpt_host`, không tạo mới khi restart.

`PLAN_DISABLED`: bấm Cho phép dùng hạn mức, xác nhận lại trên OpenAI. `REAUTH_REQUIRED`: kết nối lại registration cũ; không cấp client mới cho cùng tài khoản theo thói quen. Gia hạn token được serialize bằng row lock; lỗi terminal refresh xóa token không dùng được, giữ subject/client ID. Lỗi mạng tạm thời giữ credentials. Không copy token từ Codex, browser cookie hoặc log.

429/hạn mức: mở Quản lý hạn mức; không suy đoán thời điểm reset và không tự đổi sang API key. 403 có thể do tài khoản/workspace/khu vực hoặc capability không được hỗ trợ; đăng nhập lại liên tục không khắc phục eligibility. Stream thiếu `response.completed` không được tạo draft thành công. Tác vụ đã xếp hàng cố định connection/model; đổi tài khoản hoặc ngắt kết nối chặn các tác vụ cũ dùng connection đó. Lần inference đã được OpenAI nhận có thể vẫn dùng hạn mức khi bạn ngắt kết nối.

Ngắt kết nối sẽ thử revoke refresh session rồi xóa credentials local, giữ mapping cho lần đăng nhập sau. Nếu không xác nhận revoke do lỗi mạng, UI báo rõ; vào ChatGPT Settings → Usage để ngắt quyền từ phía OpenAI. App session và phiên ChatGPT là hai phiên riêng: đăng xuất dashboard không tự thu hồi grant dùng cho worker.

Đường ChatGPT dùng hạn mức gói hoặc credits bạn cho phép trong ChatGPT; ledger ngân sách API không tính khoản USD giả cho đường này. Smoke script từ chối chạy khi có active ChatGPT connection hoặc API key thật để tránh dùng hạn mức trong kiểm thử.


## Đăng tự động

- Mở `/#autopost`, lưu một lịch/thương hiệu. Lưu hoặc tạo bài thử để lịch tắt. Bật lịch bằng thao tác riêng sau khi các điều kiện đã đủ. Người lưu sở hữu tài khoản ChatGPT dùng cho lịch; chỉ Owner/Admin có thể thay thiết lập.
- Đăng thật cần `META_APP_ID`, `META_APP_SECRET`, `META_GRAPH_VERSION`, `TOKEN_ENCRYPTION_KEY` và `LIVE_PUBLISHING=true` trên API/worker; người dùng vẫn phải chọn Page META, xác nhận consent và bật lịch trong UI. Không tự sửa env bằng nút bật lịch. Page ID/token được xác minh qua app và quyền. Kết nối chưa đủ hiển thị trong màn thiết lập.
- Ảnh TEMPLATE không gọi image provider. Ảnh OPENAI cần key nhập ở UI (AES-GCM) hoặc key máy chủ, endpoint cố định `/v1/images/generations`; không gửi ChatGPT OAuth token sang Images. Phản hồi phải chứa PNG 1024px hợp lệ, giữ nguyên bytes + checksum; từ chối JPEG, sai kích thước, thiếu output và body quá lớn; không fallback template hay retry khi chưa biết phí. Budget settle bảo thủ bằng khoản giữ dự trù nếu không có giá usage đã xác minh.
- Kiểm tra bảng trạng thái trên UI: thiếu nguồn/AI/Page/ngân sách/consent/policy thì không tạo run. Worker tìm slot tương lai trong 30 phút; unique `autopost:<config>:<local date>` ngăn nhiều worker tạo trùng. Không backfill ngày đã qua.
- Risk flag / yêu cầu người duyệt riêng: review bài. Khi còn config đúng version, lịch bật và slot chưa vượt một giờ, approval người duyệt tự nối sang publication. Quá giờ giữ bản nháp đã duyệt để lập lịch mới thủ công.
- Đổi config/stop hủy QUEUED/RETRY_WAIT/BLOCKED; DISPATCHING/UNKNOWN/PUBLISHED giữ để đối soát, không tuyên bố đã thu hồi. Race với dispatch được kiểm tra lại dưới brand/config lock; request đã qua preflight có thể đang gửi khi người dùng tắt.
- Tác vụ preview có `autopost_preview=true`, không tạo publication kể cả sau approval. Nhật ký AUTOPOST_* trong Vận hành. Không enable lịch thật trong smoke/test.
