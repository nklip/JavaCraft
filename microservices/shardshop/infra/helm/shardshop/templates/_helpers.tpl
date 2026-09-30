{{/* Additional validation that JSON Schema cannot express for a list of objects. */}}
{{- define "shardshop.validate" -}}
{{- $seen := dict -}}
{{- range .Values.shards -}}
  {{- if hasKey $seen .name -}}
    {{- fail (printf "duplicate shard name: %s" .name) -}}
  {{- end -}}
  {{- $_ := set $seen .name true -}}
{{- end -}}
{{- if and (eq .Values.render "migration") (not (hasKey $seen .Values.migrationShard)) -}}
  {{- fail "migrationShard must name one member of the shard inventory" -}}
{{- end -}}
{{- end -}}

{{- define "shardshop.names" -}}
{{- $names := list -}}
{{- range .Values.shards -}}
  {{- $names = append $names .name -}}
{{- end -}}
{{- join "," $names -}}
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
