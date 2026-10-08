import { el } from "./dom.js";

const field = (label, value) =>
  el("div", {}, el("dt", {}, label), el("dd", {}, value || "Not recorded"));

export function pinnedInputs(plan) {
  const services = plan.services || [];
  const configuration = plan.effectiveLoadConfiguration || {};
  const load = configuration.loadService;
  const serviceCount = services.filter((service) => service.serviceId !== load).length;
  return el(
    "details",
    { class: "card spacer pinned-inputs" },
    el(
      "summary",
      {},
      el("span", { class: "pinned-inputs-title" }, "Pinned inputs"),
      el(
        "span",
        { class: "pinned-inputs-caption" },
        `${serviceCount} service${serviceCount === 1 ? "" : "s"}${services.some((service) => service.serviceId === load) ? " · 1 load generator" : ""}`,
      ),
    ),
    el(
      "div",
      { class: "pinned-inputs-body" },
      el("p", { class: "muted" }, "Saved at review time. These inputs stay fixed for this run."),
      el(
        "dl",
        { class: "pinned-inputs-facts" },
        field("Environment", plan.profile?.targetEnvironment),
        field("Kubernetes context", configuration.target?.context),
      ),
      el(
        "ol",
        { class: "pinned-inputs-services", "aria-label": "Prepared services in deployment order" },
        services.map((service) =>
          el(
            "li",
            { class: "pinned-inputs-service" },
            el(
              "div",
              { class: "pinned-inputs-service-heading" },
              el("h3", {}, service.serviceId),
              el(
                "span",
                { class: "pinned-inputs-role" },
                service.serviceId === load ? "Load generator" : "Service",
              ),
            ),
            el(
              "dl",
              { class: "pinned-inputs-facts" },
              field("Image version", service.image?.version),
              field("Image source", service.image?.sourceRef),
              field("Namespace", service.namespace),
              field("Helm release", service.releaseName),
            ),
            service.sourceRevision
              ? el(
                  "details",
                  { class: "pinned-inputs-source" },
                  el("summary", {}, "Source details"),
                  el(
                    "dl",
                    { class: "pinned-inputs-facts" },
                    field("Git commit", service.sourceRevision),
                    field("Helm chart", configuration.charts?.[service.serviceId]),
                  ),
                )
              : null,
          ),
        ),
      ),
      services.length
        ? null
        : el("p", { class: "muted" }, "No service inputs were recorded for this run."),
      el(
        "div",
        { class: "pinned-inputs-actions" },
        el("a", { href: "#plan/" + plan.id }, "View reviewed plan"),
        el("a", { href: "#configure", class: "button" }, "Prepare a new run"),
      ),
    ),
  );
}
