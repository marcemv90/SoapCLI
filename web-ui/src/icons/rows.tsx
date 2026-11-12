import type { IconProps } from ".";

/** Two panes, one above the other. */
export function RowsIcon(props: IconProps) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" width={20} height={20} viewBox="0 0 20 20" fill="none" {...props}>
      <rect x={2.5} y={3.75} width={15} height={12.5} rx={2} stroke="currentColor" strokeWidth={1.5} />
      <path d="M2.5 10h15" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" />
    </svg>
  );
}
