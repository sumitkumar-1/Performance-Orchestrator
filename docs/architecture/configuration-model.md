# Configuration model

Startup defaults come from `application.yaml`; CKP Helm values render environment overrides into ConfigMaps. Runtime edits in Connections & catalog are validated and persisted as field-level JSON overrides. The effective configuration combines startup defaults and saved edits. Import/export supports moving those edits between installations.

The catalog defines environments, services and scenario templates. Each environment has a cluster identity and a shared Loki connection reference. Each service has a Bitbucket project/repository/chart path, an Artifactory image mapping, default namespace/release/values files, optional per-environment destination overrides, and monitoring credential references per environment. Connection definitions and secret references are separate from actual credentials.

A run chooses its environment from the catalog and uses the corresponding startup `execution.targets` mapping. Kubernetes commands receive an explicit context and the expected API-server identity is checked. Selecting an image repository stage never changes the target environment.

Run profiles save an ordered service list, a load-generator selection, edited values YAML, timing, monitoring panels and optional performance thresholds. Preparation pins Git commits, image identity, chart dependencies and effective values. Deployment uses that prepared snapshot. Additional load releases belong to their parent run and participate in its cleanup.

There is no simulator catalog or runtime mode. External transports and commands are replaced only inside tests. Historical configuration exports can contain retired mode/image-source fields; those fields are ignored, not used to select an execution backend.
