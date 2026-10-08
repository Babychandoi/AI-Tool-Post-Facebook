# Phạm vi triển khai và đối chiếu thiết kế

Đây là MVP chạy được theo vòng khép kín. Không đánh đồng MVP với toàn bộ lộ trình 8–12 tuần trong tài liệu kiến trúc.

| Hạng mục | Đã triển khai | Giới hạn / điều kiện tiếp theo |
|---|---|---|
| Identity | Session, CSRF, RBAC, workspace/brand scope, OIDC profile, kiểm tra lại quyền khi worker chạy | Chưa kết nối IdP thật; cần provision membership; mỗi phiên sử dụng workspace đầu tiên được cấp; session hiện nằm tại API instance, cần sticky session nếu scale API |
| Brand | Nhiều brand, version/policy snapshot, pause, ngân sách, yêu cầu hai người | Chưa có admin UI quản lý membership và chọn workspace |
| Knowledge | Import văn bản, chunk, PostgreSQL lexical ranking, approval/hiệu lực/tenant filter, evidence IDs | Chưa upload PDF/DOCX, OCR, antivirus, embeddings hoặc vector search; không fetch URL nên không mở network tool cho Writer |
| Campaign | Mục tiêu, pillars, tạo run, daily trigger theo IANA timezone, unique kỳ kế hoạch | Một slot/ngày/campaign; chưa planner AI chọn/loại trùng ngữ nghĩa hoặc tối ưu slot theo metrics |
| Đăng tự động | Một màn kết nối AI/Page, mục tiêu/pillars/nguồn, ảnh, giờ, ba chế độ; preview chỉ tạo nháp; daily unique slot, explicit consent, version fencing, stop cancels pending; reviewer approval nối lịch | Máy chủ phải chạy; một bài/ngày/thương hiệu, chưa semantic dedup; bài có risk flag vẫn review; Meta app/server phải được cấu hình |
| Content | Draft/revision bất biến, archive bản chưa duyệt cũ, lịch sử, editor, JSON schema | Contract MVP gồm hook/body/CTA/document refs, chưa có claims ở mức câu và mapping từng chunk |
| AI | Responses adapter qua API key hoặc ChatGPT OAuth local, account/model picker, JWKS/PKCE, encrypted credentials, rotating refresh có lock, stream completion, nguồn không tin cậy, prompt versioned và usage | Chưa gọi provider thật, chưa có eval tiếng Việt được chấm bởi reviewer; không có research Internet; Images API riêng cho lịch tự động cần API key và quyền tạo ảnh |
| Media | PNG mẫu 1080px hoặc Images API PNG 1024px, checksum/provenance bất biến, bucket riêng | Chưa OCR/nội dung nhạy cảm, logo upload, asset lifecycle; Images API chưa kiểm chứng bằng key thật; không dùng hạn mức ChatGPT để tạo ảnh |
| Review | Snapshot hash, policy version, expiry 7 ngày, nguồn còn hạn, bốn mắt tùy chọn | Rule template demo giữ nguyên; Đăng tự động có consent/config version riêng, chỉ tự duyệt bài không có risk flag; bài thủ công/risk flag/two-person vẫn review |
| Publishing | Meta photo upload, demo adapter, internal UTC scheduler, deadline, lease/fence/heartbeat, preflight, attempts | Meta chưa spike trên Page thật; không remote schedule, Metricool, video hoặc nhiều ảnh |
| Reliability | SKIP LOCKED, request idempotency, unique intent, retry cap, UNKNOWN, chặn gửi lại cùng content/Page, manual reconciliation | Đối soát cần remote ID đã kiểm tra bởi operator; message/Page được xác minh, không có bằng chứng checksum ảnh từ Meta; không tuyên bố exactly-once ở provider |
| Events | Transactional outbox, operations projection receipt idempotent | Chưa broker hoặc CRM consumer; envelope projection không cập nhật reducer nghiệp vụ theo aggregate_version |
| Webhook | Challenge, HMAC raw body, lưu bền vững, account mapping, dedup replay entry, worker ACK processing | Chưa normalized conversation/message hoặc reply bot; dedup hash entry không bao phủ mọi biến thể reordered payload |
| Lead | Public form, consent, tracking key theo publication, source confidence, stage, assignment, optimistic version, keyset pagination, ẩn contact Analyst | Cursor hiện base64 không ký; chưa tự merge contact, opt-out/delete pipeline, export hoặc CRM sync; collection cần retention policy trước production |
| Metrics | Jobs 1/24/72/168h, reactions/comments/shares từ Meta, freshness, missing NULL | Reach chưa có capability đã xác minh; metric lỗi không được giả thành 0; chưa causal experiment/growth optimizer |
| Dashboard | Mười một màn, gồm thiết lập Đăng tự động tập trung, trạng thái thực, preview, review, lịch, campaigns, knowledge, leads, operations | Không có màn inbox vì backend chưa hỗ trợ reply; polling có tốc độ khác theo pending/error |
| Hạ tầng | Compose PostgreSQL/Redis/S3/API/worker/frontend đã build và chạy, Flyway, CI, native local pilot | Production TLS, backup/PITR, MFA, observability và restore drill cần người vận hành chốt |

Chưa có dữ liệu chứng minh benchmark p95, RPO/RTO, chi phí thực, chất lượng marketing hoặc kết quả kinh doanh. Những mục này là tiêu chí pilot cần đo, không là kết quả bàn giao.

Để nghiệm thu đăng thật: cung cấp brand/Page pilot, app Meta có quyền, API key OpenAI và ngân sách; chạy spike bằng nội dung được duyệt, lưu remote ID và đối chiếu metrics. Đăng tự động bắt đầu ở trạng thái tắt, cần người dùng chọn Fanpage thật và xác nhận; kiểm chứng chất lượng bằng bài thử và reviewer trước khi dùng thật.
