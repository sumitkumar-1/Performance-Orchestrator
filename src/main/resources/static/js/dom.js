export const el = (tag, attrs = {}, ...children) => {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key === "class") node.className = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "text") node.textContent = value;
    else if (value !== undefined && value !== false)
      node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat())
    if (child !== null && child !== undefined)
      node.append(
        child instanceof Node ? child : document.createTextNode(String(child)),
      );
  return node;
};

export function labeled(text, control) {
  return el("label", {}, text, control);
}
export function input(value = "", type = "text", attrs = {}) {
  return el("input", { type, value, ...attrs });
}
export function select(options, value) {
  const node = el(
    "select",
    {},
    options.map(([id, label]) => el("option", { value: id }, label)),
  );
  node.value = value;
  return node;
}
