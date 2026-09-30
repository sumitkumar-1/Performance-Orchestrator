// Stable releases first (numeric ascending), then dated development builds (newest first).
export function sortImageVersions(tags) {
  const stable = tag => /^v?\d+\.\d+\.\d+(?:\+[^\s]+)?$/.test(tag);
  const buildDate = tag => {
    const match = /^v?\d+\.\d+\.\d+\.(\d{2})(\d{2})(\d{2})(?:-|$)/.exec(tag);
    if (!match) return null;
    const year = 2000 + Number(match[1]), month = Number(match[2]), day = Number(match[3]);
    const date = new Date(Date.UTC(year, month - 1, day));
    // Malformed dates use the ordinary tag ordering instead of looking like recent builds.
    return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
      ? date.getTime() : null;
  };
  return [...new Set(tags)].sort((a, b) => {
    const releaseOrder = Number(stable(b)) - Number(stable(a));
    if (releaseOrder) return releaseOrder;
    if (!stable(a)) {
      const dateA = buildDate(a), dateB = buildDate(b);
      if (dateA !== null && dateB !== null && dateA !== dateB) return dateB - dateA;
      if ((dateA === null) !== (dateB === null)) return dateA === null ? 1 : -1;
    }
    return a.replace(/^v(?=\d)/, "").localeCompare(b.replace(/^v(?=\d)/, ""), "en", { numeric: true, sensitivity: "base" })
      || a.localeCompare(b);
  });
}
