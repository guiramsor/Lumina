const base = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
}

function Svg({ children, size = 24, ...props }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" {...base} {...props}>
      {children}
    </svg>
  )
}

export const PlayIcon = (p) => (
  <Svg {...p}>
    <path d="M7 5.5 19 12 7 18.5z" fill="currentColor" stroke="none" />
  </Svg>
)

export const PauseIcon = (p) => (
  <Svg {...p}>
    <rect x="6.5" y="5" width="3.6" height="14" rx="1.4" fill="currentColor" stroke="none" />
    <rect x="13.9" y="5" width="3.6" height="14" rx="1.4" fill="currentColor" stroke="none" />
  </Svg>
)

export const Back15Icon = (p) => (
  <Svg {...p}>
    <path d="M13.5 4.8A7 7 0 1 1 10.5 4.8" />
    <path d="M15.3 2.9 13.5 4.8 15.3 6.7" />
    <text
      x="12"
      y="12.4"
      fontSize="7.5"
      fontWeight="700"
      fill="currentColor"
      stroke="none"
      textAnchor="middle"
      dominantBaseline="central"
    >
      15
    </text>
  </Svg>
)

export const Fwd30Icon = (p) => (
  <Svg {...p}>
    <path d="M10.5 4.8A7 7 0 1 0 13.5 4.8" />
    <path d="M8.7 2.9 10.5 4.8 8.7 6.7" />
    <text
      x="12"
      y="12.4"
      fontSize="7.5"
      fontWeight="700"
      fill="currentColor"
      stroke="none"
      textAnchor="middle"
      dominantBaseline="central"
    >
      30
    </text>
  </Svg>
)

export const PrevIcon = (p) => (
  <Svg {...p}>
    <path d="M18 6 9 12l9 6z" fill="currentColor" stroke="none" />
    <rect x="6" y="5.5" width="2.2" height="13" rx="1" fill="currentColor" stroke="none" />
  </Svg>
)

export const NextIcon = (p) => (
  <Svg {...p}>
    <path d="M6 6l9 6-9 6z" fill="currentColor" stroke="none" />
    <rect x="15.8" y="5.5" width="2.2" height="13" rx="1" fill="currentColor" stroke="none" />
  </Svg>
)

export const MoonIcon = (p) => (
  <Svg {...p}>
    <path d="M20 14.5A8 8 0 0 1 9.5 4a7 7 0 1 0 10.5 10.5z" />
  </Svg>
)

export const BookmarkIcon = (p) => (
  <Svg {...p}>
    <path d="M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z" />
  </Svg>
)

export const BookmarkFilledIcon = (p) => (
  <Svg {...p}>
    <path d="M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z" fill="currentColor" />
  </Svg>
)

export const ListIcon = (p) => (
  <Svg {...p}>
    <path d="M8 6h12M8 12h12M8 18h12" />
    <circle cx="4" cy="6" r="1.1" fill="currentColor" stroke="none" />
    <circle cx="4" cy="12" r="1.1" fill="currentColor" stroke="none" />
    <circle cx="4" cy="18" r="1.1" fill="currentColor" stroke="none" />
  </Svg>
)

export const PlusIcon = (p) => (
  <Svg {...p}>
    <path d="M12 5v14M5 12h14" />
  </Svg>
)

export const DiscIcon = (p) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="8.5" />
    <circle cx="12" cy="12" r="2.2" />
  </Svg>
)

export const BookIcon = (p) => (
  <Svg {...p}>
    <path d="M4 5.5C4 4.7 4.7 4 5.5 4H11v15H5.5A1.5 1.5 0 0 0 4 20.5z" />
    <path d="M20 5.5C20 4.7 19.3 4 18.5 4H13v15h5.5a1.5 1.5 0 0 1 1.5 1.5z" />
  </Svg>
)

export const TrashIcon = (p) => (
  <Svg {...p}>
    <path d="M5 7h14M10 7V5h4v2M6 7l1 13h10l1-13" />
  </Svg>
)

export const CloseIcon = (p) => (
  <Svg {...p}>
    <path d="M6 6l12 12M18 6 6 18" />
  </Svg>
)

export const ChevronLeftIcon = (p) => (
  <Svg {...p}>
    <path d="M15 5l-7 7 7 7" />
  </Svg>
)

export const SpeedIcon = (p) => (
  <Svg {...p}>
    <path d="M12 4a8 8 0 1 0 8 8" />
    <path d="M12 12l4-4" />
    <circle cx="20" cy="4" r="1.2" fill="currentColor" stroke="none" />
  </Svg>
)

export const VolumeIcon = (p) => (
  <Svg {...p}>
    <path d="M4 9v6h4l5 4V5L8 9z" fill="currentColor" stroke="none" />
    <path d="M16.5 8.5a5 5 0 0 1 0 7" />
  </Svg>
)

export const EditIcon = (p) => (
  <Svg {...p}>
    <path d="M14.5 5.5 18.5 9.5 8 20H4v-4z" />
    <path d="M12.5 7.5 16.5 11.5" />
  </Svg>
)

export const PlayCircleIcon = (p) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M10 8.5 16 12l-6 3.5z" fill="currentColor" stroke="none" />
  </Svg>
)

export const SearchIcon = (p) => (
  <Svg {...p}>
    <circle cx="11" cy="11" r="6.5" />
    <path d="M15.8 15.8 20.5 20.5" />
  </Svg>
)

export const ChartIcon = (p) => (
  <Svg {...p}>
    <path d="M4 20V10" />
    <path d="M10 20V4" />
    <path d="M16 20v-8" />
    <path d="M21 20H3" />
  </Svg>
)

export const PaletteIcon = (p) => (
  <Svg {...p}>
    <path d="M12 3a9 9 0 1 0 0 18c1.5 0 2.2-.9 2.2-2 0-1-.7-1.6-.7-2.6 0-1.1.9-2 2-2h2A3.5 3.5 0 0 0 21 11c0-4.4-4-8-9-8z" />
    <circle cx="7.5" cy="11.5" r="1.1" fill="currentColor" stroke="none" />
    <circle cx="10.5" cy="7.5" r="1.1" fill="currentColor" stroke="none" />
    <circle cx="15" cy="7.5" r="1.1" fill="currentColor" stroke="none" />
  </Svg>
)

export const HelpIcon = (p) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M9.6 9.2a2.6 2.6 0 0 1 5.1.7c0 1.7-2.4 2.1-2.4 3.6" />
    <circle cx="12.2" cy="16.8" r="1" fill="currentColor" stroke="none" />
  </Svg>
)

export const FlameIcon = (p) => (
  <Svg {...p}>
    <path d="M12 21c-3.9 0-6.5-2.5-6.5-6 0-2.6 1.6-4.6 3-6.2C9.9 7.2 11 5.8 11 3.5c2.6 1.3 4 3.4 4 5.5 0 .9-.2 1.7-.6 2.4.8-.2 1.5-.7 2-1.4 1.3 1.4 2.1 3.2 2.1 5 0 3.5-2.6 6-6.5 6z" />
  </Svg>
)
