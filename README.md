# AI-Tool-Post-Facebook

MVP theo `Kien_truc_he_thong_AI_Marketing_v1.md`: dashboard React/TypeScript, API và worker Spring Boot/JDK 21, PostgreSQL, Redis, kho ảnh S3, OpenAI Responses qua API key hoặc tài khoản ChatGPT và Meta Page adapter.

**Đã chạy vòng khép kín tại local:** đăng nhập → nguồn kiến thức → bản nháp + ảnh PNG → duyệt revision → lập lịch → publication thử nghiệm → form lead có nguồn → phân công Sales. Adapter demo không gửi bài lên Facebook và không tạo số tương tác giả.

## Mã nguồn và chuyển máy

Repository chứa mã nguồn, cấu hình mẫu và tiện ích tạo ảnh. `.env`, token, dữ liệu PostgreSQL/MinIO, profile trình duyệt và bản backup riêng không được đưa lên GitHub. Clone repository tạo môi trường mới; để giữ dữ liệu hiện tại khi chuyển máy cần gói backup riêng.

Hướng dẫn chuyển sang Windows: [MIGRATE_TO_WINDOWS.md](migration/MIGRATE_TO_WINDOWS.md). AI triển khai đọc [AI_HANDOFF.md](migration/AI_HANDOFF.md). Các script khôi phục trong `migration/` cần chạy từ **gói backup đầy đủ**, nơi có dump, Docker images, artifacts và `MANIFEST.json`; chỉ clone repository chưa đủ để khôi phục dữ liệu cũ.

## Dùng ngay trên máy này

Dashboard: <http://127.0.0.1:5173/>. Tài khoản `owner@demo.local`, mật khẩu `demo-marketing`.

Các tài khoản `marketer`, `reviewer`, `publisher`, `sales`, `analyst` tại `@demo.local` dùng cùng mật khẩu demo. Backend kiểm tra vai trò và phạm vi thương hiệu; Analyst không thấy contact.

Chạy lại local trên macOS Apple Silicon:

```bash
./scripts/run-local.sh
```

Script dùng JDK 21 tại `JAVA_HOME`, hoặc bản đã kiểm tra checksum trong `.runtime/amazon-corretto-21.jdk/Contents/Home`. Nhấn Ctrl+C để dừng các tiến trình được script khởi tạo. Dữ liệu nằm tại `.runtime/postgres` và ảnh riêng tư tại `.runtime/media`; giữ thư mục này nếu muốn giữ demo. Không chạy hai bản API native đồng thời trên port 55433.

Profile `portable` dùng PostgreSQL 16 native, filesystem riêng tư và rate limit trong tiến trình. Profile này chỉ cho phép demo và bind loopback; đây là cách chạy local khi Docker Desktop lỗi, không phải topology production.

## Docker Compose

Cần Docker Compose và đủ dung lượng cho image/build cache. Đã build và khởi động đủ sáu service trên Docker Desktop; đường chạy native cũng đã được kiểm chứng.

```bash
./scripts/setup-chatgpt.sh
# Tạo .env nếu chưa có; giữ khóa mã hóa cũ hoặc tạo khóa mới, không in khóa ra màn hình.
docker compose up -d --build
```

Các port chỉ bind `127.0.0.1`: dashboard 5173, API 58080, PostgreSQL 55432, Redis 56379, S3 59000, console S3 59001. API và worker dùng cùng image, chạy thành hai tiến trình. Migration Flyway chạy tự động. Không dùng `down -v` nếu cần giữ dữ liệu.

