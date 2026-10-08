import { useState, useEffect } from "react";
import {
  Sparkles,
  BookOpen,
  ArrowRight,
  ShieldCheck,
  CircleAlert,
  CheckCheck,
  Image,
  FileText,
  Send,
  CalendarDays,
  Facebook,
  Check,
  LoaderCircle,
  MoreHorizontal,
} from "lucide-react";
import { api, dt, Badge, stateLabels } from "../api";
import type { Row } from "../api";
import type { Page } from "../App";
import { formatInTimeZone, fromZonedTime } from "date-fns-tz";
import { Button, Modal } from "./ui";
export function ActionModal({
  modal,
  brand,
  data,
  has,
  onClose,
  onDone,
  setPage,
}: {
  modal: { type: string; row?: Row };
  brand?: Row;
  data: Record<string, any>;
  has: (...roles: string[]) => boolean;
  onClose: () => void;
  onDone: (message: string) => void;
  setPage: (page: Page) => void;
}) {
  const { type, row } = modal;
  const zone = brand?.timezone || "Asia/Ho_Chi_Minh";
  const [fields, setFields] = useState<Row>({
    ...row,
    hook: row?.hook || "",
    body: row?.body || "",
    cta: row?.cta || "",
    topic: row?.topic || "",
    name: row?.name || "",
    industry: row?.industry || "",
    voice: row?.voice || "",
    audience: row?.audience || "",
    color: row?.color || "#8b76ef",
    timezone: row?.timezone || "Asia/Ho_Chi_Minh",
    mode: row?.mode || "ASSISTED",
    budget: row?.monthly_budget_micros
      ? row.monthly_budget_micros / 1000000
      : 10,
    due_at: formatInTimeZone(
      new Date(Date.now() + 3600000),
      zone,
      "yyyy-MM-dd'T'HH:mm",
    ),
    account_id: data.accounts[0]?.id || "",
    campaign_id: row?.campaign_id || "",
    title: "",
    source_url: "",
    effective_until: "",
    objective: "",
    pillars: "",
    contact: row?.contact || "",
    interest: row?.interest || "",
    purpose: "Liên hệ hỗ trợ nhu cầu khách hàng đã cung cấp",
    consent: false,
    stage: row?.stage || "NEW",
    assigned_user: row?.assigned_user || "",
    next_action: row?.next_action || "",
    page_id: "",
    page_token: "",
    generation_enabled: false,
    daily_generation_time: "06:00",
    remote_post_id: "",
    note: "",
  });
  const [refs, setRefs] = useState<string[]>(row?.source_refs || []),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [key] = useState(() => crypto.randomUUID()),
    [history, setHistory] = useState<Row[]>([]);
  const titles: Record<string, string> = {
    generate: "Khởi tạo nội dung",
    "new-content": "Viết bản nháp",
    "edit-content": "Content Studio",
    preview: "Xem & duyệt nội dung",
    schedule: "Lên lịch xuất bản",
    brand: row ? "Chỉnh sửa thương hiệu" : "Thêm thương hiệu",
    knowledge: "Thêm nguồn kiến thức",
    campaign: "Tạo chiến dịch",
    lead: row ? "Cập nhật khách hàng" : "Thêm khách hàng",
    connection: "Kết nối Facebook Page",
    reconcile: "Đối soát kết quả đăng",
  };
  const set = (name: string, value: any) =>
    setFields((f) => ({ ...f, [name]: value }));
  const input = (
    name: string,
    label: string,
    options: {
      multiline?: boolean;
      type?: string;
      required?: boolean;
      max?: number;
      placeholder?: string;
    } = {},
  ) => (
    <label>
      {label}
      {options.multiline ? (
        <textarea
          value={fields[name] ?? ""}
          onChange={(e) => set(name, e.target.value)}
          required={options.required ?? true}
          maxLength={options.max || 10000}
          placeholder={options.placeholder}
        />
      ) : (
        <input
          type={options.type || "text"}
          value={fields[name] ?? ""}
          onChange={(e) => set(name, e.target.value)}
          required={options.required ?? true}
          maxLength={options.max || 250}
          placeholder={options.placeholder}
        />
      )}
    </label>
  );
  const sources = (
    <fieldset className="sources-select">
      <legend>Nguồn tham chiếu</legend>
      {data.knowledge
        .filter((k: Row) => k.status === "APPROVED" && !k.expired)
        .map((k: Row) => (
          <label className="checkbox" key={k.id}>
            <input
              type="checkbox"
              checked={refs.includes(k.id)}
              onChange={(e) =>
                setRefs((old) =>
                  e.target.checked
                    ? [...old, k.id]
                    : old.filter((id) => id !== k.id),
                )
              }
            />
            <span>
              {k.title}
              <small>Phiên bản {k.version} · đã duyệt</small>
            </span>
          </label>
        ))}
      {!data.knowledge.some(
        (k: Row) => k.status === "APPROVED" && !k.expired,
      ) && (
        <p className="muted">
          Chưa có nguồn được duyệt. Thêm và duyệt nguồn trong màn Thương hiệu &
          tri thức.
        </p>
      )}
    </fieldset>
  );
  const run = async (callback: () => Promise<any>, message: string) => {
    setBusy(true);
    setError("");
    try {
      await callback();
      onDone(message);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  useEffect(() => {
    if (row?.content_id && ["preview", "edit-content"].includes(type))
      api<Row[]>(`/contents/${row.content_id}/revisions`)
        .then(setHistory)
        .catch((e) => setError(e.message));
  }, [row?.content_id, type]);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    await run(
      async () => {
        if (type === "generate") {
          await api(
            "/runs",
            "POST",
            {
              brand_id: brand!.id,
              campaign_id: fields.campaign_id || null,
              topic: fields.topic,
            },
            key,
          );
          setPage("studio");
        } else if (type === "new-content" || type === "edit-content") {
          const draft = {
            hook: fields.hook,
            body: fields.body,
            cta: fields.cta,
            source_refs: refs,
          };
          if (row)
            await api(`/contents/${row.content_id}/revisions`, "POST", draft);
          else await api("/contents", "POST", { brand_id: brand!.id, draft });
        } else if (type === "schedule") {
          const instant = fromZonedTime(fields.due_at, zone);
          if (Number.isNaN(instant.getTime()))
            throw new Error("Thời điểm không hợp lệ.");
          await api(
            "/publications",
            "POST",
            {
              revision_id: row!.id,
              account_id: fields.account_id,
              due_at: instant.toISOString(),
              intent: key,
            },
            key,
          );
          setPage("calendar");
        } else if (type === "brand") {
          const config = {
            name: fields.name,
            industry: fields.industry,
            voice: fields.voice,
            audience: fields.audience,
            color: fields.color,
            timezone: fields.timezone,
            mode: fields.mode,
            monthly_budget_micros: Math.round(Number(fields.budget) * 1000000),
            expected_version: row?.version || 0,
            require_separate_reviewer: !!fields.require_separate_reviewer,
          };
          await api(
            row ? `/brands/${row.id}` : "/brands",
            row ? "PUT" : "POST",
            config,
          );
          setPage("brand");
        } else if (type === "knowledge")
          await api(`/brands/${brand!.id}/knowledge`, "POST", {
            title: fields.title,
            body: fields.body,
            source_url: fields.source_url || null,
            effective_until: fields.effective_until
              ? fromZonedTime(fields.effective_until, zone).toISOString()
              : null,
          });
        else if (type === "campaign")
          await api("/campaigns", "POST", {
            brand_id: brand!.id,
            name: fields.name,
            objective: fields.objective,
            pillars: fields.pillars
              .split("\n")
              .map((p: string) => p.trim())
              .filter(Boolean),
            generation_enabled: !!fields.generation_enabled,
            daily_generation_time: fields.daily_generation_time,
          });
        else if (type === "lead") {
          if (row)
            await api(`/leads/${row.id}`, "PATCH", {
              stage: fields.stage,
              assigned_user: fields.assigned_user || null,
              next_action: fields.next_action || null,
              expected_version: row.version,
            });
          else
            await api("/leads", "POST", {
              brand_id: brand!.id,
              lead: {
                name: fields.name,
                contact: fields.contact,
                interest: fields.interest,
                purpose: fields.purpose,
                consent: fields.consent,
                reference: null,
              },
            });
        } else if (type === "connection")
          await api(`/brands/${brand!.id}/accounts`, "POST", {
            page_id: fields.page_id,
            page_token: fields.page_token,
          });
        else if (type === "reconcile")
          await api(`/publications/${row!.id}/reconcile`, "POST", {
            remote_post_id: fields.remote_post_id,
          });
      },
      (
        {
          generate:
            "Tác vụ đã xếp hàng. Bản nháp sẽ xuất hiện trong Content Studio.",
          "new-content": "Đã lưu bản nháp.",
          "edit-content":
            "Đã tạo revision mới. Cần gửi duyệt lại trước khi đăng.",
          schedule: "Đã lưu lịch xuất bản.",
          brand: "Đã lưu hồ sơ thương hiệu.",
          knowledge: "Nguồn đã nhập và đang chờ duyệt.",
          campaign: "Đã tạo chiến dịch.",
          lead: "Đã lưu thông tin khách hàng.",
          connection: "Đã xác minh và kết nối Meta Page.",
          reconcile: "Đã xác nhận bài đăng bằng remote ID và nội dung.",
        } as Record<string, string>
      )[type] || "Đã cập nhật.",
    );
  }
  const footer = (label: string) => (
    <div className="modal-actions">
      <Button secondary onClick={onClose}>
        Đóng
      </Button>
      <Button type="submit" disabled={busy}>
        {busy ? (
          <LoaderCircle className="spin" size={16} />
        ) : (
          <Check size={16} />
        )}{" "}
        {label}
      </Button>
    </div>
  );
  return (
    <Modal
      title={titles[type] || "Nội dung"}
      sub={brand?.name}
      onClose={onClose}
      wide={["preview", "edit-content", "new-content"].includes(type)}
    >
      {error && (
        <div className="error-box" role="alert">
          {error}
        </div>
      )}
      {type === "preview" && row ? (
        <div className="preview-layout">
          <div className="facebook-preview">
            <div className="facebook-profile">
              <span
                className="brand-avatar"
                style={{ background: brand?.color }}
              >
                P
              </span>
              <div>
                <b>{brand?.name}</b>
                <small>Bản xem trước · Facebook Page</small>
              </div>
              <MoreHorizontal size={18} />
            </div>
            <div className="facebook-copy">
              <strong>{row.hook}</strong>
              <p>{row.body}</p>
              <p>{row.cta}</p>
            </div>
            <img
              className="preview-image"
              src={`/v1/revisions/${row.id}/image`}
              alt={row.hook}
            />
            <div className="facebook-footer">
              <span>Thích</span>
              <span>Bình luận</span>
              <span>Chia sẻ</span>
            </div>
          </div>
          <div className="review-details">
            <div className="section-heading">
              <h3>Kiểm tra trước khi đăng</h3>
              <Badge status={row.status} />
            </div>
            <div className="detail-line">
              <span>Revision</span>
              <b>v{row.revision_no}</b>
            </div>
            <div className="detail-line">
              <span>Ảnh</span>
              <b>1080 × 1080 · PNG</b>
            </div>
            <h4>Nguồn tham chiếu</h4>
            {row.source_refs.length ? (
              row.source_refs.map((id: string) => {
                const source = data.knowledge.find((k: Row) => k.id === id);
                return (
                  <details className="evidence" key={id}>
                    <summary>
                      <BookOpen size={15} />
                      {source?.title || "Nguồn không còn hiển thị"}
                    </summary>
                    <p>{source?.body}</p>
                  </details>
                );
              })
            ) : (
              <p className="error-text">
                Chưa có evidence. Kiểm tra mọi claim trước khi duyệt.
              </p>
            )}
            {row.risk_flags.length > 0 && (
              <div className="risk-box">
                <CircleAlert size={17} />
                <div>
                  <b>Cần kiểm tra kỹ</b>
                  <p>
                    {row.risk_flags
                      .map(
                        (r: string) =>
                          (
                            ({
                              LEGAL_CONTENT: "Nội dung pháp lý",
                              COMMERCIAL_CLAIM: "Giá, ưu đãi hoặc cam kết",
                              MISSING_EVIDENCE: "Thiếu nguồn",
                            }) as Record<string, string>
                          )[r] || r,
                      )
                      .join(" · ")}
                  </p>
                </div>
              </div>
            )}
            <div className="revision-history">
              <h4>Lịch sử phiên bản</h4>
              {history.map((h) => (
                <span key={h.id}>
                  v{h.revision_no} · {stateLabels[h.status]} ·{" "}
                  {dt(h.created_at, zone)}
                </span>
              ))}
            </div>
            {row.status === "IN_REVIEW" && has("REVIEWER") && (
              <>
                {input("note", "Ghi chú người duyệt", {
                  multiline: true,
                  required: false,
                  max: 2000,
                })}
                <div className="review-actions">
                  <Button
                    secondary
                    disabled={busy}
                    onClick={() =>
                      run(
                        () =>
                          api(`/revisions/${row.id}/approvals`, "POST", {
                            decision: "CHANGES_REQUESTED",
                            note: fields.note,
                            expected_hash: row.snapshot_hash,
                          }),
                        "Đã yêu cầu chỉnh sửa.",
                      )
                    }
                  >
                    Yêu cầu sửa
                  </Button>
                  <Button
                    disabled={busy}
                    onClick={() =>
                      run(
                        () =>
                          api(`/revisions/${row.id}/approvals`, "POST", {
                            decision: "APPROVED",
                            note: fields.note,
                            expected_hash: row.snapshot_hash,
                          }),
                        "Đã duyệt revision này.",
                      )
                    }
                  >
                    <CheckCheck size={16} />
                    Duyệt bài
                  </Button>
                </div>
              </>
            )}
            {row.status === "DRAFT" && has("MARKETER") && (
              <Button
                disabled={busy}
                onClick={() =>
                  run(
                    () => api(`/revisions/${row.id}/submit`, "POST", {}),
                    "Đã gửi bản nháp vào hàng đợi duyệt.",
                  )
                }
              >
                <Send size={16} />
                Gửi duyệt
              </Button>
            )}
            {row.status === "APPROVED" && (
              <div className="notice">
                <ShieldCheck size={16} />
                Để lên lịch, mở bài trong Content Studio và chọn “Lên lịch”.
              </div>
            )}
          </div>
        </div>
      ) : (
        <form onSubmit={submit}>
          {type === "generate" && (
            <>
              <div className="notice">
                <Sparkles size={19} />
                {data.aiStatus?.accounts?.some((a: Row) => a.active)
                  ? "Using ChatGPT plan · Bản nháp dùng tài khoản đã chọn và nguồn đã duyệt."
                  : data.aiStatus?.api_configured
                    ? "Bản nháp dùng OpenAI API và nguồn đã duyệt còn hiệu lực."
                    : "Bản nháp dùng template demo trích nguồn đã duyệt còn hiệu lực."}
              </div>
              {input("topic", "Chủ đề bạn muốn chia sẻ", {
                max: 250,
                placeholder: "Ví dụ: Chuẩn bị trước buổi trao đổi đầu tiên",
              })}
              <label>
                Chiến dịch
                <select
                  value={fields.campaign_id}
                  onChange={(e) => set("campaign_id", e.target.value)}
                >
                  <option value="">Nội dung độc lập</option>
                  {data.campaigns.map((c: Row) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
              <div className="generation-steps">
                <span>
                  <BookOpen size={17} />
                  Nạp tri thức
                </span>
                <ArrowRight size={13} />
                <span>
                  <Sparkles size={17} />
                  Tạo bản nháp
                </span>
                <ArrowRight size={13} />
                <span>
                  <CheckCheck size={17} />
                  Chờ duyệt
                </span>
              </div>
              {footer("Bắt đầu tạo nội dung")}
            </>
          )}
          {["new-content", "edit-content"].includes(type) && (
            <>
              <div className="editor-layout">
                <div>
                  {row && (
                    <div className="notice">
                      <FileText size={17} />
                      Lưu chỉnh sửa tạo revision mới. Approval và lịch cũ vẫn
                      gắn với phiên bản cũ.
                    </div>
                  )}
                  {input("hook", "Tiêu đề / Hook", { max: 250 })}
                  {input("body", "Nội dung bài viết", {
                    multiline: true,
                    max: 10000,
                  })}
                  {input("cta", "Lời kêu gọi hành động", {
                    required: false,
                    max: 1000,
                  })}
                  {sources}
                </div>
                <aside className="editor-side">
                  <span className="eyebrow">ASSET THƯƠNG HIỆU</span>
                  {row ? (
                    <img
                      src={`/v1/revisions/${row.id}/image`}
                      alt="Ảnh của revision hiện tại"
                    />
                  ) : (
                    <div className="template-placeholder">
                      <Image size={35} />
                      <h3>Ảnh được render khi lưu</h3>
                      <p>
                        Template dùng tiêu đề, tên thương hiệu và màu chủ đạo.
                      </p>
                    </div>
                  )}
                  <p>
                    Ảnh thuộc revision hiện tại. Khi lưu, ảnh mới được render và
                    gắn với revision mới.
                  </p>
                  {row && (
                    <>
                      <Badge status={row.status} />
                      {row.status === "DRAFT" && has("MARKETER") && (
                        <Button
                          secondary
                          disabled={busy}
                          onClick={() =>
                            run(
                              () =>
                                api(`/revisions/${row.id}/submit`, "POST", {}),
                              "Đã gửi bản nháp hiện tại vào hàng đợi duyệt.",
                            )
                          }
                        >
                          <Send size={15} />
                          Gửi bản hiện tại duyệt
                        </Button>
                      )}
                      {row.status === "APPROVED" && has("PUBLISHER") && (
                        <Button
                          secondary
                          onClick={() => {
                            onClose();
                            setPage("calendar");
                            window.dispatchEvent(
                              new CustomEvent("schedule-revision", {
                                detail: row,
                              }),
                            );
                          }}
                        >
                          <CalendarDays size={15} />
                          Lên lịch bản đã duyệt
                        </Button>
                      )}
                    </>
                  )}
                </aside>
              </div>
              {footer(row ? "Lưu revision mới" : "Lưu bản nháp")}
            </>
          )}
          {type === "schedule" && (
            <>
              <div className="notice">
                <ShieldCheck size={17} />
                Xuất bản đúng revision đã duyệt. Page thử nghiệm chỉ mô phỏng
                xác nhận đăng.
              </div>
              <h3 className="schedule-hook">{row?.hook}</h3>
              <label>
                Facebook Page
                <select
                  value={fields.account_id}
                  onChange={(e) => set("account_id", e.target.value)}
                  required
                >
                  {data.accounts.map((a: Row) => (
                    <option key={a.id} value={a.id}>
                      {a.name} {a.provider === "DEMO" ? "(Thử nghiệm)" : ""}
                    </option>
                  ))}
                </select>
              </label>
              {!data.accounts.length && (
                <p className="error-text">
                  Cần kết nối Page trong màn Thương hiệu trước khi lập lịch.
                </p>
              )}
              {input("due_at", `Thời điểm đăng · ${zone}`, {
                type: "datetime-local",
              })}
              <small className="muted">
                Trong 6 ngày tới. Hệ thống lưu UTC và kiểm tra approval lần cuối
                trước khi gửi.
              </small>
              {footer("Xác nhận lịch đăng")}
            </>
          )}
          {type === "brand" && (
            <>
              <div className="form-two">
                {input("name", "Tên thương hiệu", { max: 150 })}
                {input("industry", "Lĩnh vực", { required: false, max: 500 })}
              </div>
              {input("voice", "Giọng văn", {
                multiline: true,
                required: false,
                max: 2000,
              })}
              {input("audience", "Khách hàng mục tiêu", {
                multiline: true,
                required: false,
                max: 1000,
              })}
              <div className="form-two">
                {input("color", "Màu thương hiệu", { type: "color" })}
                {input("timezone", "Múi giờ IANA")}
              </div>
              <div className="form-two">
                <label>
                  Chế độ tự động
                  <select
                    value={fields.mode}
                    onChange={(e) => set("mode", e.target.value)}
                  >
                    <option value="ASSISTED">ASSISTED · Người duyệt</option>
                    <option value="RULED_AUTOPILOT">
                      RULED · Template demo có nguồn
                    </option>
                  </select>
                </label>
                {input("budget", "Ngân sách AI tháng (USD)", {
                  type: "number",
                })}
              </div>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={!!fields.require_separate_reviewer}
                  onChange={(e) =>
                    set("require_separate_reviewer", e.target.checked)
                  }
                />
                Yêu cầu người duyệt khác người sửa nội dung cuối cùng.
              </label>
              <p className="muted">
                Bản nháp do model tạo vẫn chờ người duyệt. Đổi cấu hình làm
                approval theo policy cũ hết hiệu lực.
              </p>
              {footer("Lưu thương hiệu")}
            </>
          )}
          {type === "knowledge" && (
            <>
              {input("title", "Tên nguồn", { max: 200 })}
              {input("body", "Nội dung nguồn", {
                multiline: true,
                max: 200000,
                placeholder:
                  "Dán mô tả dịch vụ, FAQ hoặc quy trình đã được xác minh…",
              })}
              {input("source_url", "URL tham khảo (tùy chọn)", {
                type: "url",
                required: false,
                max: 2000,
              })}
              {input("effective_until", `Hết hiệu lực (tùy chọn) · ${zone}`, {
                type: "datetime-local",
                required: false,
              })}
              <div className="notice">
                <ShieldCheck size={17} />
                Nguồn mới ở trạng thái chưa duyệt. Reviewer hoặc Owner kiểm tra
                trước khi AI sử dụng.
              </div>
              {footer("Nhập nguồn kiến thức")}
            </>
          )}
          {type === "campaign" && (
            <>
              {input("name", "Tên chiến dịch", { max: 200 })}
              {input("objective", "Mục tiêu", { multiline: true, max: 500 })}
              {input("pillars", "Trụ cột nội dung · mỗi dòng một chủ đề", {
                multiline: true,
                placeholder:
                  "Kiến thức thương hiệu\nQuy trình tiếp nhận\nCâu hỏi thường gặp",
              })}
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={fields.generation_enabled}
                  onChange={(e) => set("generation_enabled", e.target.checked)}
                />
                Tự tạo một bản nháp mỗi ngày, trong giới hạn ngân sách.
              </label>
              {input("daily_generation_time", `Giờ tạo bản nháp · ${zone}`, {
                type: "time",
              })}
              {footer("Tạo chiến dịch")}
            </>
          )}
          {type === "lead" && (
            <>
              {row ? (
                <>
                  <label>
                    Trạng thái
                    <select
                      value={fields.stage}
                      onChange={(e) => set("stage", e.target.value)}
                    >
                      {[
                        "NEW",
                        "QUALIFIED",
                        "CONTACTED",
                        "WON",
                        "LOST",
                        "DISQUALIFIED",
                      ].map((s) => (
                        <option key={s} value={s}>
                          {stateLabels[s]}
                        </option>
                      ))}
                    </select>
                  </label>
                  {input("assigned_user", "Email người phụ trách (tùy chọn)", {
                    type: "email",
                    required: false,
                    max: 250,
                  })}
                  {input("next_action", "Bước chăm sóc tiếp theo", {
                    multiline: true,
                    required: false,
                    max: 2000,
                  })}
                </>
              ) : (
                <>
                  {input("name", "Tên khách hàng", { max: 150 })}
                  {input("contact", "Email hoặc số điện thoại", { max: 200 })}
                  {input("interest", "Nhu cầu", {
                    multiline: true,
                    required: false,
                    max: 2000,
                  })}
                  {input("purpose", "Mục đích sử dụng thông tin", { max: 500 })}
                  <label className="checkbox">
                    <input
                      type="checkbox"
                      checked={fields.consent}
                      onChange={(e) => set("consent", e.target.checked)}
                      required
                    />
                    Khách hàng đã đồng ý cung cấp thông tin để liên hệ theo mục
                    đích trên.
                  </label>
                </>
              )}
              {footer("Lưu thông tin")}
            </>
          )}
          {type === "connection" && (
            <>
              <div className="notice">
                <Facebook size={18} />
                Dùng Page access token thuộc app đã cấu hình. Backend xác minh
                Page và các quyền trước khi lưu.
              </div>
              {input("page_id", "Facebook Page ID")}
              {input("page_token", "Page access token", {
                type: "password",
                max: 5000,
              })}
              <p className="muted">
                Token được mã hóa ở backend. Đăng thật chỉ chạy khi người vận
                hành đã bật cấu hình LIVE_PUBLISHING.
              </p>
              {footer("Xác minh & kết nối")}
            </>
          )}
          {type === "reconcile" && (
            <>
              <div className="risk-box">
                <CircleAlert size={20} />
                <div>
                  <b>Provider có thể đã nhận yêu cầu trước timeout.</b>
                  <p>
                    Không gửi lại publication này. Kiểm tra Page và cung cấp ID
                    bài đã đăng để xác nhận.
                  </p>
                </div>
              </div>
              {input("remote_post_id", "Remote post ID · PageID_PostID")}
              <p className="muted">
                Hệ thống kiểm tra đúng Page và nội dung snapshot. Không tìm thấy
                bài thì cần xử lý bằng runbook.
              </p>
              {footer("Kiểm tra bài trên Meta")}
            </>
          )}
        </form>
      )}
    </Modal>
  );
}
