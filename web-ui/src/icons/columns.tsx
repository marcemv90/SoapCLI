import type { IconProps } from ".";

/** Two panes side by side. */
export function ColumnsIcon(props: IconProps) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" width={20} height={20} viewBox="0 0 20 20" fill="none" {...props}>
      <rect x={2.5} y={3.75} width={15} height={12.5} rx={2} stroke="currentColor" strokeWidth={1.5} />
      <path d="M10 3.75v12.5" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" />
    </svg>
  );
}
