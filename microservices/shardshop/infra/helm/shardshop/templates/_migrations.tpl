{{- define "shardshop.migrationConfigMap" -}}
{{- $files := include "shardshop.migrationFiles" . | fromJson -}}
{{- if $files -}}
{{- dict "apiVersion" "v1" "kind" "ConfigMap" "immutable" true
      "metadata" (dict "name" (include "shardshop.migrationConfigMapName" .) "namespace" .root.Release.Namespace)
      "data" $files | toPrettyJson -}}
{{- end -}}
{{- end -}}

{{- define "shardshop.migrationJob" -}}
{{- $root := .root -}}
{{- $ledger := eq .stream "ledger" -}}
{{- $shardIndex := .index | default 0 -}}
{{- $region := "" -}}
{{- range $index, $shard := $root.Values.shards -}}
  {{- if eq $shard.name $.shard -}}
    {{- $shardIndex = $index -}}
    {{- $region = $shard.region -}}
  {{- end -}}
{{- end }}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ include "shardshop.migrationJobName" . }}
  namespace: {{ $root.Release.Namespace | quote }}
  {{- if .hook }}
  annotations:
    helm.sh/hook: post-install,post-upgrade
    helm.sh/hook-weight: {{ .weight | quote }}
    helm.sh/hook-delete-policy: hook-succeeded
  {{- end }}
  labels:
    shardshop.javacraft/migration-target: {{ .shard | quote }}
    app.kubernetes.io/name: schema-migration
    app.kubernetes.io/component: {{ .stream }}
    {{- if not $ledger }}
    shardshop.javacraft/region: {{ $region | quote }}
    {{- end }}
spec:
  backoffLimit: 0
  activeDeadlineSeconds: {{ $root.Values.migration.activeDeadlineSeconds }}
  {{- if .hook }}
  ttlSecondsAfterFinished: {{ $root.Values.migration.ttlSecondsAfterFinished }}
  {{- end }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: schema-migration
        app.kubernetes.io/component: {{ .stream }}
        {{- if not $ledger }}
        shardshop.javacraft/region: {{ $region | quote }}
        {{- end }}
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: flyway
          image: {{ $root.Values.migration.image }}
          args: [-configFiles=/flyway/sql/flyway.toml, migrate]
          env:
            {{- if not $ledger }}
            - name: SHARD_NAME
              value: {{ .shard | quote }}
            - name: FLYWAY_PLACEHOLDERS_SHARDREGION
              value: {{ $region | quote }}
            - name: FLYWAY_PLACEHOLDERS_SHARDINDEX
              value: {{ $shardIndex | quote }}
            - name: FLYWAY_PLACEHOLDERS_SHARDCOUNT
              value: {{ len $root.Values.shards | quote }}
            {{- end }}
            - name: FLYWAY_URL
              {{- if $ledger }}
              value: jdbc:postgresql://ledger-db-rw.{{ $root.Release.Namespace }}.svc:5432/ledger?sslmode=verify-full&sslrootcert=/etc/flyway/ca.crt
              {{- else }}
              value: jdbc:postgresql://$(SHARD_NAME)-rw.{{ $root.Release.Namespace }}.svc:5432/shardshop?sslmode=verify-full&sslrootcert=/etc/flyway/ca.crt
              {{- end }}
            - name: FLYWAY_USER
              valueFrom:
                secretKeyRef:
                  name: {{ .stream }}-migrator
                  key: username
            - name: FLYWAY_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .stream }}-migrator
                  key: password
            - name: HOME
              value: /tmp
            - name: REDGATE_DISABLE_TELEMETRY
              value: "true"
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          resources:
            {{- toYaml $root.Values.migration.resources | nindent 12 }}
          volumeMounts:
            - name: migrations
              mountPath: /flyway/sql
              readOnly: true
            - name: ca
              mountPath: /etc/flyway
              readOnly: true
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: migrations
          configMap:
            name: {{ include "shardshop.migrationConfigMapName" . }}
        - name: ca
          secret:
            secretName: {{ .shard }}-ca
            items:
              - key: ca.crt
                path: ca.crt
        - name: tmp
          emptyDir:
            sizeLimit: 32Mi
{{- end -}}
