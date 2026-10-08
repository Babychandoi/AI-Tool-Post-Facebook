import { Button, Empty } from "./components/ui";
import { Login, LeadForm } from "./components/Auth";
import { ActionModal } from "./components/ActionModal";
import { AiConnections } from "./components/AiConnections";
import { Autopost } from "./components/Autopost";
import { useState, useEffect, useCallback } from "react";
import type { ReactNode } from "react";
import {
  LayoutDashboard,
  Sparkles,
  CalendarDays,
  CheckCheck,
  Megaphone,
  BookOpen,
  Users,
  ChartNoAxesCombined,
  Settings2,
  ChevronDown,
  Plus,
  ArrowUpRight,
  ArrowRight,
  Bell,
  Search,
  PanelLeftClose,
  LogOut,
  CircleHelp,
  X,
  Check,
  LoaderCircle,
  Facebook,
  Pause,
  Play,
  FileText,
  Clock3,
  ShieldCheck,
  Link2,
  Send,
  Image,
  ExternalLink,
  MoreHorizontal,
  RefreshCw,
  CircleAlert,
  Copy,
  Trash2,
} from "lucide-react";
import { api, login, initCsrf, dt, money, Badge, stateLabels } from "./api";
import type { Row } from "./api";
import { formatInTimeZone, fromZonedTime } from "date-fns-tz";
import "./styles.css";

export type Page =
  | "overview"
  | "autopost"
  | "studio"
  | "review"
  | "calendar"
  | "campaigns"
  | "brand"
  | "leads"
  | "reports"
  | "ai"
  | "operations";
