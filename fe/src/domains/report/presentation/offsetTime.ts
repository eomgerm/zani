const pad = (value: number) => String(value).padStart(2, "0");

/** 오프셋 초를 수업 경과 시각으로 읽는다. 한 시간을 넘기면 시간 자리를 붙인다. */
export function formatOffset(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = total % 60;
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(rest)}` : `${pad(minutes)}:${pad(rest)}`;
}
