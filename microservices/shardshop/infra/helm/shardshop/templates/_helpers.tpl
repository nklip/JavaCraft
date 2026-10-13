{{/* Additional validation that JSON Schema cannot express for a list of objects. */}}
{{- define "shardshop.validate" -}}
{{- $seen := dict -}}
{{- range .Values.shards -}}
  {{- if hasKey $seen .name -}}
    {{- fail (printf "duplicate shard name: %s" .name) -}}
  {{- end -}}
  {{- $_ := set $seen .name true -}}
{{- end -}}
{{- if eq .Values.render "migration" -}}
  {{- if eq .Values.migrationStream "ledger" -}}
    {{- if ne .Values.migrationShard "ledger-db" -}}
      {{- fail "the ledger migration stream requires migrationShard=ledger-db" -}}
    {{- end -}}
  {{- else if not (hasKey $seen .Values.migrationShard) -}}
    {{- fail "catalog and ordering migrationShard must name one member of the shard inventory" -}}
  {{- end -}}
{{- end -}}
{{- end -}}

{{- define "shardshop.names" -}}
{{- $names := list -}}
{{- range .Values.shards -}}
  {{- $names = append $names .name -}}
{{- end -}}
{{- join "," $names -}}
{{- end -}}

{{- define "shardshop.regions" -}}
{{- $regions := list -}}
{{- range .Values.shards -}}
  {{- $regions = append $regions .region -}}
{{- end -}}
{{- join "," $regions -}}
{{- end -}}

{{- define "shardshop.regionVersion" -}}
{{- $entries := list -}}
{{- range .Values.shards -}}
  {{- $entries = append $entries (printf "%s=%s" .name .region) -}}
{{- end -}}
{{- join "," $entries | sha256sum -}}
{{- end -}}

{{/* Every cluster uses the same image, resource budget, storage, and hardening. */}}
{{- define "shardshop.clusterCommon" -}}
imageName: {{ .root.Values.postgresql.image }}
enableSuperuserAccess: false
bootstrap:
  initdb:
    database: {{ .database }}
    owner: {{ .owner }}
    postInitApplicationSQL:
      - REVOKE TEMPORARY ON DATABASE {{ .database }} FROM PUBLIC
      - REVOKE ALL ON SCHEMA public FROM PUBLIC
storage:
  {{- toYaml .root.Values.postgresql.storage | nindent 2 }}
resources:
  {{- toYaml .root.Values.postgresql.resources | nindent 2 }}
{{- $affinity := mergeOverwrite (deepCopy .affinity) .root.Values.postgresql.affinity -}}
{{- if $affinity }}
affinity:
  {{- toYaml $affinity | nindent 2 }}
{{- end -}}
{{- end -}}

{{/* Restricted Pod Security without fixed IDs: each application image selects its non-root user. */}}
{{- define "shardshop.podSecurity" -}}
securityContext:
  runAsNonRoot: true
  seccompProfile:
    type: RuntimeDefault
{{- end -}}

{{- define "shardshop.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: [ALL]
{{- end -}}

{{/* Workloads use only HTTP: no Kubernetes API token, database Secret or Service variables. */}}
{{- define "shardshop.workloadPod" -}}
automountServiceAccountToken: false
enableServiceLinks: false
{{ include "shardshop.podSecurity" . }}
{{- end -}}

{{/* One source for the mounted file and its pod checksum, including static defaults. */}}
{{- define "shardshop.productConfiguration" -}}
{{ range .Values.shards -}}
shardshop.catalog.connections.{{ .name }}.password=${PRODUCT_APP_PASSWORD}
{{ end -}}
{{ range $key, $value := .Values.product.configuration -}}
{{ $key }}={{ $value }}
{{ end -}}
{{- end -}}

{{- define "shardshop.productRevision" -}}
{{- include "shardshop.productConfiguration" . | sha256sum -}}
{{- end -}}

{{- define "shardshop.seederJobName" -}}
{{- if eq .Values.render "application-release" -}}
{{- printf "%s-seeder-%d" (.Release.Name | trunc 40 | trimSuffix "-") .Release.Revision -}}
{{- else -}}
{{- printf "shardshop-product-seeder-%s" (required "legacy seeding requires an explicit run" .Values.run) -}}
{{- end -}}

{{- end -}}

{{- define "shardshop.migrationFiles" -}}
{{- $directory := printf "migrations/shard/%s/*" .stream -}}
{{- if eq .stream "ledger" -}}{{- $directory = "migrations/ledger/ledger/*" -}}{{- end -}}
{{- $files := dict -}}
{{- range $path, $_ := .root.Files.Glob $directory -}}
{{- $_ := set $files (base $path) ($.root.Files.Get $path) -}}
{{- end -}}
{{- if and (.root.Files.Glob "migrations/**") (not (hasKey $files "flyway.toml")) -}}
{{- fail (printf "migration snapshot is missing %s/flyway.toml" .stream) -}}
{{- end -}}
{{- $files | toJson -}}
{{- end -}}

{{- define "shardshop.migrationConfigMapName" -}}
{{- $files := include "shardshop.migrationFiles" . | fromJson -}}
{{- if $files -}}
{{- printf "%s-migrations-%s" .stream (toJson $files | sha256sum | trunc 24) -}}
{{- else -}}
{{- printf "%s-migrations" .stream -}}
{{- end -}}
{{- end -}}

{{- define "shardshop.migrationJobName" -}}
{{- $prefix := printf "%s-%s-migration" .shard .stream -}}
{{- if .hook -}}
{{- printf "%s-%s-%d-r%d" (.root.Release.Name | trunc 32 | trimSuffix "-") .stream .index .root.Release.Revision -}}
{{- else -}}
{{- $prefix -}}
{{- end -}}
{{- end -}}
