export function LocationPin({ color = "#10b981" }: { color?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill={color} style={{ verticalAlign: "-0.15em", marginRight: 6 }}>
      <path d="M12 2c-3.314 0-6 2.686-6 6 0 5 6 12 6 12s6-7 6-12c0-3.314-2.686-6-6-6zm0 10a4 4 0 110-8 4 4 0 010 8z" />
    </svg>
  );
}
