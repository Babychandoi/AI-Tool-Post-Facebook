# Kiểm chứng ngày 03/10/2026

Đã kiểm tra trên macOS Apple Silicon, Amazon Corretto JDK 21, Spring Boot 3.5.16 và PostgreSQL native 16.11. Đồng thời đã build và chạy Docker Compose với PostgreSQL 16.15, Redis, MinIO, API, worker và frontend Nginx. API và worker chạy riêng.

## Kết quả

- **53 kiểm thử backend đạt**, không skip: 16 provider/media/security/OAuth/SSE/image contract tests và 37 integration tests trên PostgreSQL thật. Có 14 integration tests mới cho lịch đăng tự động.
- **7 kiểm thử browser với API fixture đạt**: dashboard desktop/mobile, CSRF + snapshot hash khi duyệt, idempotency khi generate, thiếu metrics không bị bịa thành 0; account catalog và lưu model ChatGPT có CSRF; thiết lập tập trung, preview không bật đăng, bật/tắt riêng, API tạo ảnh và consent/server gate hiển thị rõ.
- **1 kiểm thử browser live đạt trên native và Docker**: đăng nhập Spring Security, dashboard có dữ liệu bền vững, PNG có naturalWidth 1080, lead từ smoke và publication PUBLISHED hiển thị đúng. Thêm một browser test live cho màn Kết nối AI và auth URL PKCE chính thức; request tới OpenAI được chặn trong test trước khi đăng nhập. Lần chạy cuối gồm đủ 10 browser tests, không skip: 7 fixture và 3 live qua dashboard Docker tại port 5173. Test preview live dùng workspace/Owner QA riêng, không liên kết ChatGPT và không có key API, để không dùng hạn mức tài khoản thật của người dùng. Khi tài khoản thật đang kết nối, browser test OAuth chặn endpoint model catalog tại trình duyệt và URL OpenAI để không gọi provider.
- **Smoke vòng khép kín đạt trên native và Docker**: API tạo asynchronous run → worker tạo draft/image → review approval → schedule idempotent → worker DEMO PUBLISHED → public form tracking → lead EXACT → phân công Sales. Bản Docker đi qua Nginx, PostgreSQL/Redis và kho PNG S3 thật. Số bài gửi Facebook thật: 0.
- Frontend TypeScript + Vite production build đạt. Backend JAR build trên JDK 21 đạt. Migration V1–V8 được áp dụng trong DB test.

## Các tình huống backend đã kiểm tra

Tenant access bị chặn; Analyst không có quyền viết; session/CSRF bắt buộc; revision bất biến; sửa tạo bản mới không kế thừa approval; policy đổi làm approval không hợp lệ; Idempotency-Key lặp chỉ tạo một run/job; timeout không retry; pause chặn dispatch; worker hết lease không commit và DISPATCHING trở thành UNKNOWN; hai worker không claim cùng job; HMAC raw-body và replay dedup; ngân sách giữ trước atomic; intent mới không né UNKNOWN; policy hai người; daily trigger idempotent và honor pause; nguồn hết hiệu lực bị từ chối. Contract tests còn kiểm tra 429 Retry-After, revoked token, post_id thiếu, AES-GCM random nonce/wrong key và PNG decode.

Các kiểm thử ChatGPT còn xác minh JWT bằng chữ ký RSA thật với sai signature/issuer/audience/nonce/expiry bị chặn; callback state một lần và issued client ID; token không lộ trong status API; người dùng khác không truy cập connection; scope thiếu chặn generation; hai worker chỉ refresh một lần và lưu rotating token; terminal refresh giữ mapping và xóa token; workflow pin provider/model/owner và ghi CHATGPT_PLAN; stream EOF/partial/incomplete/usage-limit không thành công.

## Luồng đăng tự động đã kiểm tra

Owner/Admin + CSRF + tenant enforcement; settings optimistic version và image key ciphertext không trả về UI; consent/AI/Page thật/server switch bắt buộc; ngân sách ảnh thiếu chặn trước gọi provider. Hai worker daily đồng thời chỉ tạo một run; slot ngoài cửa sổ hoặc đã qua không được tạo bù. DEMO daily tự nối approval → publication → DEMO PUBLISHED, không gọi Meta. AUTO dùng ChatGPT response giả lập → chốt snapshot → Meta mock xác nhận đúng một lần. REVIEW và risk flag chờ reviewer, approval nối publication; preview không có publication kể cả khi được duyệt. Stop hủy pending và thu hồi rule approval, config đổi trong lúc gọi AI không ghi nháp/lịch. Hai người chặn tự duyệt. Image riêng giữ ngân sách ngay cả khi text dùng ChatGPT, checksum/metadata/bytes bất biến; PNG sai định dạng/kích thước/bị hỏng/quá lớn bị từ chối.

Browser live đã dùng màn Đăng tự động tạo bản nháp và PNG 1080px qua Nginx/API, PostgreSQL/Redis, worker và MinIO thật; ảnh và nội dung xuất hiện trong modal xem trước trên cùng màn. Lịch QA giữ disabled, chỉ dùng provider DEMO. Desktop/mobile không tràn ngang. Tài khoản và dữ liệu DB của workspace QA được dọn sau kiểm thử; screenshot và log được giữ làm bằng chứng. Không có publication META hoặc bài gửi Facebook thật.

## Giới hạn bằng chứng

Meta/S3 được mock trong integration để không gọi provider; smoke native dùng private filesystem, smoke Docker dùng MinIO thật. Cả hai dùng DEMO publishing adapter. Không có token Meta/OpenAI nên chưa kiểm chứng live publish, App Review, quyền tài khoản, fact correctness của model hoặc chi phí provider thật. Kết nối và inference ChatGPT được mock ở các test nghiệp vụ; Tài khoản người dùng đã đăng nhập trong ứng dụng, DB ghi CONNECTED và đã chọn model; kiểm thử không gọi inference bằng tài khoản thật hoặc dùng credits của người dùng. Images API cũng chưa gọi bằng key thật. Browser live chỉ kiểm tra tới ranh giới auth URL và không gọi provider. Chưa load test, backup restore drill, RPO/RTO hoặc nghiệm thu production.

Docker host từng chuyển filesystem read-only trong lần build đầu. Sau khi host hoạt động trở lại, đã build đủ image, chạy đủ sáu service và smoke thành công; dữ liệu demo lưu trong named volumes. Dashboard Docker dùng port 5173, API 58080; profile portable là lựa chọn dự phòng.

Log build/test và smoke bàn giao ở `.runtime/verification/`; report JUnit ở `backend/target/surefire-reports`; screenshot/browser report ở `frontend/test-results`. Các tệp này được gitignore vì là dữ liệu chạy, không phải source.
