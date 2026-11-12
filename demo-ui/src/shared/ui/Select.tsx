import type { SelectHTMLAttributes } from 'react';

/** Keep the native keyboard/popup behavior; own only the closed control's geometry. */
export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <span className="select-control"><select {...props} />
    <svg className="select-chevron" width="12" height="8" viewBox="0 0 12 8" aria-hidden="true" focusable="false">
      <path d="m1 1.5 5 5 5-5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg></span>;
}

export function ArrowIcon() {
  return <svg className="icon" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
    <path d="M4 12 12 4M4 4h8v8" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
  </svg>;
}
