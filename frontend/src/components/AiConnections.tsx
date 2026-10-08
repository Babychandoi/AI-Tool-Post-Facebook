import { useEffect, useState } from "react";
import {
  Check,
  ExternalLink,
  Link2,
  LoaderCircle,
  RefreshCw,
  ShieldCheck,
} from "lucide-react";
import { api } from "../api";
import type { Row } from "../api";
import { Button, Modal } from "./ui";

const labels: Record<string, string> = {
  CONNECTED: "Đã cho phép dùng hạn mức",
  PLAN_DISABLED: "Chưa cho phép dùng hạn mức",
  REAUTH_REQUIRED: "Cần kết nối lại",
  DISCONNECTED: "Đã ngắt kết nối",
};
export function AiConnections({
  onChanged,
  embedded = false,
}: {
  onChanged: () => void;
  embedded?: boolean;
}) {
  const [info, setInfo] = useState<Row | null>(null);
  const [models, setModels] = useState<Row[]>([]);
  const [model, setModel] = useState("");
  const [busy, setBusy] = useState(false);
  const [welcome, setWelcome] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState(() => {
    const result = new URLSearchParams(window.location.search).get("chatgpt");
    return (
      (
        {
          connected:
            "Đã kết nối ChatGPT. Chọn model bên dưới để bắt đầu tạo nội dung.",
          plan_disabled:
            "Đã xác minh tài khoản; bạn cần cho phép dùng hạn mức ChatGPT.",
          declined: "Bạn đã hủy kết nối. Tài khoản đang dùng được giữ nguyên.",
          error:
            "Chưa kết nối được ChatGPT. Phiên có thể đã hết hạn hoặc OpenAI chưa cho phép; thử kết nối lại.",
        } as Record<string, string>
      )[result || ""] || ""
    );
  });
  const active = info?.accounts?.find((a: Row) => a.id === info.active_id);
  useEffect(() => {
    if (active?.status === "CONNECTED") {
      try {
        setWelcome(
          localStorage.getItem(`chatgpt-welcome:${active.id}`) !== "seen",
        );
      } catch {
        setWelcome(true);
      }
    }
  }, [active?.id, active?.status]);
  function acknowledge() {
    if (active) {
      try {
        localStorage.setItem(`chatgpt-welcome:${active.id}`, "seen");
      } catch {}
    }
    setWelcome(false);
  }
  async function load() {
    const status = await api("/ai/chatgpt");
    setInfo(status);
    return status;
  }
  useEffect(() => {
    if (new URLSearchParams(window.location.search).has("chatgpt"))
      window.history.replaceState(
        null,
        "",
        window.location.pathname + (embedded ? "#autopost" : "#ai"),
      );
    sessionStorage.removeItem("chatgpt-return-to");
    load().catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    let alive = true;
    setModels([]);
    setModel(active?.model || "");
    if (active?.status === "CONNECTED")
      api<Row[]>(`/ai/chatgpt/${active.id}/models`)
        .then((rows) => {
          if (alive) {
            setModels(rows);
            setModel((m) => (rows.some((r) => r.slug === m) ? m : ""));
          }
        })
        .catch((e) => {
          if (alive) setError(e.message);
        });
    return () => {
      alive = false;
    };
  }, [active?.id, active?.status]);
  async function action(work: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const connect = (id?: string, consent = false) =>
    action(async () => {
      const result = await api("/ai/chatgpt/connect", "POST", {
        connection_id: id || null,
        consent,
      });
      const url = new URL(result.authorization_url);
      if (
        url.origin !== "https://auth.openai.com" ||
        url.pathname !== "/api/accounts/authorize"
      )
        throw new Error("Địa chỉ đăng nhập OpenAI không hợp lệ.");
      if (embedded) sessionStorage.setItem("chatgpt-return-to", "autopost");
      window.location.assign(url.href);
    });
  return (
    <div className="ai-connections">
      {welcome && (
        <Modal
          title="Bạn đang dùng gói ChatGPT"
          sub="You're using your ChatGPT plan"
          onClose={acknowledge}
        >
          <div className="ai-welcome">
            <p>
              Các lần tạo nội dung bằng model bạn chọn sẽ dùng hạn mức gói hoặc
              credits theo cài đặt ChatGPT của bạn. Bạn có thể điều chỉnh quyền
              và hạn mức cho AI Marketing trong ChatGPT.
            </p>
            <a
              className="text-button"
              href="https://chatgpt.com/settings/usage"
              target="_blank"
              rel="noreferrer"
            >
              Quản lý hạn mức · Manage usage <ExternalLink size={14} />
            </a>
            <Button onClick={acknowledge}>Đã hiểu · Got it</Button>
          </div>
        </Modal>
      )}
      <section className="panel ai-connect-intro">
        <div className="ai-connect-icon">
          <Link2 size={27} />
        </div>
        <div>
          <span className="eyebrow">CHATGPT · TÀI KHOẢN CỦA BẠN</span>
          <h2>Dùng gói ChatGPT để tạo nội dung</h2>
          <p>
            Kết nối tài khoản Plus hoặc Pro đủ điều kiện, cho phép AI Marketing
            dùng hạn mức và chọn model. Bạn đăng nhập trực tiếp trên OpenAI.
          </p>
          <button
            className="chatgpt-signin"
            disabled={busy || !info?.enabled}
            onClick={() => connect()}
          >
            {busy && <LoaderCircle size={16} className="spin" />} Continue with
            ChatGPT
          </button>
          {info && !info.enabled && (
            <p className="ai-help">
              Kết nối ChatGPT chưa được bật trên máy chủ.
            </p>
          )}
        </div>
      </section>
      {message && (
        <div className="notice" role="status">
          <Check size={18} />
          {message}
        </div>
      )}
      {error && (
        <div className="error-box" role="alert">
          {error}
          <a
            href="https://chatgpt.com/settings/usage"
            target="_blank"
            rel="noreferrer"
          >
            Quản lý hạn mức
          </a>
        </div>
      )}
      {!!info?.accounts?.length && (
        <section className="panel ai-account-panel">
          <div className="section-heading">
            <div>
              <h2>Tài khoản đã kết nối</h2>
              <p>
                Kết nối riêng của bạn, dùng cho tác vụ bạn khởi tạo và chiến
                dịch bạn bật tạo nháp hằng ngày.
              </p>
            </div>
            <Button
              secondary
              disabled={busy}
              onClick={() =>
                action(async () => {
                  await load();
                })
              }
            >
              <RefreshCw size={15} />
              Cập nhật
            </Button>
          </div>
          {info.accounts.map((account: Row) => (
            <div className="ai-account" key={account.id}>
              <div>
                <b>{account.account_email}</b>
                <small>
                  {labels[account.status]} · Kết nối {account.id.slice(0, 8)}
                </small>
                {account.active && (
                  <span className="badge status-connected">
                    <i />
                    Đang chọn
                  </span>
                )}
              </div>
              <div className="ai-account-actions">
                {!account.active && (
                  <Button
                    secondary
                    disabled={busy}
                    onClick={() =>
                      action(async () => {
                        await api(`/ai/chatgpt/${account.id}/activate`, "POST");
                        await load();
                        onChanged();
                      })
                    }
                  >
                    Chọn tài khoản
                  </Button>
                )}
                <Button
                  secondary
                  disabled={busy}
                  onClick={() =>
                    connect(account.id, account.status === "PLAN_DISABLED")
                  }
                >
                  {account.status === "PLAN_DISABLED"
                    ? "Cho phép dùng hạn mức"
                    : "Kết nối lại"}
                </Button>
                {account.status !== "DISCONNECTED" && (
                  <Button
                    secondary
                    disabled={busy}
                    onClick={() =>
                      action(async () => {
                        const result = await api(
                          `/ai/chatgpt/${account.id}`,
                          "DELETE",
                        );
                        setMessage(
                          result.remote_revocation_confirmed
                            ? "Đã ngắt kết nối ChatGPT."
                            : "Đã ngắt tại ứng dụng; chưa xác nhận thu hồi từ OpenAI. Bạn có thể ngắt ứng dụng trong cài đặt ChatGPT.",
                        );
                        await load();
                        onChanged();
                      })
                    }
                  >
                    Ngắt kết nối
                  </Button>
                )}
              </div>
            </div>
          ))}
          {active && (
            <div className="ai-model-section">
              <div className="ai-model-heading">
                <h3>Model tạo nội dung</h3>
                <span className="ai-plan-indicator">
                  {active.status === "CONNECTED"
                    ? "Using ChatGPT plan"
                    : "Chưa sẵn sàng"}
                </span>
              </div>
              <div className="ai-model-control">
                <select
                  aria-label="Model ChatGPT"
                  value={model}
                  disabled={busy || active.status !== "CONNECTED"}
                  onChange={(e) => setModel(e.target.value)}
                >
                  <option value="">
                    Chọn model tài khoản được phép sử dụng
                  </option>
                  {models.map((m) => (
                    <option key={m.slug} value={m.slug}>
                      {m.display_name}
                    </option>
                  ))}
                </select>
                <Button
                  disabled={busy || !model}
                  onClick={() =>
                    action(async () => {
                      await api(`/ai/chatgpt/${active.id}/model`, "PUT", {
                        model,
                      });
                      await load();
                      onChanged();
                      setMessage(
                        "Đã chọn model. Bản nháp tiếp theo sẽ dùng hạn mức ChatGPT của bạn.",
                      );
                    })
                  }
                >
                  Lưu model
                </Button>
              </div>
              {active.last_error && (
                <p className="ai-help">{active.last_error}</p>
              )}
              <a
                className="text-button"
                href="https://chatgpt.com/settings/usage"
                target="_blank"
                rel="noreferrer"
              >
                Quản lý hạn mức · Manage usage <ExternalLink size={14} />
              </a>
            </div>
          )}
        </section>
      )}
      <div className="notice">
        <ShieldCheck size={20} />
        <span>
          OpenAI quản lý quyền và hạn mức. Khi hết hạn mức hoặc thiếu quyền, tác
          vụ dừng; ứng dụng không tự chuyển sang API tính phí. Cách duyệt và
          đăng Facebook được chọn trong Đăng tự động; bài tạo thủ công vẫn cần
          duyệt.
        </span>
      </div>
    </div>
  );
}