MinIO community hiện là source-only và repository đã archive. Compose đã build thành công từ commit cố định `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a` của release 2025-10-15. Production nên dùng dịch vụ S3 được duy trì và cấu hình endpoint/credentials tương ứng. [Nguồn MinIO chính thức](https://github.com/minio/minio).

## Đăng tự động hằng ngày

Mở **Đăng tự động** trong menu hoặc <http://127.0.0.1:5173/#autopost>. Bốn bước ở cùng một màn:

1. Kết nối ChatGPT và chọn model; chọn Fanpage hoặc kết nối Page ID/token ngay tại đây.
2. Nhập mục tiêu, mỗi dòng một chủ đề; nhập và xác nhận thông tin thực tế về thương hiệu để làm nguồn.
3. Chọn ảnh mẫu thương hiệu hoặc ảnh AI qua OpenAI Images API. ChatGPT plan usage chưa hỗ trợ image generation; API key cho ảnh được mã hóa riêng và không trả về trình duyệt. Ảnh AI 1024 × 1024 PNG, model chọn được, chất lượng low, phí API riêng. Khoản dự trù mỗi ảnh giữ trong ngân sách tháng là ước tính, không là giới hạn hóa đơn OpenAI. Xem [giới hạn ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations) và [Images API](https://developers.openai.com/api/docs/guides/image-generation).
4. Chọn giờ và cách chạy: **Duyệt trước khi đăng**, **Chạy thử toàn bộ** (Page DEMO), hoặc **Tự đăng lên Facebook** (Page META + AI thật). Lưu thiết lập và Tạo bài thử luôn tắt lịch; bài thử không bao giờ tự lên lịch, kể cả khi được duyệt. Xem bài và ảnh ngay trên màn này. Chỉ nút **Bật lịch hằng ngày** bật tự động; lịch thật còn cần xác nhận cho phép đăng và máy chủ `LIVE_PUBLISHING=true`.

Worker kiểm tra mỗi 30 giây, chuẩn bị bài trong 30 phút trước slot theo timezone thương hiệu, tối đa một run/ngày/lịch. Chủ đề luân phiên theo ngày, AI dùng mục tiêu chiến dịch, giọng văn và nguồn đã duyệt để viết hook/body/CTA. Khi tự đăng đã bật, bài không có risk flag được ghi approval gắn config version + policy + hash rồi vào lịch tự động. Bài pháp lý/giá/ưu đãi/thiếu nguồn hoặc chính sách hai người vẫn cần review; chế độ review đưa bài vào lịch sau khi người có quyền duyệt. Slot quá hạn một giờ không tự đăng bù. Máy chủ/worker phải chạy; khởi động sau giờ đăng không tạo bài bù cho ngày cũ.

Sửa/tắt thiết lập tăng version và hủy lịch còn chờ. Worker kiểm tra lại quyền, config/version, consent, Page, policy, nguồn và checksum trước gửi. Tắt không thể thu hồi request đang gửi hoặc bài đã đăng; UNKNOWN cần đối soát tại Lịch nội dung. Tài khoản ChatGPT của người lưu thiết lập dùng cho lịch; provider/model cố định từng run, không chuyển sang API tính phí khi ChatGPT lỗi. Thiết lập ban đầu chưa bật, không tự đổi cấu hình đăng thật phía máy chủ.

## Sử dụng

1. Thương hiệu & tri thức: cấu hình voice, khách hàng mục tiêu, ngân sách; thêm nguồn rồi duyệt nguồn.
2. Chiến dịch: nhập mục tiêu, trụ cột. Có thể bật tạo **một bản nháp mỗi ngày** theo giờ thương hiệu; run theo ngày chống trùng. Luồng API giữ ngân sách trước khi gọi AI; ChatGPT dùng hạn mức tài khoản của người bật chiến dịch.
3. Content Studio: tạo bản nháp, hoặc viết thủ công. Ảnh 1080 × 1080 được render từ template tiếng Việt. Chỉnh sửa tạo revision mới; revision cũ vẫn được truy vết.
4. Hàng đợi duyệt: mở ảnh, nội dung, nguồn và flags; duyệt hoặc yêu cầu sửa. Có thể bật yêu cầu người duyệt khác người sửa cuối.
5. Mở bài đã duyệt trong Content Studio, chọn “Lên lịch bản đã duyệt”, Page và giờ đăng trong 6 ngày tới. Gate kiểm tra lại policy, nguồn, deadline và quyền người dùng khi worker dispatch.
6. Khách hàng: chia sẻ form công khai. Link theo từng bài có tracking key và ghi nguồn EXACT; form không có referral ghi UNKNOWN. Đồng ý được ghi nhận tại máy chủ.
7. Vận hành: xem jobs, lease, attempts, audit. UNKNOWN phải đối soát; API chặn tạo lịch mới cùng content/Page khi còn kết quả chưa xác định.

ASSISTED là chế độ mặc định. RULED_AUTOPILOT hiện chỉ tự duyệt **template trích nguồn trong demo** đạt rule; output AI tạo thủ công vẫn cần người duyệt. Luồng Đăng tự động có cơ chế cấp quyền riêng như mô tả ở trên. Hệ thống không tự gửi inbox hoặc thay policy.

## Bật tích hợp thật

**Tạo ảnh ChatGPT Web thử nghiệm bằng Cốc Cốc:** chạy `./scripts/run-docker.sh` để khởi động ứng dụng và cầu nối ảnh cùng nhau. Script tạo hoặc giữ token cầu nối trong `.env` (quyền file 600). Nếu dùng native, chạy `./scripts/setup-image-connector.sh` một lần rồi `./scripts/run-local.sh`; cầu nối cũng khởi động cùng ứng dụng.

Cài một lần tiện ích đã giải nén từ `tools/chatgpt-web-images/extension` vào Cốc Cốc tại `chrome://extensions/`. Tiện ích chỉ xin quyền trên `chatgpt.com`, `*.oaiusercontent.com` và cầu nối local `127.0.0.1:58110`. Trong **Đăng tự động → Ảnh cho bài viết**, chọn **ChatGPT Web · Thử nghiệm**, bấm **Tạo mã kết nối Cốc Cốc**, nhập mã 6 chữ số vào tiện ích và bấm **Kết nối**. Mã hết hạn sau 2 phút và dùng một lần; token tiện ích riêng được lưu trong trình duyệt, máy chủ chỉ giữ hash. Đăng nhập ChatGPT bằng chính phiên Cốc Cốc này. Giao diện ứng dụng chỉ báo sẵn sàng khi nhận heartbeat và ChatGPT có ô soạn tin.

Mỗi tác vụ tạo ảnh mở và chọn một tab ChatGPT mới trong phiên đã đăng nhập, tự chọn công cụ Tạo ảnh, gửi prompt đúng một lần và trả ảnh về ứng dụng. ChatGPT cần tab đang hoạt động để tải đủ menu và ảnh. Tab thành công được đóng, trở về tab trước nếu bạn chưa chuyển sang tab khác; tab lỗi được giữ để kiểm tra. Không cần nhập prompt hoặc tải ảnh từng bài. Giữ Cốc Cốc, Docker và máy chạy. Nếu thiếu đăng nhập, hết hạn mức, giao diện thay đổi hoặc không nhận ảnh trong khoảng 5 phút, tác vụ dừng, không tự gửi lại prompt và không chuyển sang API trả phí. Hạn mức tài khoản ChatGPT vẫn áp dụng.

Ảnh ChatGPT Web dùng cách chạy đã chọn: duyệt trước, chạy thử hoặc tự đăng. Bài thử luôn là bản nháp; tự đăng vẫn giữ kiểm tra nguồn, risk flags, quyền, consent và chính sách người duyệt riêng. Phong cách ảnh được lưu trong ứng dụng. Chỉ tiêu đề được cung cấp có thể xuất hiện trên ảnh; không thêm thông tin liên hệ hoặc cam kết pháp lý. PNG/JPEG được giữ toàn bộ bố cục khi đặt vào PNG vuông 1024×1024. Cầu nối Docker dùng service nội bộ `image-bridge`; cổng host chỉ bind 127.0.0.1. Có thể chạy phiên riêng cũ bằng `CHATGPT_WEB_IMAGE_MODE=DEDICATED_BROWSER ./scripts/run-chatgpt-web-images.sh`, nhưng luồng mặc định dùng tiện ích Cốc Cốc.

Đã kiểm tra luồng tự động thực tế ngày 08/10/2026: bài thử của POIP LEGAL nhận ảnh PNG 1024×1024 với `generation_mode=BROWSER_EXTENSION`, không nhập prompt hoặc tải ảnh thủ công. Bản nháp có nội dung pháp lý nên chờ duyệt. Bằng chứng local: `.runtime/verification/poip-automatic-image-preview.jpg` và `poip-automatic-image-result.json`. Lịch hằng ngày và chế độ đăng thật vẫn đang tắt.

Đã tham khảo [miuuyy/codex-chatgpt-web](https://github.com/miuuyy/codex-chatgpt-web) tại commit `92a356fac2292e3af5a97ab7ba634edd8d38621e`. Cầu nối riêng trong `tools/chatgpt-web-images` dùng giao diện ChatGPT. Tiện ích không trích xuất cookie/mật khẩu và không sử dụng hoặc sửa credentials Codex.

**Dùng tài khoản ChatGPT đang trả phí:** mở **Kết nối AI → Continue with ChatGPT**, đăng nhập trên OpenAI, chọn workspace và cho phép dùng hạn mức. Khi quay lại, chọn model từ danh sách tài khoản rồi **Lưu model**. Không cần `OPENAI_API_KEY` cho đường này. Tác vụ mới cố định connection/model theo người khởi tạo. Tài khoản và workspace ChatGPT được lưu tách theo issued client ID; có nút đổi, kết nối lại và ngắt từng tài khoản.

Ứng dụng này dùng flow dành cho dự án cá nhân chạy local. Khả năng dùng gói tùy tài khoản Plus/Pro, workspace, khu vực, model và quyền do OpenAI quyết định; ứng dụng trả phí hoặc hosting từ xa cần quyền tương ứng. [Tài liệu OpenAI chính thức](https://developers.openai.com/siwc/token-sharing-open-source).

`CHATGPT_ENABLED=true`, `CHATGPT_REDIRECT_URI=http://127.0.0.1:5173/v1/ai/chatgpt/callback` và khóa `TOKEN_ENCRYPTION_KEY` ổn định là cấu hình máy chủ local. Callback phải dùng `127.0.0.1`, không đổi sang `localhost`; nếu đổi port web, cập nhật cả URI. Backend dùng PKCE/state/nonce, xác minh JWT bằng JWKS và scope, mã hóa credentials trong DB local, giữ host ID qua restart, serialize rotating refresh token bằng khóa PostgreSQL giữa API/worker. Trên native, truyền các biến này trong môi trường khi chạy script.

ChatGPT gọi public Responses với `store:false`, `stream:true`, chỉ chấp nhận `response.completed`. Không dùng cookie hay token từ Codex. Lỗi quyền/hạn mức dừng tác vụ, không tự chuyển sang API key. Usage token được ghi với `CHATGPT_PLAN`; ngân sách API trên dashboard không đại diện cho hạn mức gói ChatGPT, xem [Quản lý hạn mức ChatGPT](https://chatgpt.com/settings/usage). OpenAI có thể dùng credits nếu bạn cho phép trong cài đặt. Chưa xác minh inference trên tài khoản thật cho đến khi bạn đăng nhập và cấp quyền.

- OpenAI: cấu hình `OPENAI_API_KEY`, `OPENAI_MODEL`, giá input/output bằng micro-USD trên 1 triệu token và khoản giữ trước `GENERATION_RESERVE_MICROS`. Backend dùng Structured Outputs; JSON được kiểm tra giới hạn và ID evidence. Chưa gọi API thật khi bàn giao vì chưa có khóa. [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).
- Ảnh Google: chọn **Google Gemini tạo ảnh** trong thiết lập bài đăng, rồi nhập Gemini API key hoặc cấu hình `GOOGLE_AI_API_KEY` cho API và worker trong Docker Compose. Mặc định dùng `gemini-3.1-flash-image`; dịch vụ Gemini API có hạn mức và giá riêng. Google Flow là ứng dụng sáng tạo tương tác; lịch nền dùng Gemini API/Nano Banana thay vì điều khiển Flow. [Image generation](https://ai.google.dev/gemini-api/docs/image-generation) · [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing).
- Meta: cấu hình `META_GRAPH_VERSION`, `META_APP_ID`, `META_APP_SECRET`, `TOKEN_ENCRYPTION_KEY`. Trong giao diện kết nối Page ID và Page access token. Backend xác minh app, Page và `pages_manage_posts`/`pages_read_engagement`; token được mã hóa AES-GCM. Sau spike trên Page thử, bật `LIVE_PUBLISHING=true`. Đã xác minh một bài đăng thật có ảnh trên Fanpage Công ty TNHH Poip Legal ngày 08/10/2026; quyền và token vẫn cần còn hiệu lực cho các lần chạy tiếp theo.
- Meta đăng ảnh PNG trực tiếp bằng multipart. Response thiếu post ID, timeout hoặc 5xx chuyển UNKNOWN, không retry tự động. 429 backoff theo Retry-After, tối đa 5 lần.
- OIDC: cấu hình các biến trong `compose.oidc.yaml`, dùng DB production riêng, provision owner bằng `scripts/provision-owner.sh`, TLS và cookie Secure. Tắt demo; API production từ chối khởi động nếu không có profile OIDC. Membership dựa trên email đã xác minh; không tự cấp Owner từ callback.

Trong đường dùng API key, giá chưa cấu hình hoặc usage không đủ sẽ quyết toán bằng khoản giữ trước với trạng thái ước tính. Ledger chống các worker cùng vượt **hạn mức giữ trước**; chưa bảo đảm hóa đơn provider chính xác tuyệt đối. Cần đối soát usage thực và cấu hình giá/buffer trước khi chạy AI thật.

## Kiểm tra

```bash
# Build + contract/unit tests, JDK 21
JAVA_HOME=/path/to/jdk21 mvn -f backend/pom.xml verify
npm ci --prefix frontend
npm run build --prefix frontend
npm run test:e2e --prefix frontend

# Integration trên PostgreSQL của Docker, DB marketing_test riêng
./scripts/test-integration.sh

# Integration native trên Apple Silicon, PostgreSQL thật
RUN_DB_TESTS=true EMBEDDED_DB=true JAVA_HOME=/path/to/jdk21 \
  mvn -f backend/pom.xml -Pportable-macos verify

# Vòng khép kín trên demo đang chạy; tạo dữ liệu giả
python3 scripts/smoke.py
RUN_LIVE_UI=true npm run test:e2e --prefix frontend -- --grep 'local app'
# Nếu dùng dashboard ở port khác, thêm LIVE_BASE_URL=http://127.0.0.1:PORT
```

Browser tests dùng Chrome đã cài; CI cài bằng Playwright. Integration không đăng thật: Meta/S3 adapters được mock, các transaction/migration/claim chạy trên PostgreSQL thật. Smoke dùng adapter DEMO và dữ liệu contact `example.com`.

Xem [trạng thái triển khai](docs/IMPLEMENTATION.md), [kiến trúc](docs/ARCHITECTURE.md), [runbook](docs/RUNBOOK.md), [hợp đồng API](docs/openapi.json) và [kiểm chứng](docs/VERIFICATION.md).
