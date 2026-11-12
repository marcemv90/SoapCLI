import { cn } from "@/utils";

/** A horizontal bar for a value from 0 to 100. */
function Progress({ value, label, className }: { value: number; label: string; className?: string }) {
  const clamped = Math.max(0, Math.min(100, value));
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={clamped}
      className={cn("h-2 w-full overflow-hidden rounded-full bg-gray-100 dark:bg-white/10", className)}
    >
      <div className="h-full rounded-full bg-brand-500 transition-[width] duration-200 ease-out" style={{ width: `${clamped}%` }} />
    </div>
  );
}

export { Progress };
