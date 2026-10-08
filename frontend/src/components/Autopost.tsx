import { useEffect, useRef, useState } from "react";
import {
  Check,
  CircleAlert,
  Clock3,
  Facebook,
  Image,
  LoaderCircle,
  Pause,
  Play,
  Sparkles,
  ArrowRight,
} from "lucide-react";
import { api, Badge, dt, money } from "../api";
import type { Row } from "../api";
import { Button, Modal } from "./ui";
import { AiConnections } from "./AiConnections";

type Props = {
  brand: Row;
  accounts: Row[];
  knowledge: Row[];
  canManage: boolean;
  refresh: number;
  onChanged: () => void;
  onReview: () => void;
};
const initial = {
  account_id: "",
  objective: "",
  pillars: "",
  mode: "REVIEW",
  daily_post_time: "09:00",
  image_mode: "TEMPLATE",
  image_prompt: "Minh họa hiện đại, bố cục sạch, màu sắc thương hiệu.",
  image_api_key: "",
  clear_image_key: false,
  image_model: "gemini-3.1-flash-image",
  image_reserve_micros: 100000,
  live_consent: false,
};

export function Autopost({
  brand,
  accounts,
  knowledge,
  canManage,
  refresh,
  onChanged,
  onReview,
}: Props) {
  const [info, setInfo] = useState<Row | null>(null),
    [fields, setFields] = useState(initial);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState("");
  const [pageId, setPageId] = useState(""),
    [pageToken, setPageToken] = useState("");
  const [facts, setFacts] = useState(""),
    [factsConfirmed, setFactsConfirmed] = useState(false);
  const [preview, setPreview] = useState<Row | null>(null);
  const [pendingPreview, setPendingPreview] = useState("");
  const [imagePairing, setImagePairing] = useState<Row | null>(null);
  const hydrated = useRef("");
  const prefix = `/brands/${brand.id}`;
  const selected = accounts.find((a) => a.id === fields.account_id);
  const zone = brand.timezone || "Asia/Ho_Chi_Minh";
  const set = (key: string, value: unknown) =>
    setFields((f) => ({ ...f, [key]: value }));
  function accept(data: Row, reset = false) {
    setInfo(data);
    if (reset || hydrated.current !== brand.id) {
      const c = data.config;
      setFields(
        c
          ? {
              ...initial,
              ...Object.fromEntries(
                Object.keys(initial)
                  .filter((k) => k in c)
                  .map((k) => [k, c[k]]),
              ),
              pillars: c.pillars.join("\n"),
              daily_post_time: c.daily_post_time.slice(0, 5),
              account_id: c.account_id || "",
              image_api_key: "",
            }
          : {
              ...initial,
              account_id: accounts.find((a) => a.provider === "DEMO")?.id || "",
            },
      );
      hydrated.current = brand.id;
    }
  }
  useEffect(() => {
    let alive = true;
    api(prefix + "/autopost")
      .then((data) => {
        if (alive) accept(data);
      })
      .catch((e) => {
        if (alive) setError(e.message);
      });
    return () => {
      alive = false;
    };
  }, [brand.id, refresh]);
  useEffect(() => {
    if (!pendingPreview) return;
    const run = info?.runs?.find((r: Row) => r.id === pendingPreview);
    if (run?.output_revision_id) {
      setPreview(run);
      setPendingPreview("");
    } else if (run?.status === "FAILED") setPendingPreview("");
  }, [info, pendingPreview]);
  async function perform(work: () => Promise<void>) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function save(enabled: boolean) {
    const payload = {
      ...fields,
      account_id: fields.account_id || null,
      pillars: fields.pillars
        .split("\n")
        .map((p) => p.trim())
        .filter(Boolean),
      enabled,
      expected_version: info?.config?.version || 0,
    };
    const data = await api(prefix + "/autopost", "PUT", payload);
    accept(data, true);
    onChanged();
    return data;
  }
  const valid = !!info && !!fields.objective.trim() && !!fields.pillars.trim();
  const checks: Row[] = info?.checks?.length
    ? info.checks
    : [
        {
          key: "ai",
          ok:
            info?.ai_provider &&
            info.ai_provider !== "DEMO" &&
            info.ai_provider !== "MISSING",
          message:
            info?.ai_provider === "CHATGPT"
              ? "ChatGPT đã kết nối"
              : "Kết nối AI viết bài ở bước 1.",
        },
        {
          key: "page",
          ok: !!selected,
          message: selected ? "Đã chọn Page" : "Chọn Fanpage ở bước 1.",
        },
        {
          key: "knowledge",
          ok: knowledge.some((k) => k.status === "APPROVED" && !k.expired),
          message: knowledge.some((k) => k.status === "APPROVED" && !k.expired)
            ? "Đã có thông tin thương hiệu"
            : "Thêm thông tin thương hiệu ở bước 2.",
        },
      ];
  const enabled = info?.config?.enabled;
  return (
    <div className="autopost">
      {preview && (
        <Modal
          wide
          title="Bài viết & ảnh"
          sub={
            preview.autopost_preview
              ? "Bài thử · Không đăng Facebook"
              : "Nội dung của lịch hằng ngày"
          }
          onClose={() => setPreview(null)}
        >
          <div className="autopost-preview">
            <img
              src={`/v1/revisions/${preview.output_revision_id}/image`}
              alt={preview.hook || preview.topic}
            />
            <div>
              <Badge status={preview.revision_status || preview.status} />
              <h3>{preview.hook}</h3>
              <p>{preview.body}</p>
              <p>{preview.cta}</p>
              {!!preview.risk_flags?.length && (
                <div className="notice">
                  Bài cần kiểm tra thêm:{" "}
                  {preview.risk_flags
                    .map(
                      (flag: string) =>
                        (
                          ({
                            LEGAL_CONTENT: "Nội dung pháp lý",
                            COMMERCIAL_CLAIM: "Giá, ưu đãi hoặc cam kết",
                            MISSING_EVIDENCE: "Thiếu nguồn thông tin",
                          }) as Record<string, string>
                        )[flag] || "Cần kiểm tra nội dung",
                    )
                    .join("; ")}
                </div>
              )}
              <Button
                secondary
                onClick={() => {
                  setPreview(null);
                  onReview();
                }}
              >
                Mở hàng đợi duyệt
              </Button>
            </div>
          </div>
        </Modal>
      )}
      <div className="autopost-intro">
        <div className="autopost-intro-icon">
          <Sparkles size={24} />
        </div>
        <div>
          <h2>Thiết lập một lần. Có bài mới mỗi ngày.</h2>
          <p>
            AI viết nội dung, caption và tạo ảnh theo chủ đề của bạn. Chọn cách
            đăng bên dưới.
          </p>
        </div>
      </div>
      {error && (
        <div className="error-box" role="alert">
          <CircleAlert size={18} />
          {error}
        </div>
      )}
      {message && (
        <div className="notice" role="status">
          <Check size={18} />
          {message}
        </div>
      )}
      {!canManage && (
        <div className="notice">
          Chủ sở hữu hoặc quản trị viên có thể thay đổi lịch tự động. Bạn có thể
          xem trạng thái tại đây.
        </div>
      )}
      <div className="autopost-layout">
        <div className="autopost-steps">
          <section className="panel autopost-step">
            <div className="autopost-step-heading">
              <span>1</span>
              <div>
                <h2>Kết nối AI & Fanpage</h2>
                <p>Chọn tài khoản viết bài và nơi bài sẽ xuất hiện.</p>
              </div>
            </div>
            {canManage && (
              <div className="autopost-ai">
                <AiConnections embedded onChanged={onChanged} />
              </div>
            )}
            {info?.ai_provider === "DEMO" && (
              <p className="autopost-help">
                Chưa kết nối AI: bài thử dùng nội dung mẫu từ kho thông tin,
                không phải nội dung AI tự viết.
              </p>
            )}
            {info?.ai_provider === "OPENAI" && (
              <p className="autopost-help">
                AI viết bài dùng OpenAI API trên máy chủ, tính phí riêng theo
                API.
              </p>
            )}
            <label className="autopost-field">
              <span>
                <Facebook size={16} /> Fanpage nhận bài
              </span>
              <select
                disabled={busy || !canManage || !info}
                value={fields.account_id}
                onChange={(e) => set("account_id", e.target.value)}
              >
                <option value="">Chọn Fanpage</option>
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                    {a.provider === "DEMO"
                      ? " · Chạy thử, không đăng Facebook"
                      : " · Facebook"}
                    {a.health !== "CONNECTED" ? " · Cần kết nối lại" : ""}
                  </option>
                ))}
              </select>
            </label>
            {canManage && (
              <details className="autopost-details">
                <summary>Kết nối Fanpage Facebook mới</summary>
                <p className="autopost-help">
                  Dùng Page ID và Page access token có quyền đăng bài. Token
                  được lưu bảo mật trên máy chủ.
                </p>
                {info && !info.meta_available && (
                  <div className="notice">
                    Kết nối Facebook trên máy chủ chưa sẵn sàng. Quản trị viên
                    cần cấu hình ứng dụng Meta trước khi xác minh Fanpage.
                  </div>
                )}
                <label className="autopost-field">
                  Page ID
                  <input
                    value={pageId}
                    inputMode="numeric"
                    autoComplete="off"
                    onChange={(e) => setPageId(e.target.value)}
                  />
                </label>
                <label className="autopost-field">
                  Page access token
                  <input
                    type="password"
                    value={pageToken}
                    autoComplete="new-password"
                    onChange={(e) => setPageToken(e.target.value)}
                  />
                </label>
                <Button
                  secondary
                  disabled={
                    busy || !info?.meta_available || !pageId || !pageToken
                  }
                  onClick={() =>
                    perform(async () => {
                      const a = await api(prefix + "/accounts", "POST", {
                        page_id: pageId,
                        page_token: pageToken,
                      });
                      setPageToken("");
                      set("account_id", a.id);
                      onChanged();
                      setMessage(
                        "Đã kết nối Fanpage. Chọn giờ và cách đăng ở bước 4.",
                      );
                    })
                  }
                >
                  Kết nối Fanpage
                </Button>
              </details>
            )}
          </section>
          <section className="panel autopost-step">
            <div className="autopost-step-heading">
              <span>2</span>
              <div>
                <h2>AI sẽ viết về điều gì?</h2>
                <p>Nhập định hướng và thông tin đúng về thương hiệu.</p>
              </div>
            </div>
            <label className="autopost-field">
              Mục tiêu nội dung
              <textarea
                disabled={!canManage || busy || !info}
                maxLength={500}
                rows={2}
                value={fields.objective}
                placeholder="Ví dụ: Giới thiệu dịch vụ, chia sẻ kiến thức và mời khách hàng nhắn tin."
                onChange={(e) => set("objective", e.target.value)}
              />
            </label>
            <label className="autopost-field">
              Chủ đề hằng ngày · mỗi dòng một chủ đề
              <textarea
                disabled={!canManage || busy || !info}
                rows={4}
                value={fields.pillars}
                placeholder={
                  "Mẹo hữu ích cho khách hàng\nGiới thiệu dịch vụ\nCâu hỏi thường gặp"
                }
                onChange={(e) => set("pillars", e.target.value)}
              />
            </label>
            <p className="autopost-help">
              AI luân phiên các chủ đề này, theo giọng văn thương hiệu. Caption
              gồm mở bài, mô tả và lời kêu gọi hành động.
            </p>
            <details
              className="autopost-details"
              open={
                !knowledge.some((k) => k.status === "APPROVED" && !k.expired)
              }
            >
              <summary>
                Thông tin thương hiệu ·{" "}
                {
                  knowledge.filter((k) => k.status === "APPROVED" && !k.expired)
                    .length
                }{" "}
                nguồn đã duyệt
              </summary>
              <p className="autopost-help">
                Tên sản phẩm, dịch vụ, ưu điểm, thông tin liên hệ và những điều
                AI được phép nói.
              </p>
              <ul className="autopost-sources">
                {knowledge
                  .filter((k) => k.status === "APPROVED" && !k.expired)
                  .slice(0, 5)
                  .map((k) => (
                    <li key={k.id}>
                      <Check size={14} />
                      {k.title}
                    </li>
                  ))}
              </ul>
              {canManage && (
                <>
                  <label className="autopost-field">
                    Bổ sung thông tin
                    <textarea
                      rows={4}
                      value={facts}
                      maxLength={200000}
                      placeholder="Nhập thông tin thực tế về thương hiệu để AI dựa vào khi viết…"
                      onChange={(e) => setFacts(e.target.value)}
                    />
                  </label>
                  <label className="autopost-checkbox">
                    <input
                      type="checkbox"
                      checked={factsConfirmed}
                      onChange={(e) => setFactsConfirmed(e.target.checked)}
                    />
                    Tôi đã kiểm tra và xác nhận thông tin này đúng.
                  </label>
                  <Button
                    secondary
                    disabled={busy || !facts.trim() || !factsConfirmed}
                    onClick={() =>
                      perform(async () => {
                        const k = await api(prefix + "/knowledge", "POST", {
                          title: "Thông tin đăng tự động · " + brand.name,
                          body: facts,
                        });
                        await api(
                          prefix + `/knowledge/${k.id}/approve`,
                          "POST",
                        );
                        setFacts("");
                        setFactsConfirmed(false);
                        onChanged();
                        setMessage("Đã lưu thông tin để AI sử dụng.");
                      })
                    }
                  >
                    Lưu thông tin đã xác nhận
                  </Button>
                </>
              )}
            </details>
          </section>
          <section className="panel autopost-step">
            <div className="autopost-step-heading">
              <span>3</span>
              <div>
                <h2>Ảnh cho bài viết</h2>
                <p>Mỗi bài có một ảnh được tạo cùng nội dung.</p>
              </div>
            </div>
            <div className="autopost-options">
              {[
                {
                  id: "TEMPLATE",
                  title: "Ảnh mẫu thương hiệu",
                  text: "Tự dựng ảnh có tiêu đề và màu thương hiệu. Không tốn phí tạo ảnh.",
                },
                {
                  id: "OPENAI",
                  title: "AI tự sinh ảnh",
                  text: "Tạo ảnh minh họa riêng theo nội dung. Cần OpenAI API cho ảnh, tính phí riêng.",
                },
                {
                  id: "GOOGLE",
                  title: "Google Gemini tạo ảnh",
                  text: "Tạo ảnh tự động bằng Gemini (Nano Banana). Cần Gemini API key; Flow không có API tự động cho backend.",
                },
                {
                  id: "CHATGPT_WEB",
                  title: "ChatGPT Web · Thử nghiệm",
                  text: "Dùng tài khoản ChatGPT đã đăng nhập trên máy này. Ảnh được dùng theo cách duyệt hoặc tự đăng bạn chọn bên dưới.",
                },
              ].map((o) => (
                <label
                  key={o.id}
                  className={`autopost-option ${fields.image_mode === o.id ? "selected" : ""}`}
                >
                  <input
                    type="radio"
                    name="image-mode"
                    value={o.id}
                    checked={fields.image_mode === o.id}
                    disabled={!canManage || busy || !info}
                    onChange={() => {
                      set("image_mode", o.id);
                      if (o.id === "GOOGLE")
                        set("image_model", "gemini-3.1-flash-image");
                      if (o.id === "OPENAI") set("image_model", "gpt-image-2");
                      if (o.id === "CHATGPT_WEB") {
                        set("image_model", "chatgpt-web-image");
                      }
                    }}
                  />
                  <div>
                    <b>{o.title}</b>
                    <p>{o.text}</p>
                  </div>
                </label>
              ))}
            </div>
            {fields.image_mode === "CHATGPT_WEB" && (
              <>
                <div className="notice">
                  <Image size={18} />
                  <span>
                    {info?.chatgpt_web_images?.message ||
                      "Mở cầu nối ChatGPT Web trên máy đang chạy dự án."}{" "}
                    Không cần API key tạo ảnh. Hạn mức phụ thuộc tài khoản
                    ChatGPT. Giữ Cốc Cốc mở và tiện ích đã kết nối để ảnh được
                    tạo, lấy về tự động.
                  </span>
                </div>
                {info?.chatgpt_web_images?.mode === "BROWSER_EXTENSION" ? (
                  <div className="autopost-help">
                    <p>Cài tiện ích “AI Marketing – Ảnh ChatGPT tự động” trong
                      Cốc Cốc một lần, rồi nhập mã kết nối bên dưới vào tiện ích.
                      Sau khi kết nối, mỗi bài tự gửi prompt và nhận ảnh; bạn
                      không cần tạo hoặc tải ảnh thủ công.</p>
                    <Button secondary disabled={busy || !canManage}
                      onClick={() => perform(async () => {
                        setImagePairing(await api(prefix + "/autopost/image-connector/pairing", "POST", {}));
                      })}>Tạo mã kết nối Cốc Cốc</Button>
                    <Button secondary disabled={busy}
                      onClick={() => perform(async () => {
                        accept(await api(prefix + "/autopost"));
                      })}>Kiểm tra kết nối ảnh</Button>
                    {imagePairing && <p role="status">
                      Mã kết nối: <strong>{imagePairing.code}</strong> · Có hiệu lực
                      trong 2 phút. Nhập vào tiện ích rồi bấm Kết nối.
                    </p>}
                  </div>
                ) : (
                  <p className="autopost-help">
                    Cầu nối ảnh chưa khởi động. Mở ứng dụng kèm dịch vụ tạo ảnh
                    rồi kiểm tra lại kết nối.
                  </p>
                )}
                <label className="autopost-field">
                  Phong cách ảnh
                  <textarea
                    rows={2}
                    value={fields.image_prompt}
                    maxLength={2000}
                    disabled={busy || !canManage || !info}
                    onChange={(e) => set("image_prompt", e.target.value)}
                  />
                </label>
              </>
            )}
            {fields.image_mode === "GOOGLE" && (
              <>
                <div className="notice">
                  <Image size={18} />
                  <span>
                    Dùng Gemini API để tạo ảnh tự động. Đây là dịch vụ API riêng
                    của Google; không cần OpenAI API key. Flow là ứng dụng sáng
                    tạo dùng thủ công và không có endpoint API phù hợp cho lịch
                    chạy nền.
                  </span>
                </div>
                <label className="autopost-field">
                  Gemini API key
                  {info?.config?.image_key_saved && (
                    <small>Đã lưu key. Để trống để giữ key hiện tại.</small>
                  )}
                  <input
                    type="password"
                    autoComplete="new-password"
                    value={fields.image_api_key}
                    placeholder={
                      info?.google_image_api_available
                        ? "Đang dùng key Google trên máy chủ"
                        : "Dán Gemini API key từ Google AI Studio"
                    }
                    disabled={busy || !canManage || !info}
                    onChange={(e) => set("image_api_key", e.target.value)}
                  />
                </label>
                {info?.config?.image_key_saved && (
                  <label className="autopost-checkbox">
                    <input
                      type="checkbox"
                      checked={fields.clear_image_key}
                      onChange={(e) => set("clear_image_key", e.target.checked)}
                    />
                    Xóa API key đã lưu
                  </label>
                )}
                <label className="autopost-field">
                  Phong cách ảnh
                  <textarea
                    rows={2}
                    value={fields.image_prompt}
                    maxLength={2000}
                    disabled={busy || !canManage || !info}
                    onChange={(e) => set("image_prompt", e.target.value)}
                  />
                </label>
                <label className="autopost-field">
                  Model ảnh
                  <select
                    value={fields.image_model}
                    disabled={busy || !canManage || !info}
                    onChange={(e) => set("image_model", e.target.value)}
                  >
                    <option value="gemini-3.1-flash-image">
                      Gemini 3.1 Flash Image
                    </option>
                  </select>
                </label>
              </>
            )}
            {fields.image_mode === "OPENAI" && (
              <>
                <div className="notice">
                  <Image size={18} />
                  <span>
                    Đăng nhập ChatGPT đang dùng để viết bài. Kết nối này chưa hỗ
                    trợ sinh ảnh; API tạo ảnh dùng hạn mức và phí riêng.
                  </span>
                </div>
                <label className="autopost-field">
                  OpenAI API key cho ảnh
                  {info?.config?.image_key_saved && (
                    <small>Đã lưu key. Để trống để giữ key hiện tại.</small>
                  )}
                  <input
                    type="password"
                    autoComplete="new-password"
                    value={fields.image_api_key}
                    placeholder={
                      info?.image_api_available
                        ? "Có thể dùng key đã cấu hình trên máy chủ"
                        : "Dán API key tạo ảnh"
                    }
                    disabled={busy || !canManage || !info}
                    onChange={(e) => set("image_api_key", e.target.value)}
                  />
                </label>
                {info?.config?.image_key_saved && (
                  <label className="autopost-checkbox">
                    <input
                      type="checkbox"
                      checked={fields.clear_image_key}
                      onChange={(e) => set("clear_image_key", e.target.checked)}
                    />
                    Xóa key tạo ảnh đã lưu
                  </label>
                )}
                <label className="autopost-field">
                  Phong cách ảnh
                  <textarea
                    rows={2}
                    value={fields.image_prompt}
                    maxLength={2000}
                    disabled={busy || !canManage || !info}
                    onChange={(e) => set("image_prompt", e.target.value)}
                  />
                </label>
                <details className="autopost-details">
                  <summary>Model ảnh & dự trù ngân sách</summary>
                  <label className="autopost-field">
                    Model ảnh
                    <select
                      value={fields.image_model}
                      disabled={busy || !canManage || !info}
                      onChange={(e) => set("image_model", e.target.value)}
                    >
                      {[
                        "gpt-image-2.5-sunburst",
                        "gpt-image-2.5-flare",
                        "gpt-image-2",
                        "gpt-image-1.5",
                        "gpt-image-1",
                      ].map((m) => (
                        <option key={m}>{m}</option>
                      ))}
                    </select>
                  </label>
                  <label className="autopost-field">
                    Dự trù cho mỗi ảnh (USD)
                    <input
                      type="number"
                      min="0.01"
                      max="10"
                      step="0.01"
                      value={fields.image_reserve_micros / 1000000}
                      disabled={busy || !canManage || !info}
                      onChange={(e) =>
                        set(
                          "image_reserve_micros",
                          Math.round(Number(e.target.value) * 1000000),
                        )
                      }
                    />
                  </label>
                  <p className="autopost-help">
                    Đây là khoản giữ trong ngân sách tháng, không phải giá thực
                    tế hay giới hạn hóa đơn OpenAI. Ảnh vuông 1024 × 1024, chất
                    lượng thấp; phí thực tế xem trên OpenAI.
                  </p>
                </details>
              </>
            )}
          </section>
          <section className="panel autopost-step">
            <div className="autopost-step-heading">
              <span>4</span>
              <div>
                <h2>Giờ đăng & cách chạy</h2>
                <p>Một bài mỗi ngày, theo múi giờ thương hiệu.</p>
              </div>
            </div>
            <label className="autopost-field">
              Giờ đăng hằng ngày
              <input
                type="time"
                value={fields.daily_post_time}
                disabled={busy || !canManage || !info}
                onChange={(e) => set("daily_post_time", e.target.value)}
              />
              <small>
                <Clock3 size={14} /> {zone} · AI chuẩn bị bài trước giờ đăng 30
                phút.
              </small>
            </label>
            <div className="autopost-modes">
              {[
                {
                  id: "REVIEW",
                  title: "Duyệt trước khi đăng",
                  text: "AI chuẩn bị bài. Bạn duyệt, hệ thống đăng theo giờ đã chọn.",
                },
                {
                  id: "DEMO",
                  title: "Chạy thử toàn bộ",
                  text: "Tạo bài, ảnh và mô phỏng đăng trên Page thử nghiệm. Không đăng Facebook.",
                },
                {
                  id: "AUTO",
                  title: "Tự đăng lên Facebook",
                  text: "AI tạo bài và ảnh, rồi đăng vào giờ đã chọn; không cần duyệt từng bài thông thường.",
                },
              ].map((o) => (
                <label
                  key={o.id}
                  className={`autopost-option ${fields.mode === o.id ? "selected" : ""}`}
                >
                  <input
                    type="radio"
                    name="autopost-mode"
                    value={o.id}
                    checked={fields.mode === o.id}
                    disabled={!canManage || busy || !info}
                    onChange={() => {
                      set("mode", o.id);
                      if (o.id === "DEMO") {
                        set(
                          "account_id",
                          accounts.find((a) => a.provider === "DEMO")?.id || "",
                        );
                        set("live_consent", false);
                      }
                    }}
                  />
                  <div>
                    <b>{o.title}</b>
                    <p>{o.text}</p>
                  </div>
                </label>
              ))}
            </div>
            {fields.mode === "AUTO" && selected?.provider !== "META" && (
              <div className="notice">
                Hãy kết nối và chọn Fanpage Facebook thật ở bước 1. Page thử
                nghiệm không thể đăng Facebook.
              </div>
            )}
            {(fields.mode === "AUTO" || selected?.provider === "META") && (
              <>
                <label className="autopost-checkbox autopost-consent">
                  <input
                    type="checkbox"
                    disabled={busy || !canManage || !info}
                    checked={fields.live_consent}
                    onChange={(e) => set("live_consent", e.target.checked)}
                  />
                  <span>
                    Tôi cho phép hệ thống đăng thật lên Fanpage đã chọn theo
                    lịch này.
                    {fields.mode === "AUTO" &&
                      " Tôi đồng ý bài thông thường được tự duyệt và đăng."}
                  </span>
                </label>
                {info && !info.live_available && (
                  <p className="autopost-help">
                    Máy chủ hiện đang tắt đăng thật. Bạn có thể lưu thiết lập và
                    tạo bài thử; quản trị viên cần mở chế độ đăng Facebook trước
                    khi bật lịch thật.
                  </p>
                )}
              </>
            )}
            <p className="autopost-help">
              Bài có rủi ro pháp lý, giá, ưu đãi hoặc thiếu nguồn vẫn chờ bạn
              duyệt. Khi mất kết nối, hết hạn mức hoặc lỡ giờ quá 1 tiếng, bài
              dừng để bạn kiểm tra. Máy chủ phải chạy để lịch hoạt động.
            </p>
          </section>
        </div>
        <aside className="autopost-summary panel">
          <span className="eyebrow">LỊCH CỦA BẠN</span>
          <div className="autopost-summary-time">
            {fields.daily_post_time}
            <span>mỗi ngày</span>
          </div>
          <p>{selected?.name || "Chưa chọn Fanpage"}</p>
          <div className={`autopost-running ${enabled ? "on" : ""}`}>
            <i />
            {enabled ? "Lịch đã bật" : "Chưa bật lịch tự động"}
          </div>
          {enabled && info?.next_post_at && (
            <p className="autopost-help">
              Lần tiếp theo: {dt(info.next_post_at, zone)}
            </p>
          )}
          <div className="autopost-checks">
            {checks.map((c) => (
              <div key={c.key} className={c.ok ? "ok" : "missing"}>
                {c.ok ? <Check size={16} /> : <CircleAlert size={16} />}
                <span>{c.message}</span>
              </div>
            ))}
          </div>
          {["OPENAI", "GOOGLE"].includes(fields.image_mode) && (
            <p className="autopost-help">
              Dự trù ảnh: {money(fields.image_reserve_micros)}/bài · ngân sách
              tháng {money(brand.monthly_budget_micros)}.
            </p>
          )}
          {canManage && (
            <div className="autopost-actions">
              <Button
                disabled={busy || !valid}
                secondary
                onClick={() =>
                  perform(async () => {
                    await save(false);
                    setMessage(
                      "Đã lưu thiết lập. Lịch đang tắt; bạn có thể tạo bài thử trước khi bật.",
                    );
                  })
                }
              >
                Lưu thiết lập
              </Button>
              <Button
                disabled={busy || !valid}
                secondary
                onClick={() =>
                  perform(async () => {
                    await save(false);
                    const run = await api(prefix + "/autopost/preview", "POST");
                    setPendingPreview(run.run_id);
                    onChanged();
                    setMessage(
                      "Đang tạo bài thử. Bài này chỉ để xem trước, không đăng Facebook; lịch đang tắt.",
                    );
                  })
                }
              >
                <Sparkles size={16} />
                Tạo bài thử
              </Button>
              {enabled ? (
                <Button
                  secondary
                  disabled={busy}
                  onClick={() =>
                    perform(async () => {
                      accept(
                        await api(prefix + "/autopost/stop", "POST", {
                          expected_version: info?.config?.version,
                        }),
                        true,
                      );
                      onChanged();
                      setMessage(
                        "Đã tắt lịch và hủy các bài còn chờ gửi. Bài đang gửi hoặc đã đăng cần kiểm tra ở lịch nội dung.",
                      );
                    })
                  }
                >
                  <Pause size={16} />
                  Tắt đăng tự động
                </Button>
              ) : (
                <Button
                  disabled={busy || !valid}
                  onClick={() =>
                    perform(async () => {
                      const saved = await save(true);
                      setMessage(
                        saved.config.mode === "DEMO"
                          ? "Đã bật lịch chạy thử. Không có bài nào được đăng Facebook."
                          : "Đã bật lịch hằng ngày. Bạn có thể tắt tại đây bất cứ lúc nào.",
                      );
                    })
                  }
                >
                  {busy ? (
                    <LoaderCircle size={16} className="spin" />
                  ) : (
                    <Play size={16} />
                  )}
                  Bật lịch hằng ngày
                </Button>
              )}
            </div>
          )}
          <p className="autopost-help">
            Lưu thiết lập hoặc tạo bài thử sẽ tắt lịch đang bật. Bài đã gửi
            Facebook không thể thu hồi bằng nút tắt lịch.
          </p>
        </aside>
      </div>
      <section className="panel autopost-history">
        <div className="section-heading">
          <div>
            <h2>Bài do lịch này tạo</h2>
            <p>Theo dõi bài thử, bài chờ duyệt và lần đăng.</p>
          </div>
          <Button secondary onClick={onReview}>
            Mở hàng đợi duyệt
            <ArrowRight size={15} />
          </Button>
        </div>
        {!info?.runs?.length ? (
          <p className="autopost-help">
            Chưa có bài. Bấm “Tạo bài thử” để xem nội dung và ảnh trước khi bật
            lịch.
          </p>
        ) : (
          <div className="autopost-run-list">
            {info.runs.map((r: Row) => (
              <article key={r.id}>
                <div>
                  <b>{r.topic}</b>
                  <small>
                    {r.autopost_preview
                      ? "Bài thử · không đăng"
                      : `Lịch ${dt(r.autopost_due_at, zone)}`}
                  </small>
                  {r.error && <p className="autopost-run-error">{r.error}</p>}
                </div>
                {r.output_revision_id && (
                  <button className="text-button" onClick={() => setPreview(r)}>
                    Xem bài & ảnh
                  </button>
                )}
                <Badge
                  status={r.publication_status || r.revision_status || r.status}
                />
                {r.remote_url && (
                  <a href={r.remote_url} target="_blank" rel="noreferrer">
                    Xem trên Facebook
                  </a>
                )}
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
