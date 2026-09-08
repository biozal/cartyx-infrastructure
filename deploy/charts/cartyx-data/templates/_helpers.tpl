{{- define "data.name" -}}
{{- if ne .Release.Name "cartyx-data" }}{{ fail "release name must be cartyx-data (certificate and service identities)" }}{{ end -}}
{{- if not (has .Values.environment (list "local" "dev" "prod" "restore")) }}{{ fail "environment must be local, dev, prod, or restore" }}{{ end -}}
{{- if or (ne (int .Values.cassandra.replicas) 1) (ne (int .Values.janusgraph.replicas) 1) }}{{ fail "single-node topology requires replicas=1; scaling needs a separate migration" }}{{ end -}}
{{- range $value := list .Values.graphKeyspace .Values.stateKeyspace .Values.datacenter -}}
{{- if not (regexMatch "^[a-z][a-z0-9_]*$" $value) }}{{ fail "invalid keyspace/datacenter identifier" }}{{ end -}}
{{- end -}}
{{- if eq .Values.graphKeyspace .Values.stateKeyspace }}{{ fail "graph and application state require separate keyspaces" }}{{ end -}}
{{- range $image := list .Values.cassandra.image .Values.janusgraph.image -}}
{{- if not (regexMatch "@sha256:[a-f0-9]{64}$" $image) }}{{ fail "data images must be pinned by SHA256 digest" }}{{ end -}}
{{- end -}}
{{- $secret := required "secretName is required" .Values.secretName -}}
cartyx-data
{{- end -}}
{{- define "data.labels" -}}
app.kubernetes.io/name: cartyx-data
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: cartyx-data
cartyx.io/environment: {{ .Values.environment | quote }}
{{- end -}}
