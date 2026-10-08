import { useState, useEffect } from "react";
import {
  ArrowRight,
  CheckCheck,
  CalendarDays,
  Sparkles,
  LoaderCircle,
  Send,
} from "lucide-react";
import { api, login } from "../api";
import type { Row } from "../api";
import { Button } from "./ui";
export function Login({ onLogin }: { onLogin: (me: Row) => void }) {
  const [email, setEmail] = useState(""),
    [password, setPassword] = useState(""),
    [config, setConfig] = useState<Row>({}),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    api("/auth/config")
      .then((c) => {
        setConfig(c);
        if (c.demo) {
          setEmail("owner@demo.local");
          setPassword("demo-marketing");
        }
      })
      .catch(() =>
        setError("Máy chủ chưa sẵn sàng. Kiểm tra dịch vụ backend."),
      );
  }, []);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await login(email, password);
      onLogin(await api("/auth/me"));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login-page">
      <div className="login-decoration">
        <div className="logo-mark">
          m<span>✦</span>
        </div>
        <span className="eyebrow">AI MARKETING</span>
        <h1>
          Ý tưởng tốt.
          <br />
          Kết nối thật.
        </h1>
        <p>
          Một không gian cho tri thức thương hiệu,
          <br />
          nội dung sáng tạo và khách hàng của bạn.
        </p>
        <div className="orbit orbit-login">
          <div className="orbit-ring" />
          <div className="orbit-core">
            <Sparkles size={40} />
          </div>
          <div className="orbit-chip chip-one">
            <CheckCheck size={19} /> Nội dung có nguồn
          </div>
          <div className="orbit-chip chip-two">
            <CalendarDays size={19} /> Xuất bản có kiểm soát
          </div>
        </div>
      </div>
      <form className="login-form" onSubmit={submit}>
        <div className="logo-inline">
          <div className="logo-mark small">
            m<span>✦</span>
          </div>
          <b>AI Marketing</b>
        </div>
        <h2>Chào mừng trở lại</h2>
        <p>Đăng nhập vào không gian thương hiệu của bạn.</p>
        {config.demo && (
          <div className="notice">
            Workspace thử nghiệm · không đăng Facebook thật
          </div>
        )}
        {error && (
          <div className="error-box" role="alert">
            {error}
          </div>
        )}
        {config.demo && (
          <>
            <label>
              Email
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                autoComplete="username"
              />
            </label>
            <label>
              Mật khẩu
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                autoComplete="current-password"
              />
            </label>
            <Button type="submit" disabled={busy}>
              {busy ? <LoaderCircle className="spin" size={18} /> : null}Đăng
              nhập <ArrowRight size={18} />
            </Button>
            <small>Tài khoản demo: owner@demo.local / demo-marketing</small>
          </>
        )}
        {config.oidc && (
          <a className="btn-primary" href="/oauth2/authorization/marketing">
            Đăng nhập bằng tài khoản tổ chức <ArrowRight size={18} />
          </a>
        )}
      </form>
    </div>
  );
}
export function LeadForm() {
  const key = window.location.pathname.split("/")[2];
  const [brand, setBrand] = useState<Row | null>(null),
    [error, setError] = useState(""),
    [done, setDone] = useState(false),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    api(`/forms/${key}`)
      .then(setBrand)
      .catch((e) => setError(e.message));
  }, [key]);
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    try {
      await api(`/forms/${key}`, "POST", {
        name: f.get("name"),
        contact: f.get("contact"),
        interest: f.get("interest"),
        purpose: "Liên hệ để hỗ trợ nhu cầu đã cung cấp",
        consent: f.get("consent") === "on",
        reference: new URLSearchParams(location.search).get("ref") || null,
      });
      setDone(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="public-form">
      <div className="logo-mark">
        m<span>✦</span>
      </div>
      <span className="eyebrow">KẾT NỐI CÙNG CHÚNG TÔI</span>
      <h1>{brand?.name || "Đăng ký nhận hỗ trợ"}</h1>
      <p>
        Chia sẻ nhu cầu của bạn. Đội ngũ sẽ liên hệ để làm rõ phạm vi hỗ trợ.
      </p>
      {error && <div className="error-box">{error}</div>}
      {done ? (
        <div className="success-box">
          <CheckCheck />
          Đã nhận thông tin. Cảm ơn bạn đã liên hệ!
        </div>
      ) : (
        brand && (
          <form onSubmit={submit}>
            <label>
              Họ tên
              <input name="name" required maxLength={150} />
            </label>
            <label>
              Email hoặc số điện thoại
              <input name="contact" required maxLength={200} />
            </label>
            <label>
              Nhu cầu của bạn
              <textarea name="interest" required maxLength={2000} />
            </label>
            <label className="checkbox">
              <input type="checkbox" name="consent" required />
              Tôi đồng ý cung cấp thông tin để đội ngũ liên hệ hỗ trợ nhu cầu
              trên.
            </label>
            <Button type="submit" disabled={busy}>
              Gửi nhu cầu <Send size={17} />
            </Button>
          </form>
        )
      )}
    </main>
  );
}
