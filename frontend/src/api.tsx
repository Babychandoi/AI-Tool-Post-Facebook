export type Row = Record<string, any>;
let csrf = "";
export async function initCsrf() {
  const r = await fetch("/v1/auth/csrf", { credentials: "same-origin" });
  const data = await r.json();
  csrf = data.token;
}
export async function api<T = Row>(
  path: string,
  method = "GET",
  body?: unknown,
  key?: string,
): Promise<T> {
  if (method !== "GET" && !csrf) await initCsrf();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (method !== "GET") headers["X-XSRF-TOKEN"] = csrf;
  if (key) headers["Idempotency-Key"] = key;
  const r = await fetch(`/v1${path}`, {
    method,
    credentials: "same-origin",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data =
    r.status === 204
      ? {}
      : await r
          .json()
          .catch(() => ({ message: "Không đọc được phản hồi máy chủ." }));
  if (!r.ok) {
    if (r.status === 401) window.dispatchEvent(new Event("session-expired"));
    throw new Error(data.message || `Yêu cầu thất bại (${r.status}).`);
  }
  return data as T;
}
export async function login(email: string, password: string) {
  await initCsrf();
  const r = await fetch("/v1/auth/login", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "X-XSRF-TOKEN": csrf,
    },
    body: new URLSearchParams({ username: email, password }),
  });
  if (!r.ok) {
    const d = await r.json();
    throw new Error(d.message || "Không đăng nhập được.");
  }
  await initCsrf();
}
export const dt = (value: string, zone = "Asia/Ho_Chi_Minh") =>
  new Intl.DateTimeFormat("vi-VN", {
    timeZone: zone,
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
export const money = (micro: number) =>
  new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 2,
  }).format(micro / 1000000);
export const stateLabels: Record<string, string> = {
  DRAFT: "Bản nháp",
  IN_REVIEW: "Chờ duyệt",
  APPROVED: "Đã duyệt",
  CHANGES_REQUESTED: "Cần chỉnh sửa",
  ARCHIVED: "Lưu trữ",
  QUEUED: "Đã lên lịch",
  DISPATCHING: "Đang gửi",
  PUBLISHED: "Đã xuất bản",
  RETRY_WAIT: "Chờ thử lại",
  UNKNOWN: "Cần đối soát",
  BLOCKED: "Bị chặn",
  FAILED: "Thất bại",
  CANCELLED: "Đã hủy",
  NEW: "Mới",
  QUALIFIED: "Tiềm năng",
  CONTACTED: "Đã liên hệ",
  WON: "Thành công",
  LOST: "Không thành công",
  DISQUALIFIED: "Không phù hợp",
  READY: "Sẵn sàng",
  RUNNING: "Đang chạy",
  DONE: "Hoàn tất",
  WAITING_REVIEW: "Chờ người duyệt",
  COMPLETED: "Hoàn tất",
  PENDING: "Chưa duyệt",
  CONNECTED: "Đã kết nối",
};
export function Badge({ status }: { status: string }) {
  return (
    <span className={`badge status-${status.toLowerCase()}`}>
      <i />
      {stateLabels[status] || status}
    </span>
  );
}
