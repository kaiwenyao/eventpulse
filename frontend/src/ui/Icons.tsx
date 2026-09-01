/**
 * Inline 16×16 stroke icons. Bundled rather than pulled from an icon package:
 * the app needs a dozen glyphs, and inline SVG inherits `currentColor` so a
 * single CSS rule keeps them consistent with their surrounding text.
 */

type IconProps = { className?: string }

function Icon({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <svg className={`icon${className ? ` ${className}` : ''}`} viewBox="0 0 16 16" aria-hidden focusable="false">
      {children}
    </svg>
  )
}

export const ClockIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <circle cx="8" cy="8" r="6.5" />
    <path d="M8 4.5V8l2.5 1.5" />
  </Icon>
)

export const PinIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M8 14.5S13 10.4 13 6.6A5 5 0 0 0 3 6.6C3 10.4 8 14.5 8 14.5Z" />
    <circle cx="8" cy="6.5" r="1.9" />
  </Icon>
)

export const TicketIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M2 5.5A1.5 1.5 0 0 1 3.5 4h9A1.5 1.5 0 0 1 14 5.5v1a1.5 1.5 0 0 0 0 3v1A1.5 1.5 0 0 1 12.5 12h-9A1.5 1.5 0 0 1 2 10.5v-1a1.5 1.5 0 0 0 0-3Z" />
    <path d="M9.5 4v8" strokeDasharray="1.6 1.6" />
  </Icon>
)

export const UsersIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <circle cx="6" cy="5.5" r="2.4" />
    <path d="M1.8 13.4a4.4 4.4 0 0 1 8.4 0" />
    <path d="M10.6 3.6a2.4 2.4 0 0 1 0 4.4M11.6 9.6a4.4 4.4 0 0 1 2.6 3.8" />
  </Icon>
)

export const ChartIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M2 13.5h12" />
    <path d="M4 13V8M7.3 13V4.5M10.6 13V9.8M13.6 13V6.4" />
  </Icon>
)

export const GridIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <rect x="2.2" y="2.2" width="5" height="5" rx="1.2" />
    <rect x="8.8" y="2.2" width="5" height="5" rx="1.2" />
    <rect x="2.2" y="8.8" width="5" height="5" rx="1.2" />
    <rect x="8.8" y="8.8" width="5" height="5" rx="1.2" />
  </Icon>
)

export const PlusIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M8 3v10M3 8h10" />
  </Icon>
)

export const ArrowRightIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M2 8h12M10 4l4 4-4 4" />
  </Icon>
)

export const CheckIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M3 8.4 6.4 12 13 4.6" />
  </Icon>
)

export const BellIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M4 7a4 4 0 0 1 8 0c0 3 1.2 4 1.2 4H2.8S4 10 4 7Z" />
    <path d="M6.6 13.2a1.6 1.6 0 0 0 2.8 0" />
  </Icon>
)

export const HeartIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M8 13.6S2.2 10.2 2.2 6.4A2.9 2.9 0 0 1 8 5a2.9 2.9 0 0 1 5.8 1.4c0 3.8-5.8 7.2-5.8 7.2Z" />
  </Icon>
)

export const ImageIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <rect x="2" y="3" width="12" height="10" rx="1.6" />
    <circle cx="5.8" cy="6.4" r="1.1" />
    <path d="m2.6 11.6 3.2-3 3 2.6 2-1.8 2.6 2.2" />
  </Icon>
)
