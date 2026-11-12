import { useEffect, useRef, useState, type ComponentProps, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CheckIcon, CopyIcon } from "@/icons";
import { copyText } from "@/lib/format";
import { cn } from "@/utils";

export function NativeSelect({ className, ...props }: ComponentProps<"select">) {
  return (
    <select
      className={cn(
        "h-11 rounded-[10px] border border-gray-300 bg-white py-2 ps-3 pe-9 text-sm text-gray-800 shadow-theme-xs outline-hidden transition-colors",
        "focus:border-brand-300 focus:ring-3 focus:ring-brand-500/20 disabled:cursor-not-allowed disabled:opacity-60",
        "dark:border-gray-700 dark:bg-gray-900 dark:text-white/90 dark:focus:border-brand-500/40",
        className,
      )}
      {...props}
    />
  );
}

export interface SegmentedOption<T extends string> {
  value: T;
  label: ReactNode;
  disabled?: boolean;
  title?: string;
  /** For an option that shows only an icon. */
  ariaLabel?: string;
}

/** A pill-shaped single choice, in the style of TailAdmin's tabs. */
export function Segmented<T extends string>({ value, onChange, options, label }: {
  value: T;
  onChange: (value: T) => void;
  options: SegmentedOption<T>[];
  label: string;
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex rounded-lg bg-gray-100 p-1 dark:bg-gray-900">
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={active}
            disabled={o.disabled}
            title={o.title}
            aria-label={o.ariaLabel}
            onClick={() => onChange(o.value)}
            className={cn(
              "inline-flex h-9 items-center gap-1.5 rounded-md px-3 text-sm font-medium whitespace-nowrap transition-colors outline-hidden",
              "focus-visible:ring-3 focus-visible:ring-brand-500/20 disabled:cursor-not-allowed disabled:opacity-50",
              active
                ? "bg-white text-gray-900 shadow-theme-xs dark:bg-white/10 dark:text-white"
                : "text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white",
            )}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

export function StatusBadge({ status, text }: { status: number; text?: string }) {
  const color = status >= 200 && status < 300 ? "success" : status >= 300 && status < 400 ? "warning"
    : status >= 400 ? "error" : "gray";
  return (
    <Badge variant="light" color={color} size="lg" className="font-semibold">
      {status > 0 ? status : "—"}{text ? ` ${text}` : ""}
    </Badge>
  );
}

export function CopyButton({ text, label = "Copy", className }: { text: string; label?: string; className?: string }) {
  const [done, setDone] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  return (
    <Button
      variant="outline"
      size="xs"
      className={className}
      onClick={async () => {
        if (await copyText(text)) {
          setDone(true);
          clearTimeout(timer.current);
          timer.current = setTimeout(() => setDone(false), 1500);
        }
      }}
    >
      {done ? <CheckIcon className="text-green-500" /> : <CopyIcon />}
      {done ? "Copied" : label}
    </Button>
  );
}

export function EmptyState({ icon, title, children, action }: { icon: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-6 py-12 text-center">
      <div className="flex size-14 items-center justify-center rounded-full bg-gray-100 text-gray-500 dark:bg-white/5 dark:text-gray-400 [&_svg]:size-7">
        {icon}
      </div>
      <h3 className="text-base font-semibold text-gray-800 dark:text-white/90">{title}</h3>
      {children && <p className="max-w-md text-sm text-gray-500 dark:text-gray-400">{children}</p>}
      {action}
    </div>
  );
}

export function FieldLabel({ children, htmlFor, hint }: { children: ReactNode; htmlFor?: string; hint?: ReactNode }) {
  return (
    <div className="mb-1.5">
      <label htmlFor={htmlFor} className="block text-sm font-medium text-gray-700 dark:text-gray-300">{children}</label>
      {hint && <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{hint}</p>}
    </div>
  );
}

export function Kbd({ children }: { children: ReactNode }) {
  return (
    <kbd className="rounded-md border border-gray-200 bg-gray-50 px-1.5 py-0.5 font-sans text-xs text-gray-500 dark:border-gray-700 dark:bg-white/5 dark:text-gray-400">
      {children}
    </kbd>
  );
}
