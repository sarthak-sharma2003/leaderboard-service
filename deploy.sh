#!/usr/bin/env bash
# Deploys the image CI published to Azure Container Apps: the service plus a Redis sidecar,
# with Postgres on Neon. Run `az login` and `npx neonctl auth` first, and install the CLI
# extension: `az extension add -n containerapp --allow-preview true`. Safe to re-run.
#   NEON_PROJECT=<neon project id> ./deploy.sh
set -euo pipefail

RG=leaderboard-rg
LOCATION=canadacentral
APP=leaderboard
IMAGE=ghcr.io/sarthak-sharma2003/leaderboard-service:latest
NEON_PROJECT=${NEON_PROJECT:?set NEON_PROJECT to the Neon project id}

az group create --name "$RG" --location "$LOCATION" --output none
# Not an express environment: express supports neither HTTP/2 ingress (gRPC) nor sidecars.
az containerapp env show --name "$APP-env" --resource-group "$RG" --output none 2>/dev/null ||
  az containerapp env create --name "$APP-env" --resource-group "$RG" --location "$LOCATION" \
    --environment-mode WorkloadProfiles --logs-destination none --output none
env_id=$(az containerapp env show --name "$APP-env" --resource-group "$RG" --query id --output tsv)

# The database password goes from Neon straight into the app's secret store through a private
# temp file that is removed on exit. It is never printed.
db=$(npx --yes neonctl connection-string --project-id "$NEON_PROJECT" --extended --output json)
spec=$(mktemp)
trap 'rm -f "$spec"' EXIT
cat > "$spec" <<EOF
location: $LOCATION
properties:
  managedEnvironmentId: $env_id
  configuration:
    ingress: { external: true, targetPort: 9090, transport: http2, allowInsecure: false }
    secrets:
      - name: db-password
        value: $(jq .password <<<"$db")
  template:
    containers:
      - name: app
        image: $IMAGE
        resources: { cpu: 0.5, memory: 1Gi }
        env:
          - name: SPRING_DATASOURCE_URL
            value: "jdbc:postgresql://$(jq -r .host <<<"$db")/$(jq -r .database <<<"$db")?sslmode=require"
          - name: SPRING_DATASOURCE_USERNAME
            value: $(jq .role <<<"$db")
          - name: SPRING_DATASOURCE_PASSWORD
            secretRef: db-password
      # Redis lives and dies with the replica. When the app scales to zero the cache is lost,
      # and the next request rebuilds each leaderboard from Postgres.
      - name: redis
        image: public.ecr.aws/docker/library/redis:8-alpine
        resources: { cpu: 0.25, memory: 0.5Gi }
    scale: { minReplicas: 0, maxReplicas: 1 }
EOF
az containerapp create --name "$APP" --resource-group "$RG" --yaml "$spec" --output none

fqdn=$(az containerapp show --name "$APP" --resource-group "$RG" --query properties.configuration.ingress.fqdn --output tsv)
echo "gRPC endpoint: $fqdn:443"
