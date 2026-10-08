import { useEffect } from "react";
import type { ReactNode } from "react";
import { FileText, X } from "lucide-react";
export function Button({
  children,
  onClick,
  secondary = false,
  disabled = false,
  type = "button",
  className = "",
}: {
  children: ReactNode;
  onClick?: () => void;
  secondary?: boolean;
  disabled?: boolean;
  type?: "button" | "submit";
  className?: string;
}) {
  return (
    <button
      type={type}
      className={`${secondary ? "btn-secondary" : "btn-primary"} ${className}`}
      onClick={onClick}
      disabled={disabled}
    >
      {children}
    </button>
  );
}
export function Empty({
  title,
  text,
  action,
}: {
  title: string;
  text: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-icon">
        <FileText size={26} />
      </div>
      <h3>{title}</h3>
      <p>{text}</p>
      {action}
    </div>
  );
}
export function Modal({
  title,
  sub,
  children,
  onClose,
  wide = false,
}: {
  title: string;
  sub?: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  useEffect(() => {
    const f = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", f);
    return () => document.removeEventListener("keydown", f);
  }, [onClose]);
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <section
        className={`modal ${wide ? "wide" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <header>
          <div>
            <h2>{title}</h2>
            {sub && <p>{sub}</p>}
          </div>
          <button className="icon-button" aria-label="Đóng" onClick={onClose}>
            <X size={21} />
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}
