import { el } from "./dom.js";

export function reviewProgress(api) {
  const title = el("h2", {}, "Preparing review"),
    summary = el("p", { class: "muted", role: "status" }),
    rows = el("div", { class: "review-progress-rows" });
  const node = el("section", { class: "card review-progress", hidden: true }, title, summary, rows);
  let id = null,
    timer,
    closed = false,
    epoch = 0,
    started = 0,
    latest = null;
  const duration = (ms) => {
    const seconds = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  };
  function render(state = "RUNNING") {
    const running = state === "RUNNING";
    node.classList.toggle("is-preparing", running);
    title.textContent = running
      ? "Preparing review"
      : state === "READY"
        ? "Review ready"
        : "Review stopped";
    summary.textContent = `${latest?.stage || "Connecting to review…"} · ${duration(Date.now() - started)} elapsed`;
    rows.replaceChildren(
      ...(latest?.services || []).map((service) => {
        const ready = service.stage === "Ready",
          failed = service.stage === "Failed";
        const active = running && !ready && !failed && service.stage !== "Queued";
        const stage = !running && !ready && !failed ? "Not completed" : service.stage;
        const time = service.startedAt
          ? ` · ${duration((ready || failed || !running ? Date.parse(service.updatedAt) : Date.now()) - Date.parse(service.startedAt))}`
          : "";
        return el(
          "div",
          { class: "review-progress-row" },
          el(
            "span",
            {
              class: `review-stage-icon ${active ? "spinning" : ready ? "ready" : failed ? "failed" : ""}`,
              "aria-hidden": "true",
            },
            active ? "◌" : ready ? "✓" : failed ? "✕" : "–",
          ),
          el(
            "div",
            {},
            el("strong", {}, service.serviceId),
            el("p", { class: "muted" }, stage + time),
          ),
        );
      }),
    );
  }
  async function poll(ticket) {
    try {
      const value = await api(`/real/preparations/${id}`);
      if (closed || ticket !== epoch) return;
      latest = value;
      render(value.state);
    } catch {
      if (closed || ticket !== epoch) return;
      summary.textContent = `Waiting for progress · ${duration(Date.now() - started)} elapsed. Review is still running.`;
    }
    if (!closed && ticket === epoch) timer = setTimeout(() => poll(ticket), 1500);
  }
  return {
    node,
    start(profile) {
      clearTimeout(timer);
      epoch++;
      id = crypto.randomUUID();
      started = Date.now();
      latest = {
        stage: "Checking cluster connection",
        services: [...profile.services, profile.loadGenerator].map((service) => ({
          serviceId: service.serviceId,
          stage: "Queued",
        })),
      };
      node.hidden = false;
      render();
      node.scrollIntoView({
        block: "nearest",
        behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth",
      });
      timer = setTimeout(() => poll(epoch), 500);
      return id;
    },
    async finish(success) {
      if (!id || closed) return;
      clearTimeout(timer);
      const ticket = ++epoch;
      try {
        const value = await api(`/real/preparations/${id}`);
        if (!closed && ticket === epoch) latest = value;
      } catch {}
      if (!closed && ticket === epoch) {
        if (latest)
          latest.stage = success
            ? "Charts prepared; nothing deployed"
            : "See the error above for details";
        render(success ? "READY" : "FAILED");
      }
    },
    dispose() {
      closed = true;
      epoch++;
      clearTimeout(timer);
    },
  };
}
