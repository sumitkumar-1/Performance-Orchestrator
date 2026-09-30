// Stable releases first, then development tags; numeric components sort naturally ascending.
export function sortImageVersions(tags) {
  const stable = tag => /^v?\d+\.\d+\.\d+(?:\+[^\s]+)?$/.test(tag);
  return [...new Set(tags)].sort((a, b) => Number(stable(b)) - Number(stable(a))
    || a.replace(/^v(?=\d)/, "").localeCompare(b.replace(/^v(?=\d)/, ""), "en", { numeric: true, sensitivity: "base" }) || a.localeCompare(b));
}