const nav: { id: Page; name: string; icon: typeof LayoutDashboard }[] = [
  { id: "overview", name: "Tổng quan", icon: LayoutDashboard },
  { id: "autopost", name: "Đăng tự động", icon: Clock3 },
  { id: "studio", name: "Content Studio", icon: Sparkles },
  { id: "review", name: "Hàng đợi duyệt", icon: CheckCheck },
  { id: "calendar", name: "Lịch nội dung", icon: CalendarDays },
  { id: "campaigns", name: "Chiến dịch", icon: Megaphone },
  { id: "brand", name: "Thương hiệu & tri thức", icon: BookOpen },
  { id: "leads", name: "Khách hàng", icon: Users },
  { id: "reports", name: "Báo cáo", icon: ChartNoAxesCombined },
  { id: "ai", name: "Kết nối AI", icon: Link2 },
  { id: "operations", name: "Vận hành", icon: Settings2 },
];
const defaults = {
  dash: null,
  contents: [],
  knowledge: [],
  accounts: [],
  calendar: [],
  campaigns: [],
  runs: [],
  leads: [],
  reports: null,
  operations: null,
} as Record<string, any>;
export default function App() {
  if (window.location.pathname.startsWith("/f/")) return <LeadForm />;
  return <Workspace />;
}
function Workspace() {
  const [me, setMe] = useState<Row | null>(null),
    [aiStatus, setAiStatus] = useState<Row | null>(null),
    [authReady, setAuthReady] = useState(false),
    [brands, setBrands] = useState<Row[]>([]),
    [brandId, setBrandId] = useState(""),
    [page, setPage] = useState<Page>(
      new URLSearchParams(window.location.search).has("chatgpt") &&
        sessionStorage.getItem("chatgpt-return-to") === "autopost"
        ? "autopost"
        : nav.some((n) => "#" + n.id === window.location.hash)
          ? (window.location.hash.slice(1) as Page)
          : "overview",
    ),
    [data, setData] = useState(defaults),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(false),
    [refresh, setRefresh] = useState(0),
    [search, setSearch] = useState(""),
    [toast, setToast] = useState(""),
    [modal, setModal] = useState<{ type: string; row?: Row } | null>(null),
    [collapsed, setCollapsed] = useState(false);
  const has = (...roles: string[]) =>
    !!me &&
    (me.role === "OWNER" || me.role === "ADMIN" || roles.includes(me.role));
  const brand = brands.find((b) => b.id === brandId),
    zone = brand?.timezone || "Asia/Ho_Chi_Minh";
  const notify = useCallback((message: string) => {
    setToast(message);
    setTimeout(() => setToast(""), 5000);
  }, []);
  const reload = () => setRefresh((x) => x + 1);
  const activeAi = aiStatus?.accounts?.find((a: Row) => a.active);
  useEffect(() => {
    if (me && has("MARKETER"))
      api("/ai/chatgpt")
        .then(setAiStatus)
        .catch(() => {});
  }, [me, refresh]);
  const open = (type: string, row?: Row) => setModal({ type, row });
  useEffect(() => {
    api("/auth/me")
      .then(setMe)
      .catch(() => {})
      .finally(() => setAuthReady(true));
    const expired = () => setMe(null);
    window.addEventListener("session-expired", expired);
    return () => window.removeEventListener("session-expired", expired);
  }, []);
  useEffect(() => {
    if (!me) return;
    let alive = true;
    api<Row[]>("/brands")
      .then((rows) => {
        if (alive) {
          setBrands(rows);
          setBrandId((id) =>
            rows.some((b) => b.id === id) ? id : rows[0]?.id || "",
          );
        }
      })
      .catch((e) => setError(e.message));
    return () => {
      alive = false;
    };
  }, [me, refresh]);
  useEffect(() => {
    if (!me || !brandId) return;
    let alive = true;
    setLoading(true);
    const q = `?brand_id=${brandId}`;
    const requests: { key: string; path: string }[] = [
      { key: "dash", path: "/dashboard" + q },
      { key: "contents", path: "/contents" + q },
      { key: "knowledge", path: `/brands/${brandId}/knowledge` },
      { key: "accounts", path: `/brands/${brandId}/accounts` },
      { key: "calendar", path: "/calendar" + q },
      { key: "campaigns", path: "/campaigns" + q },
      { key: "runs", path: "/runs" + q },
      { key: "reports", path: "/reports" + q },
    ];
    if (has("SALES", "ANALYST"))
      requests.push({ key: "leads", path: "/leads" + q });
    if (has()) requests.push({ key: "operations", path: "/operations" + q });
    Promise.allSettled(requests.map((r) => api(r.path))).then((results) => {
      if (!alive) return;
      let errors: string[] = [];
      const next = { ...defaults };
      results.forEach((r, i) => {
        if (r.status === "fulfilled")
          next[requests[i].key] =
            requests[i].key === "leads" ? r.value.items : r.value;
        else errors.push(r.reason.message);
      });
      setData(next);
      setError(errors.join(" · "));
      setLoading(false);
    });
    return () => {
      alive = false;
    };
  }, [me, brandId, refresh]);
  useEffect(() => {
    if (!me || !brandId) return;
    const active =
      data.runs.some((r: Row) => ["QUEUED", "RUNNING"].includes(r.status)) ||
      data.calendar.some((p: Row) =>
        ["DISPATCHING", "QUEUED", "RETRY_WAIT"].includes(p.status),
      );
    const timer = setTimeout(reload, error ? 45000 : active ? 3500 : 30000);
    return () => clearTimeout(timer);
  }, [me, brandId, data, error]);
  useEffect(() => {
    setSearch("");
    if (!new URLSearchParams(window.location.search).has("chatgpt"))
      window.history.replaceState(
        null,
        "",
        window.location.pathname + "#" + page,
      );
  }, [page]);
  useEffect(() => {
    const handler = (e: Event) =>
      setModal({ type: "schedule", row: (e as CustomEvent).detail });
    window.addEventListener("schedule-revision", handler);
    return () => window.removeEventListener("schedule-revision", handler);
  }, []);
  async function action(
    path: string,
    method = "POST",
    body?: unknown,
    message = "Đã cập nhật.",
  ) {
    try {
      await api(path, method, body);
      reload();
      notify(message);
    } catch (e) {
      notify((e as Error).message);
    }
  }
  const contents: Row[] = data.contents.filter((c: Row) =>
    c.hook.toLowerCase().includes(search.toLowerCase()),
  );
  const close = useCallback(() => setModal(null), []);
  const contentCards = (items: Row[], review = false) =>
    items.length ? (
      <div className="content-grid">
        {items.map((c) => (
          <article className="content-card" key={c.id}>
            <div className="content-image" onClick={() => open("preview", c)}>
              <img src={`/v1/revisions/${c.id}/image`} alt={c.hook} />
              <span className="channel-pill">
                <Facebook size={13} /> Facebook
              </span>
              <button
                className="image-more icon-button"
                aria-label={`Xem ${c.hook}`}
              >
                <MoreHorizontal size={20} />
              </button>
            </div>
            <div className="content-card-body">
              <div className="card-meta">
                <Badge status={c.status} />
                <span>v{c.revision_no}</span>
              </div>
              <h3>{c.hook}</h3>
              <p>{c.body}</p>
              <div className="content-card-foot">
                <span>
                  <BookOpen size={13} />
                  {c.source_refs.length} nguồn · <Image size={13} /> 1 ảnh
                </span>
                <button
                  onClick={() =>
                    open(
                      review || !has("MARKETER") ? "preview" : "edit-content",
                      c,
                    )
                  }
                >
                  {review ? "Xem & duyệt" : "Mở bài"}
                  <ArrowUpRight size={15} />
                </button>
              </div>
            </div>
          </article>
        ))}
      </div>
    ) : (
      <Empty
        title={
          review
            ? "Không có bài đang chờ duyệt"
            : "Nội dung tiếp theo bắt đầu ở đây"
        }
        text={
          review
            ? "Những bản nháp gửi duyệt sẽ xuất hiện tại đây."
            : "Tạo bản nháp từ nguồn kiến thức đã duyệt của thương hiệu."
        }
        action={
          has("MARKETER") && (
            <Button onClick={() => open("generate")}>
              <Sparkles size={17} />
              Tạo nội dung
            </Button>
          )
        }
      />
    );
  if (!authReady)
    return (
      <div className="boot">
        <LoaderCircle className="spin" />
        Đang mở không gian thương hiệu…
      </div>
    );
  if (!me)
    return (
      <Login
        onLogin={(m) => {
          setMe(m);
          initCsrf();
        }}
      />
    );
  return (
    <div className={`workspace ${collapsed ? "sidebar-collapsed" : ""}`}>
      <aside className="sidebar">
        <div className="logo-inline">
          <div className="logo-mark small">
            m<span>✦</span>
          </div>
          <div>
            <b>AI Marketing</b>
            <small>YOUR BRAND, IN MOTION</small>
          </div>
        </div>
        <div className="workspace-picker">
          <span className="workspace-avatar">P</span>
          <div>
            <strong>Không gian thương hiệu</strong>
            <small>Marketing workspace</small>
          </div>
          <ChevronDown size={15} />
        </div>
        <div className="nav-label">KHÔNG GIAN LÀM VIỆC</div>
        <nav>
          {nav
            .filter((n) => n.id !== "operations" || has())
            .filter((n) => n.id !== "ai" || has("MARKETER"))
            .filter((n) => n.id !== "leads" || has("SALES", "ANALYST"))
            .map((n) => (
              <button
                className={page === n.id ? "active" : ""}
                key={n.id}
                onClick={() => setPage(n.id)}
              >
                <n.icon size={19} />
                <span>{n.name}</span>
                {n.id === "review" && data.dash?.counts.pending_review > 0 && (
                  <em>{data.dash.counts.pending_review}</em>
                )}
              </button>
            ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="assistant-card">
            <span className="assistant-spark">
              <Sparkles size={18} />
            </span>
            <strong>Sáng tạo có cơ sở</strong>
            <p>Mỗi ý tưởng bắt đầu từ tri thức thương hiệu của bạn.</p>
            <button onClick={() => setPage("brand")}>
              Quản lý kho tri thức <ArrowRight size={14} />
            </button>
          </div>
          <button
            className="help"
            onClick={() =>
              notify(
                "Quy trình: duyệt nguồn → tạo bài → duyệt revision → lên lịch → theo dõi lead.",
              )
            }
          >
            <CircleHelp size={18} />
            Hướng dẫn sử dụng
            <ArrowUpRight size={15} />
          </button>
          <button
            className="profile"
            onClick={() =>
              action("/auth/logout", "POST", undefined, "Đã đăng xuất.").then(
                () => setMe(null),
              )
            }
          >
            <span className="avatar">{me.name.slice(0, 2).toUpperCase()}</span>
            <span>
              <b>{me.name}</b>
              <small>{me.role}</small>
            </span>
            <LogOut size={16} />
          </button>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <button
            className="icon-button"
            aria-label="Thu gọn điều hướng"
            onClick={() => setCollapsed(!collapsed)}
          >
            <PanelLeftClose size={20} />
          </button>
          <div className="breadcrumb">
            Không gian làm việc <span>/</span>
            <b>{nav.find((n) => n.id === page)?.name}</b>
          </div>
          <div className="topbar-right">
            <span className="live-indicator">
              <i />
              {data.dash?.demo
                ? "Chế độ thử nghiệm"
                : "Workspace đang hoạt động"}
            </span>
            <button
              className="icon-button notification"
              aria-label="Các việc cần xử lý"
              onClick={() => setPage("review")}
            >
              <Bell size={20} />
              {data.dash?.counts.attention > 0 && <i />}
            </button>
            <span className="avatar">{me.name.slice(0, 2).toUpperCase()}</span>
          </div>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <span className="eyebrow">
                {new Intl.DateTimeFormat("vi-VN", {
                  timeZone: zone,
                  weekday: "long",
                  day: "numeric",
                  month: "long",
                  year: "numeric",
                }).format(new Date())}
              </span>
              <h1>
                {page === "overview"
                  ? `Chào ${me.name}, một ngày sáng tạo mới`
                  : nav.find((n) => n.id === page)?.name}
              </h1>
              <p>
                {page === "overview"
                  ? "Cùng đưa thương hiệu đến gần hơn với khách hàng của bạn."
                  : (
                      {
                        studio:
                          "Từ nguồn tri thức đến những câu chuyện có giá trị.",
                        review:
                          "Kiểm tra nguồn, rủi ro và phiên bản trước khi xuất bản.",
                        calendar:
                          "Đúng nội dung, đúng thời điểm, đúng trạng thái.",
                        campaigns: "Một định hướng rõ ràng cho mỗi nội dung.",
                        brand: "Nền tảng cho nội dung nhất quán và có căn cứ.",
                        leads: "Mỗi kết nối là khởi đầu của một cuộc trao đổi.",
                        reports:
                          "Kết quả có nguồn và thời gian cập nhật rõ ràng.",
                        operations:
                          "Theo dõi tác vụ, lần đăng và lịch sử thao tác.",
                        ai: "Kết nối tài khoản ChatGPT và lựa chọn model tạo nội dung.",
                        autopost:
                          "Kết nối, chọn nội dung và đặt lịch hằng ngày tại một chỗ.",
                      } as Record<string, string>
                    )[page]}
              </p>
            </div>
            <div className="heading-actions">
              {brands.length > 0 && (
                <label className="brand-select">
                  <span
                    className="brand-dot"
                    style={{ background: brand?.color }}
                  />
                  <select
                    aria-label="Thương hiệu"
                    value={brandId}
                    onChange={(e) => {
                      setBrandId(e.target.value);
                      setData(defaults);
                    }}
                  >
                    {brands.map((b) => (
                      <option key={b.id} value={b.id}>
                        {b.name}
                      </option>
                    ))}
                  </select>
                  <ChevronDown size={14} />
                </label>
              )}
              {has("MARKETER") &&
                brand &&
                ["overview", "studio"].includes(page) && (
                  <Button onClick={() => open("generate")}>
                    <Plus size={17} />
                    Tạo nội dung
                  </Button>
                )}
            </div>
          </div>
          {error && (
            <div className="error-box" role="alert">
              <CircleAlert size={17} />
              {error}
              <button onClick={reload}>Thử lại</button>
            </div>
          )}
          {activeAi && ["overview", "studio", "campaigns"].includes(page) && (
            <div className="notice">
              <Link2 size={17} />
              <span>
                {activeAi.status === "CONNECTED"
                  ? `Using ChatGPT plan · ${activeAi.account_email} · ${activeAi.model || "Chưa chọn model"}`
                  : "Kết nối ChatGPT cần được cập nhật trước khi tạo nội dung."}
              </span>
              <button className="text-button" onClick={() => setPage("ai")}>
                Kết nối AI
              </button>
              <a
                className="text-button"
                href="https://chatgpt.com/settings/usage"
                target="_blank"
                rel="noreferrer"
              >
                Manage usage
              </a>
            </div>
          )}
          {loading && !data.dash && (
            <div className="loading">
              <LoaderCircle className="spin" />
              Đang tải dữ liệu…
            </div>
          )}
          {!brand && !loading ? (
            <Empty
              title="Tạo thương hiệu đầu tiên"
              text="Nhập mục tiêu, giọng văn và nguồn kiến thức để bắt đầu."
              action={
                has() && (
                  <Button onClick={() => open("brand")}>
                    <Plus size={17} />
                    Thêm thương hiệu
                  </Button>
                )
              }
            />
          ) : (
            brand && (
              <>
                {page === "overview" && data.dash && (
                  <>
                    <div className="autopost-entry panel">
                      <div>
                        <Clock3 size={22} />
                        <span>
                          <b>Muốn AI chuẩn bị bài và đăng mỗi ngày?</b>
                          <small>
                            Thiết lập Fanpage, AI, chủ đề, ảnh và giờ đăng tại
                            một chỗ.
                          </small>
                        </span>
                      </div>
                      <Button onClick={() => setPage("autopost")}>
                        Thiết lập đăng tự động
                        <ArrowRight size={16} />
                      </Button>
                    </div>
                    <section className="hero">
                      <div>
                        <span className="hero-tag">
                          <Sparkles size={13} /> KHÔNG GIAN SÁNG TẠO CỦA BẠN
                        </span>
                        <h2>
                          Từ ý tưởng
                          <br />
                          đến kết nối.
                        </h2>
                        <p>
                          AI hỗ trợ nội dung. Bạn nắm quyền quyết định.
                          <br />
                          Giữ chất riêng của thương hiệu trong từng bài viết.
                        </p>
                        <Button
                          onClick={() =>
                            has("MARKETER")
                              ? open("generate")
                              : setPage("studio")
                          }
                        >
                          <Sparkles size={16} />
                          Khởi tạo bản nháp
                          <ArrowRight size={16} />
                        </Button>
                      </div>
                      <div className="orbit">
                        <div className="orbit-ring" />
                        <div className="orbit-ring second" />
                        <div className="orbit-core">
                          <Sparkles size={43} />
                        </div>
                        <div className="orbit-chip chip-one">
                          <BookOpen size={20} />
                          <span>
                            Tri thức thương hiệu<small>Nội dung có nguồn</small>
                          </span>
                          <span className="mini-check">
                            <Check size={12} />
                          </span>
                        </div>
                        <div className="orbit-chip chip-two">
                          <CheckCheck size={20} />
                          <span>
                            Sẵn sàng khi được duyệt
                            <small>Bạn kiểm soát việc xuất bản</small>
                          </span>
                        </div>
                        <span className="orbit-star">✦</span>
                        <span className="orbit-dot" />
                      </div>
                    </section>
                    <section className="stats-grid">
                      {[
                        {
                          label: "Bài chờ duyệt",
                          value: data.dash.counts.pending_review,
                          icon: CheckCheck,
                          tone: "purple",
                          hint: "Cần bạn kiểm tra",
                          dest: "review",
                        },
                        {
                          label: "Bài đã lên lịch",
                          value: data.dash.counts.scheduled,
                          icon: CalendarDays,
                          tone: "blue",
                          hint: "Sẵn sàng cho thời điểm phù hợp",
                          dest: "calendar",
                        },
                        {
                          label: "Bài đã xuất bản",
                          value: data.dash.counts.published,
                          icon: Send,
                          tone: "green",
                          hint: data.dash.demo
                            ? "Kết quả trong chế độ thử nghiệm"
                            : "Đã xác nhận bởi nhà cung cấp",
                          dest: "calendar",
                        },
                        {
                          label: "Khách hàng quan tâm",
                          value: data.dash.counts.leads,
                          icon: Users,
                          tone: "orange",
                          hint: "Thông tin được cung cấp chủ động",
                          dest: has("SALES", "ANALYST") ? "leads" : "reports",
                        },
                      ].map((s) => (
                        <button
                          className="stat-card"
                          key={s.label}
                          onClick={() => setPage(s.dest as Page)}
                        >
                          <span className={`stat-icon ${s.tone}`}>
                            <s.icon size={21} />
                          </span>
                          <ArrowUpRight className="stat-arrow" size={17} />
                          <span className="stat-label">{s.label}</span>
                          <strong>{s.value.toString().padStart(2, "0")}</strong>
                          <span className="stat-hint">{s.hint}</span>
                        </button>
                      ))}
                    </section>
                    <div className="dashboard-bottom">
                      <section className="panel upcoming-panel">
                        <div className="section-heading">
                          <div>
                            <h2>Nội dung đang tiến triển</h2>
                            <p>Những câu chuyện tiếp theo của thương hiệu.</p>
                          </div>
                          <button
                            className="text-button"
                            onClick={() => setPage("studio")}
                          >
                            Xem tất cả
                            <ArrowRight size={14} />
                          </button>
                        </div>
                        <div className="draft-list">
                          {data.contents.slice(0, 3).map((c: Row) => (
                            <button
                              className="draft-row"
                              key={c.id}
                              onClick={() => open("preview", c)}
                            >
                              <img src={`/v1/revisions/${c.id}/image`} alt="" />
                              <div>
                                <strong>{c.hook}</strong>
                                <span>
                                  <Facebook size={12} />
                                  Facebook Page · {c.source_refs.length} nguồn
                                </span>
                              </div>
                              <Badge status={c.status} />
                              <ChevronDown className="rotate" size={17} />
                            </button>
                          ))}
                        </div>
                        {!data.contents.length && (
                          <Empty
                            title="Chưa có nội dung"
                            text="Thêm nguồn kiến thức và khởi tạo bản nháp đầu tiên."
                          />
                        )}
                        <div className="panel-foot">
                          <ShieldCheck size={15} />
                          {data.dash.counts.attention > 0
                            ? `${data.dash.counts.attention} lịch đăng cần kiểm tra`
                            : "Mỗi lần đăng đều đi qua bước kiểm tra cuối."}
                        </div>
                      </section>
                      <section className="panel connection-panel">
                        <div className="section-heading">
                          <div>
                            <h2>Nhịp hoạt động</h2>
                            <p>Thương hiệu trong tầm kiểm soát.</p>
                          </div>
                          <span className="little-dots">•••</span>
                        </div>
                        <div className="brand-health">
                          <span
                            className="brand-avatar"
                            style={{ background: brand.color }}
                          >
                            P
                          </span>
                          <div>
                            <strong>{brand.name}</strong>
                            <span>
                              <i
                                className={
                                  brand.enabled
                                    ? "health-dot"
                                    : "health-dot paused"
                                }
                              />
                              {brand.enabled ? "Đang hoạt động" : "Đã tạm dừng"}
                            </span>
                          </div>
                          {has() && (
                            <button
                              className="icon-button"
                              aria-label={
                                brand.enabled
                                  ? "Tạm dừng thương hiệu"
                                  : "Tiếp tục thương hiệu"
                              }
                              onClick={() =>
                                action(
                                  `/brands/${brand.id}/pause`,
                                  "POST",
                                  { enabled: !brand.enabled },
                                  brand.enabled
                                    ? "Đã chặn tác vụ mới và lần đăng tiếp theo."
                                    : "Đã tiếp tục thương hiệu.",
                                )
                              }
                            >
                              {brand.enabled ? (
                                <Pause size={16} />
                              ) : (
                                <Play size={16} />
                              )}
                            </button>
                          )}
                        </div>
                        <div className="health-line">
                          <span>
                            <Facebook size={16} />
                            Kênh kết nối
                          </span>
                          <b>{data.accounts.length} Page</b>
                        </div>
                        <div className="health-line">
                          <span>
                            <ShieldCheck size={16} />
                            Chế độ duyệt
                          </span>
                          <b>
                            {brand.mode === "ASSISTED"
                              ? "Có người duyệt"
                              : "Theo quy tắc"}
                          </b>
                        </div>
                        <div className="budget-block">
                          <div>
                            <span>Ngân sách AI tháng này</span>
                            <b>{money(data.dash.budget.spent_micros)}</b>
                          </div>
                          <div className="progress-bar">
                            <span
                              style={{
                                width: `${Math.min(100, ((data.dash.budget.spent_micros + data.dash.budget.reserved_micros) / Math.max(brand.monthly_budget_micros, 1)) * 100)}%`,
                              }}
                            />
                          </div>
                          <small>
                            Hạn mức {money(brand.monthly_budget_micros)} · giữ
                            trước {money(data.dash.budget.reserved_micros)}
                          </small>
                        </div>
                        <button
                          className="text-button"
                          onClick={() => setPage("brand")}
                        >
                          Quản lý thương hiệu
                          <ArrowRight size={14} />
                        </button>
                      </section>
                    </div>
                    <section className="activity-strip">
                      <span className="activity-icon">
                        <Clock3 size={18} />
                      </span>
                      <div>
                        <strong>Hoạt động gần đây</strong>
                        <span>
                          {data.dash.activity[0]
                            ? `${activityLabel(data.dash.activity[0].action)} · ${dt(data.dash.activity[0].created_at, zone)}`
                            : "Chưa có thao tác mới."}
                        </span>
                      </div>
                      <button
                        className="text-button"
                        onClick={() =>
                          has() ? setPage("operations") : setPage("studio")
                        }
                      >
                        Xem lịch sử
                        <ArrowRight size={14} />
                      </button>
                    </section>
                  </>
                )}
                {page === "studio" && (
                  <>
                    <div className="toolbar">
                      <div className="tabs">
                        <button className="selected">
                          Tất cả <em>{data.contents.length}</em>
                        </button>
                        <button onClick={() => setPage("review")}>
                          Chờ duyệt{" "}
                          <em>
                            {
                              data.contents.filter(
                                (c: Row) => c.status === "IN_REVIEW",
                              ).length
                            }
                          </em>
                        </button>
                      </div>
                      <div className="search-input">
                        <Search size={16} />
                        <input
                          aria-label="Tìm nội dung"
                          placeholder="Tìm nội dung…"
                          value={search}
                          onChange={(e) => setSearch(e.target.value)}
                        />
                      </div>
                      {has("MARKETER") && (
                        <Button secondary onClick={() => open("new-content")}>
                          <FileText size={16} />
                          Viết thủ công
                        </Button>
                      )}
                    </div>
                    {data.runs
                      .filter((r: Row) =>
                        ["QUEUED", "RUNNING", "FAILED"].includes(r.status),
                      )
                      .slice(0, 3)
                      .map((r: Row) => (
                        <div
                          className={`run-banner ${r.status === "FAILED" ? "failed" : ""}`}
                          key={r.id}
                        >
                          {r.status === "FAILED" ? (
                            <CircleAlert size={19} />
                          ) : (
                            <LoaderCircle className="spin" size={19} />
                          )}
                          <div>
                            <strong>{r.topic}</strong>
                            <span>
                              {r.error ||
                                "Đang tạo bản nháp và ảnh từ kho tri thức đã duyệt…"}
                            </span>
                          </div>
                          <Badge status={r.status} />
                        </div>
                      ))}
                    {contentCards(contents)}
                  </>
                )}
                {page === "review" && (
                  <>
                    <div className="notice">
                      <ShieldCheck size={18} />
                      Approval gắn với đúng revision, ảnh và policy. Bản sửa mới
                      cần được duyệt lại.
                    </div>
                    {contentCards(
                      contents.filter((c) => c.status === "IN_REVIEW"),
                      true,
                    )}
                  </>
                )}
                {page === "calendar" && (
                  <>
                    <div className="calendar-toolbar">
                      <div>
                        <CalendarDays size={20} />
                        <b>Lịch xuất bản</b>
                        <span>{zone}</span>
                      </div>
                      <Button secondary onClick={reload}>
                        <RefreshCw size={15} />
                        Cập nhật
                      </Button>
                    </div>
                    <div className="week-strip">
                      {Array.from({ length: 7 }, (_, i) => {
                        const date = new Date(Date.now() + i * 86400000);
                        const count = data.calendar.filter(
                          (p: Row) =>
                            formatInTimeZone(
                              new Date(p.due_at),
                              zone,
                              "yyyy-MM-dd",
                            ) === formatInTimeZone(date, zone, "yyyy-MM-dd"),
                        ).length;
                        return (
                          <div key={i} className={i === 0 ? "today" : ""}>
                            <span>
                              {new Intl.DateTimeFormat("vi-VN", {
                                timeZone: zone,
                                weekday: "short",
                              }).format(date)}
                            </span>
                            <b>
                              {new Intl.DateTimeFormat("vi-VN", {
                                timeZone: zone,
                                day: "2-digit",
                              }).format(date)}
                            </b>
                            <small>{count ? `${count} bài` : "—"}</small>
                          </div>
                        );
                      })}
                    </div>
                    <section className="panel">
                      {data.calendar.length ? (
                        <div className="publication-list">
                          {data.calendar.map((p: Row) => (
                            <div className="publication-row" key={p.id}>
                              <div className="publication-date">
                                <b>
                                  {formatInTimeZone(
                                    new Date(p.due_at),
                                    zone,
                                    "HH:mm",
                                  )}
                                </b>
                                <span>
                                  {formatInTimeZone(
                                    new Date(p.due_at),
                                    zone,
                                    "dd/MM",
                                  )}
                                </span>
                              </div>
                              <div className="publication-content">
                                <strong>{p.hook}</strong>
                                <span>
                                  <Facebook size={13} />
                                  {p.account_name}
                                  {p.provider === "DEMO" && <em>Thử nghiệm</em>}
                                </span>
                                {p.last_error && (
                                  <p className="error-text">{p.last_error}</p>
                                )}
                                {p.status === "PUBLISHED" && (
                                  <small>
                                    Nguồn lead:{" "}
                                    <a
                                      href={`/f/${brand.public_form_key}?ref=${p.tracking_key}`}
                                      target="_blank"
                                      rel="noreferrer"
                                    >
                                      Mở form theo bài đăng{" "}
                                      <ExternalLink size={11} />
                                    </a>
                                  </small>
                                )}
                              </div>
                              <Badge status={p.status} />
                              <div className="row-actions">
                                {p.remote_url && (
                                  <a
                                    className="icon-button"
                                    href={p.remote_url}
                                    target="_blank"
                                    rel="noreferrer"
                                    aria-label="Xem bài trên Facebook"
                                  >
                                    <ExternalLink size={16} />
                                  </a>
                                )}
                                {has("PUBLISHER") &&
                                  ["QUEUED", "RETRY_WAIT", "BLOCKED"].includes(
                                    p.status,
                                  ) && (
                                    <button
                                      className="icon-button"
                                      aria-label="Hủy lịch"
                                      onClick={() =>
                                        action(
                                          `/publications/${p.id}/cancel`,
                                          "POST",
                                          {},
                                          "Đã hủy lịch nội bộ.",
                                        )
                                      }
                                    >
                                      <Trash2 size={16} />
                                    </button>
                                  )}
                                {has("PUBLISHER") && p.status === "UNKNOWN" && (
                                  <Button
                                    secondary
                                    onClick={() => open("reconcile", p)}
                                  >
                                    Đối soát
                                  </Button>
                                )}
                              </div>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <Empty
                          title="Lịch đăng đang chờ câu chuyện đầu tiên"
                          text="Mở một bài đã duyệt trong Content Studio để chọn Page và thời điểm xuất bản."
                          action={
                            <Button onClick={() => setPage("studio")}>
                              Mở Content Studio
                              <ArrowRight size={16} />
                            </Button>
                          }
                        />
                      )}
                    </section>
                  </>
                )}
                {page === "campaigns" && (
                  <>
                    <div className="section-heading">
                      <div>
                        <h2>Định hướng nội dung</h2>
                        <p>Chiến dịch và trụ cột cho thương hiệu.</p>
                      </div>
                      {has("MARKETER") && (
                        <Button onClick={() => open("campaign")}>
                          <Plus size={16} />
                          Thêm chiến dịch
                        </Button>
                      )}
                    </div>
                    <div className="campaign-grid">
                      {data.campaigns.map((c: Row) => (
                        <article className="panel campaign-card" key={c.id}>
                          <span className="campaign-icon">
                            <Megaphone size={24} />
                          </span>
                          <span className="campaign-status">
                            Đang hoạt động
                          </span>
                          <h2>{c.name}</h2>
                          <p>{c.objective}</p>
                          <div className="pillars">
                            {c.pillars.map((p: string) => (
                              <span key={p}>{p}</span>
                            ))}
                          </div>
                          <div className="campaign-automation">
                            <Clock3 size={14} />
                            <span>
                              {c.generation_enabled
                                ? `Tự tạo mỗi ngày · ${c.daily_generation_time?.slice(0, 5)} (${zone})`
                                : "Tạo bản nháp theo yêu cầu"}
                            </span>
                            {has("MARKETER") && (
                              <button
                                onClick={() =>
                                  action(
                                    `/campaigns/${c.id}/automation`,
                                    "PUT",
                                    {
                                      enabled: !c.generation_enabled,
                                      daily_generation_time:
                                        c.daily_generation_time?.slice(0, 5) ||
                                        "06:00",
                                      expected_version: c.version,
                                    },
                                    c.generation_enabled
                                      ? "Đã tắt lịch tạo bản nháp."
                                      : "Đã bật lịch tạo một bản nháp mỗi ngày.",
                                  )
                                }
                              >
                                <span
                                  className={`toggle ${c.generation_enabled ? "on" : ""}`}
                                />
                              </button>
                            )}
                          </div>
                          <div className="campaign-footer">
                            <small>
                              {data.contents.length} bản nháp trong thương hiệu
                            </small>
                            {has("MARKETER") && (
                              <button
                                className="text-button"
                                onClick={() =>
                                  open("generate", {
                                    campaign_id: c.id,
                                    topic: c.pillars[0] || c.name,
                                  })
                                }
                              >
                                Tạo nội dung
                                <ArrowRight size={15} />
                              </button>
                            )}
                          </div>
                        </article>
                      ))}
                    </div>
                    {!data.campaigns.length && (
                      <Empty
                        title="Chưa có chiến dịch"
                        text="Bắt đầu bằng mục tiêu và những trụ cột nội dung phù hợp."
                      />
                    )}
                  </>
                )}
                {page === "brand" && (
                  <>
                    <div className="brand-layout">
                      <section className="panel brand-profile">
                        <div className="section-heading">
                          <h2>Hồ sơ thương hiệu</h2>
                          {has() && (
                            <button
                              className="text-button"
                              onClick={() => open("brand", brand)}
                            >
                              Chỉnh sửa
                              <ArrowUpRight size={15} />
                            </button>
                          )}
                        </div>
                        <div
                          className="large-brand-avatar"
                          style={{ background: brand.color }}
                        >
                          P<span>✦</span>
                        </div>
                        <h2>{brand.name}</h2>
                        <p>{brand.industry}</p>
                        <dl>
                          <dt>Giọng văn</dt>
                          <dd>{brand.voice || "Chưa cấu hình"}</dd>
                          <dt>Khách hàng mục tiêu</dt>
                          <dd>{brand.audience || "Chưa cấu hình"}</dd>
                          <dt>Chế độ tự động</dt>
                          <dd>
                            {brand.mode === "ASSISTED"
                              ? "ASSISTED · Người duyệt từng bài"
                              : "RULED · Chỉ tự duyệt template trích nguồn trong demo"}
                          </dd>
                          <dt>Policy hiện hành</dt>
                          <dd>Phiên bản {brand.policy_version}</dd>
                        </dl>
                        {has() && (
                          <Button secondary onClick={() => open("brand")}>
                            <Plus size={15} />
                            Thêm thương hiệu
                          </Button>
                        )}
                      </section>
                      <div>
                        <section className="panel knowledge-panel">
                          <div className="section-heading">
                            <div>
                              <h2>Kho tri thức</h2>
                              <p>Nguồn đã duyệt là cơ sở cho bản nháp AI.</p>
                            </div>
                            {has("MARKETER") && (
                              <Button onClick={() => open("knowledge")}>
                                <Plus size={16} />
                                Thêm nguồn
                              </Button>
                            )}
                          </div>
                          {data.knowledge.map((k: Row) => (
                            <div className="knowledge-row" key={k.id}>
                              <span className="document-icon">
                                <FileText size={21} />
                              </span>
                              <div>
                                <strong>{k.title}</strong>
                                <span>
                                  v{k.version} ·{" "}
                                  {k.body.length.toLocaleString("vi-VN")} ký tự
                                  {k.effective_until
                                    ? ` · Hết hạn ${dt(k.effective_until, zone)}`
                                    : ""}
                                </span>
                                {k.source_url && (
                                  <a
                                    href={
                                      /^https?:\/\//.test(k.source_url)
                                        ? k.source_url
                                        : "#"
                                    }
                                    target="_blank"
                                    rel="noreferrer"
                                  >
                                    Nguồn tham khảo
                                    <ExternalLink size={11} />
                                  </a>
                                )}
                              </div>
                              <Badge
                                status={k.expired ? "ARCHIVED" : k.status}
                              />
                              {has("REVIEWER") &&
                                k.status === "PENDING" &&
                                !k.expired && (
                                  <Button
                                    secondary
                                    onClick={() =>
                                      action(
                                        `/brands/${brandId}/knowledge/${k.id}/approve`,
                                        "POST",
                                        {},
                                        "Nguồn đã được duyệt.",
                                      )
                                    }
                                  >
                                    Duyệt nguồn
                                  </Button>
                                )}
                            </div>
                          ))}
                          {!data.knowledge.length && (
                            <Empty
                              title="Thêm tri thức cho thương hiệu"
                              text="Dán nội dung đã xác minh, kèm nguồn và thời hạn nếu có."
                            />
                          )}
                        </section>
                        <section className="panel channels-panel">
                          <div className="section-heading">
                            <div>
                              <h2>Kênh kết nối</h2>
                              <p>
                                Mỗi publication sử dụng một Page và một
                                provider.
                              </p>
                            </div>
                            {has() && (
                              <Button
                                secondary
                                onClick={() => open("connection")}
                              >
                                <Link2 size={15} />
                                Kết nối Meta
                              </Button>
                            )}
                          </div>
                          {data.accounts.map((a: Row) => (
                            <div className="account-row" key={a.id}>
                              <span className="facebook-icon">
                                <Facebook size={21} />
                              </span>
                              <div>
                                <strong>{a.name}</strong>
                                <span>
                                  {a.provider === "DEMO"
                                    ? "Adapter thử nghiệm · Không gửi đến Facebook"
                                    : `Meta Page · ${a.remote_id}`}
                                </span>
                              </div>
                              <Badge status={a.health} />
                            </div>
                          ))}
                          {!data.accounts.length && (
                            <p className="muted">Chưa có Page kết nối.</p>
                          )}
                        </section>
                      </div>
                    </div>
                  </>
                )}
                {page === "leads" && (
                  <>
                    <div className="toolbar">
                      <div className="tabs">
                        <button className="selected">
                          Tất cả khách hàng <em>{data.leads.length}</em>
                        </button>
                      </div>
                      <div className="spacer" />
                      <Button
                        secondary
                        onClick={() => {
                          navigator.clipboard
                            .writeText(
                              `${location.origin}/f/${brand.public_form_key}`,
                            )
                            .then(() => notify("Đã sao chép liên kết form."))
                            .catch(() =>
                              notify(
                                `${location.origin}/f/${brand.public_form_key}`,
                              ),
                            );
                        }}
                      >
                        <Copy size={15} />
                        Liên kết nhận lead
                      </Button>
                      {has("SALES") && (
                        <Button onClick={() => open("lead")}>
                          <Plus size={16} />
                          Thêm khách hàng
                        </Button>
                      )}
                    </div>
                    <div className="panel table-wrap">
                      <table>
                        <thead>
                          <tr>
                            <th>Khách hàng</th>
                            <th>Nhu cầu</th>
                            <th>Trạng thái</th>
                            <th>Nguồn</th>
                            <th>Người phụ trách</th>
                            <th />
                          </tr>
                        </thead>
                        <tbody>
                          {data.leads.map((l: Row) => (
                            <tr key={l.id}>
                              <td>
                                <b>{l.name}</b>
                                <small>{l.contact}</small>
                              </td>
                              <td className="lead-interest">{l.interest}</td>
                              <td>
                                <Badge status={l.stage} />
                              </td>
                              <td>
                                <span className="source-label">
                                  {l.attribution_confidence === "EXACT"
                                    ? "Theo bài đăng"
                                    : "Chưa xác định"}
                                </span>
                                <small>Đồng ý {dt(l.consent_at, zone)}</small>
                              </td>
                              <td>{l.assigned_user || "Chưa phân công"}</td>
                              <td>
                                {has("SALES") && (
                                  <button
                                    className="text-button"
                                    onClick={() => open("lead", l)}
                                  >
                                    Cập nhật
                                    <ArrowUpRight size={15} />
                                  </button>
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                      {!data.leads.length && (
                        <Empty
                          title="Sẵn sàng đón kết nối đầu tiên"
                          text="Chia sẻ form nhận lead để khách chủ động cung cấp thông tin."
                        />
                      )}
                    </div>
                  </>
                )}
                {page === "reports" && (
                  <>
                    <div className="notice">
                      <ChartNoAxesCombined size={18} />
                      Chỉ số chưa được provider cung cấp hiển thị “Chưa có dữ
                      liệu”. Dữ liệu demo không có số tương tác giả.
                    </div>
                    <div className="report-grid">
                      <section className="panel">
                        <div className="section-heading">
                          <div>
                            <h2>Tiến trình khách hàng</h2>
                            <p>Theo trạng thái được đội Sales cập nhật.</p>
                          </div>
                        </div>
                        {["NEW", "QUALIFIED", "CONTACTED", "WON", "LOST"].map(
                          (s) => {
                            const value = Number(
                              data.reports?.lead_stages.find(
                                (r: Row) => r.stage === s,
                              )?.total || 0,
                            );
                            return (
                              <div className="funnel-row" key={s}>
                                <span>{stateLabels[s]}</span>
                                <div className="funnel-bar">
                                  <i
                                    style={{
                                      width: `${Math.max(value ? 4 : 0, (value / Math.max(1, data.dash?.counts.leads || 1)) * 100)}%`,
                                    }}
                                  />
                                </div>
                                <b>{value}</b>
                              </div>
                            );
                          },
                        )}
                      </section>
                      <section className="panel">
                        <div className="section-heading">
                          <div>
                            <h2>Đo lường sau xuất bản</h2>
                            <p>
                              Lấy dữ liệu tại 1 giờ, 24 giờ, 72 giờ và 7 ngày.
                            </p>
                          </div>
                        </div>
                        <div className="metric-empty">
                          <ChartNoAxesCombined size={37} />
                          <h3>
                            {data.reports?.metrics.length
                              ? "Snapshot đã được thu thập"
                              : "Chờ dữ liệu từ Page"}
                          </h3>
                          <p>
                            {data.reports?.metrics.length
                              ? "Xem giá trị và lý do thiếu dữ liệu trong bảng bên dưới."
                              : "Sau khi đăng, worker sẽ thu chỉ số theo quyền provider. Reach thiếu dữ liệu được giữ nguyên là NULL."}
                          </p>
                        </div>
                      </section>
                    </div>
                    {!!data.reports?.metrics.length && (
                      <section className="panel table-wrap">
                        <table>
                          <thead>
                            <tr>
                              <th>Nội dung</th>
                              <th>Chỉ số</th>
                              <th>Giá trị</th>
                              <th>Provider</th>
                              <th>Cập nhật</th>
                            </tr>
                          </thead>
                          <tbody>
                            {data.reports.metrics.map((m: Row) => (
                              <tr key={m.id}>
                                <td>{m.hook}</td>
                                <td>{m.metric_name}</td>
                                <td>
                                  {m.value === null
                                    ? "Chưa có dữ liệu"
                                    : m.value}
                                  <small>{m.missing_reason}</small>
                                </td>
                                <td>{m.provider}</td>
                                <td>{dt(m.fetched_at, zone)}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </section>
                    )}
                  </>
                )}
                {page === "ai" && has("MARKETER") && (
                  <AiConnections onChanged={reload} />
                )}
                {page === "autopost" && (
                  <Autopost
                    key={brand.id}
                    brand={brand}
                    accounts={data.accounts}
                    knowledge={data.knowledge}
                    canManage={has()}
                    refresh={refresh}
                    onChanged={reload}
                    onReview={() => setPage("review")}
                  />
                )}
                {page === "operations" && data.operations && (
                  <>
                    <div className="section-heading">
                      <div>
                        <h2>Tác vụ & độ tin cậy</h2>
                        <p>Lease, số lần xử lý và lỗi cuối cùng.</p>
                      </div>
                      <Button secondary onClick={reload}>
                        <RefreshCw size={15} />
                        Cập nhật
                      </Button>
                    </div>
                    <div className="panel table-wrap">
                      <table>
                        <thead>
                          <tr>
                            <th>Loại tác vụ</th>
                            <th>Trạng thái</th>
                            <th>Lần xử lý</th>
                            <th>Đến hạn</th>
                            <th>Thông tin lỗi</th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.operations.jobs.map((j: Row) => (
                            <tr key={j.id}>
                              <td>
                                <b>{j.kind}</b>
                                <small>{j.id.slice(0, 8)}</small>
                              </td>
                              <td>
                                <Badge status={j.status} />
                              </td>
                              <td>
                                {j.attempts} · fence {j.fence}
                              </td>
                              <td>{dt(j.due_at, zone)}</td>
                              <td>{j.last_error || "—"}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                      {!data.operations.jobs.length && (
                        <Empty
                          title="Chưa có tác vụ"
                          text="Tác vụ xuất hiện khi tạo nội dung hoặc lập lịch đăng."
                        />
                      )}
                    </div>
                    <section className="panel audit-panel">
                      <div className="section-heading">
                        <h2>Lịch sử thao tác</h2>
                      </div>
                      {data.operations.audit.slice(0, 20).map((a: Row) => (
                        <div className="audit-row" key={a.id}>
                          <span className="audit-dot" />
                          <div>
                            <b>{activityLabel(a.action)}</b>
                            <small>{a.actor}</small>
                          </div>
                          <time>{dt(a.created_at, zone)}</time>
                        </div>
                      ))}
                    </section>
                  </>
                )}
              </>
            )
          )}
          <footer className="page-footer">
            <span>
              <Sparkles size={13} />
              AI Marketing · Tri thức dẫn lối sáng tạo
            </span>
            <span>
              {data.dash?.demo
                ? "Dữ liệu thử nghiệm · Đăng thật đã tắt"
                : `Múi giờ ${zone}`}
              <i />
            </span>
          </footer>
        </main>
      </div>
      {toast && (
        <div className="toast" role="status">
          <CircleAlert size={17} />
          {toast}
          <button aria-label="Ẩn thông báo" onClick={() => setToast("")}>
            <X size={15} />
          </button>
        </div>
      )}
      {modal && (
        <ActionModal
          modal={modal}
          brand={brand}
          data={{ ...data, aiStatus }}
          has={has}
          onClose={close}
          onDone={(message) => {
            close();
            reload();
            notify(message);
          }}
          setPage={setPage}
        />
      )}
    </div>
  );
}
function activityLabel(action: string) {
  const labels: Record<string, string> = {
    DEMO_INITIALIZED: "Không gian thử nghiệm đã sẵn sàng",
    REVISION_CREATED: "Bản nháp mới đã được tạo",
    REVIEW_REQUESTED: "Nội dung đã gửi duyệt",
    REVIEW_APPROVED: "Nội dung đã được duyệt",
    REVIEW_CHANGES_REQUESTED: "Nội dung cần chỉnh sửa",
    PUBLICATION_QUEUED: "Nội dung đã được lên lịch",
    PUBLICATION_CANCELLED: "Lịch nội bộ đã được hủy",
    WORKFLOW_QUEUED: "Tác vụ tạo nội dung đã bắt đầu",
    KNOWLEDGE_IMPORTED: "Đã thêm nguồn kiến thức",
    KNOWLEDGE_APPROVED: "Nguồn kiến thức đã được duyệt",
    BRAND_PAUSED: "Thương hiệu đã tạm dừng",
    BRAND_RESUMED: "Thương hiệu đã tiếp tục",
    BRAND_CREATED: "Thương hiệu mới đã được tạo",
    BRAND_POLICY_UPDATED: "Cấu hình thương hiệu đã cập nhật",
    LEAD_CAPTURED: "Đã ghi nhận khách hàng quan tâm",
    LEAD_UPDATED: "Thông tin khách hàng đã cập nhật",
    CAMPAIGN_CREATED: "Chiến dịch mới đã được tạo",
    META_CONNECTED: "Facebook Page đã được kết nối",
  };
  return labels[action] || action;
}
