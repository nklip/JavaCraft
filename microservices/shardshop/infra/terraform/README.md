# AWS foundation

[ShardShop](../../README.md) › [Infrastructure](../README.md) › **AWS foundation** · [Plan milestone 7](../../PLAN.md#milestone-7-aws-deployment)

Terraform creates the AWS foundation of milestone 7: a VPC, an EKS
cluster, ECR repositories, a backup bucket, the runtime secrets and the IAM roles of
the workloads. The [runbook](#deliver-the-database-layer-with-argo-cd) then installs the database
tier of the shared chart on EKS and runs the drills. The local deployment does not use
these resources. The versions are in [VERSIONS.md](../../VERSIONS.md#aws-foundation).

## Design

There are two Terraform root modules:

- [`state/`](state) creates the S3 bucket for the Terraform state, with versioning,
  encryption and blocked public access. This bucket must exist before the other
  module, thus `state/` keeps its own state in a local file.
- [`aws/`](aws) creates the foundation. Its state is in the S3 bucket, and the S3
  lock file (`use_lockfile`) prevents two runs at the same time.

| Part | Design |
|---|---|
| Network | VPC `10.40.0.0/16` in the first three zones of `eu-west-2`. In each zone, a private `/20` subnet for the workers and a public `/24` subnet. One shared NAT gateway (`nat_gateway_per_zone = true` gives one in each zone). A free S3 gateway endpoint for backups and ECR image layers. |
| Cluster | EKS `shardshop` on Kubernetes 1.36, because CloudNativePG 1.30 supports 1.34–1.36. The upgrade policy `STANDARD` prevents paid extended support. The public API endpoint accepts only `api_allowed_cidrs`. Access entries only (`authentication_mode = "API"`), and the creator is a cluster administrator. Audit and authenticator logs stay 7 days. |
| Workers | One managed node group in each zone, because an EBS volume stays in its zone. Graviton `t4g.large` with Amazon Linux 2023 arm64, because the images are linux/arm64. This size holds the database tier with the backup sidecars, three brokers, one restore cluster and the add-ons, but not the applications (`node_instance_type`). A `t4g.medium` (4 GiB) does not hold the backup sidecars. IMDSv2 with a hop limit of 1. |
| Storage | [`eks-values.yaml`](../helm/shardshop/eks-values.yaml) makes the shared chart create the StorageClass `shardshop-gp3`: encrypted gp3 volumes with the tag `Project=shardshop`, created in the zone of the scheduled pod and deleted with their claim. Each shard has one instance in each zone. |
| Add-ons | `vpc-cni`, `eks-pod-identity-agent`, `kube-proxy`, `coredns`, `aws-ebs-csi-driver`, the community add-on `cert-manager` for the backup plugin (its webhook port 10260 has its own node rule), and `aws-secrets-store-csi-driver-provider` with the Kubernetes Secret sync. Each one uses the newest add-on version for the cluster version at apply time. |
| Images | One ECR repository for each image in [`skaffold.yaml`](../../skaffold.yaml), with immutable tags and a scan on push. Four repositories below `shardshop-charts/` keep the OCI charts that Argo CD applies. |
| Delivery | A self-managed [Argo CD](../helm/argocd/values.yaml) applies the database layer from the [delivery chart](../helm/delivery): one child Application for each layer, in sync waves, with digest-pinned charts. |
| Backups | Bucket `shardshop-backups-<account>-<region>` with SSE-S3 encryption, blocked public access and a TLS-only policy. The [Barman Cloud Plugin](../helm/barman-cloud/values.yaml) archives the WAL of each database cluster continuously below the prefix of the cluster, for example `shard-a/`. A base backup starts with the cluster and then runs each day. The plugin keeps 7 days. |
| Secrets | One AWS Secrets Manager secret for each login role of `runtimeSecrets` in `eks-values.yaml`, and `shardshop/rabbitmq` with the broker passwords, the Erlang cookie and the `users.json` of [`users.jq`](../helm/shardshop/files/rabbitmq/users.jq). The passwords are ephemeral and the values write-only, thus no password is in the Terraform state. A destroy deletes the secrets without a recovery window. The shared chart syncs them to the Kubernetes Secrets that kind creates with `cluster-create.sh`, through a holder pod that mounts them. |
| Broker | Three RabbitMQ brokers, one in each zone. Each quorum queue has a member on each broker. |
| IAM | The worker role has only the node, CNI and ECR pull policies. EKS Pod Identity gives roles to the EBS CSI driver and to each CloudNativePG cluster of [`shards.yaml`](../shards.yaml) and `ledger-db`. Only this cluster can use these roles. A backup role can write only below its own prefix, for example `shard-a/`. A restore role lets the cluster `<cluster>-pitr` read only the prefix of its source. The holder pod can read only the runtime secrets. |

`terraform destroy` removes everything that the modules create:

- ECR deletes the repositories with their images (`force_delete`).
- S3 deletes the buckets with their objects (`force_destroy`).
- The modules create no customer managed KMS key, because such a key stays for at
  least 7 days after its deletion. EKS encrypts the Kubernetes API data with an
  AWS owned key.

The Terraform state does not contain the EBS volumes of the database and broker
claims. Thus [remove the database tier](#remove-the-database-tier) before the
destroy, while the EBS CSI driver still runs.

[`operator-policy.json`](operator-policy.json) gives the permissions for these
modules to the IAM user that runs Terraform:

- All names must start with `shardshop`, and the region must be `eu-west-2`.
- The user can attach only the AWS managed EKS, ECR pull and EBS CSI policies, and
  can pass the roles only to EKS, EC2 and EKS Pod Identity.
- The user can get an ECR login token, and can delete only EBS volumes with the tag
  `Project=shardshop`.
- The user can manage only the Secrets Manager secrets below `shardshop/`.
- This policy is not a full privilege boundary. The user can write an inline
  policy for a `shardshop-*` role and give that role to a pod.

### Costs

These are the on-demand prices in `eu-west-2`, without tax, from the AWS public
price list of 2026-10-29:

| Item | Price |
|---|---|
| EKS control plane, standard support | US$0.10 for each hour |
| NAT gateway | US$0.05 for each hour, and US$0.05 for each GB |
| Public IPv4 address of the NAT gateway | US$0.005 for each hour |
| Three `t4g.large` workers | US$0.2256 for each hour (3 × US$0.0752, price list of 2026-11-01) |
| Three 20 GB gp3 root volumes | US$0.0076 for each hour (US$0.0928 for each GB-month) |
| Thirteen 2 GiB gp3 database and broker volumes of the database tier | US$0.0033 for each hour |
| Nine Secrets Manager secrets | US$0.40 for each secret-month (US$0.005 for each hour), and US$0.05 for each 10,000 API calls |
| Total while the foundation and the database tier run | Approximately US$0.40 for each hour, or US$290 for each month |
| EKS audit and authenticator logs | US$0.5985 for each GB |

S3, ECR and the S3 gateway endpoint cost almost nothing with the small backups of a
run. Destroy the foundation after each use.

## Operation

Run the commands from the repository root with the AWS profile of the operator
user. The examples use the operator user `NikitaDev`. Replace `<account-id>` with
the 12-digit AWS account ID, which this command shows:

```bash
aws sts get-caller-identity --query Account --output text
```

### Give the permissions to the operator user

An administrator of the AWS account does these steps one time. Do not use the root
user if an IAM administrator is available.

1. Sign in to the AWS console as the administrator.
2. Open **IAM**, then **Policies**, then select **Create policy**.
3. Select **JSON**. Remove the example text, and paste all the text of
   `microservices/shardshop/infra/terraform/operator-policy.json`.
4. Examine the findings of the policy editor below the JSON. If there is an error
   or a security warning, stop and report the finding text.
5. Select **Next**. Type the policy name `ShardShopTerraformOperator`.
6. Select **Create policy**.
7. Open **IAM**, then **Users**, then the user `NikitaDev`.
8. On the **Permissions** tab, select **Add permissions**, then **Add permissions**
   again.
9. Select **Attach policies directly**. Search for `ShardShopTerraformOperator`,
   and select its check box.
10. Select **Next**, then **Add permissions**.

An administrator with the AWS CLI can do the same with two commands. Replace
`<admin-profile>` with the AWS CLI profile of the administrator:

```bash
aws iam create-policy --profile <admin-profile> --policy-name ShardShopTerraformOperator --policy-document file://microservices/shardshop/infra/terraform/operator-policy.json
```

```bash
aws iam attach-user-policy --profile <admin-profile> --user-name NikitaDev --policy-arn arn:aws:iam::<account-id>:policy/ShardShopTerraformOperator
```

Then, as the operator user, make sure that the permissions work. The command must
show `"clusters": []`:

```bash
aws eks list-clusters --region eu-west-2
```

If `operator-policy.json` changes, the administrator replaces the policy text. In
the console, open the policy, select **Edit**, paste the new JSON and select
**Save changes**. With the AWS CLI:

```bash
aws iam create-policy-version --profile <admin-profile> --policy-arn arn:aws:iam::<account-id>:policy/ShardShopTerraformOperator --policy-document file://microservices/shardshop/infra/terraform/operator-policy.json --set-as-default
```

### Create the state bucket

```bash
terraform -chdir=microservices/shardshop/infra/terraform/state init
terraform -chdir=microservices/shardshop/infra/terraform/state apply
```

Keep `state/terraform.tfstate`. It is the only record of the state bucket, and Git
ignores it.

### Create the foundation

Give the public address of this computer to the API endpoint filter, then
initialize the backend with the state bucket:

```bash
export TF_VAR_api_allowed_cidrs="[\"$(curl -s https://checkip.amazonaws.com)/32\"]"
terraform -chdir=microservices/shardshop/infra/terraform/aws init -backend-config="bucket=$(terraform -chdir=microservices/shardshop/infra/terraform/state output -raw bucket)"
terraform -chdir=microservices/shardshop/infra/terraform/aws plan -out=foundation.tfplan
terraform -chdir=microservices/shardshop/infra/terraform/aws apply foundation.tfplan
```

The apply takes approximately 15 minutes. If the public address changes, set the
variable again and apply again.

A VPN or proxy can use a different exit address for each connection. Then one
`/32` block is not sufficient, and kubectl fails with `TLS handshake timeout`. In
this case, open the endpoint for the run with `["0.0.0.0/0"]`. Then IAM
authentication and the access entries are the only protection:

```bash
export TF_VAR_api_allowed_cidrs='["0.0.0.0/0"]'
```

### Check the foundation

The `kind` Maven profile of the drills uses the context `kind-<cluster>` and a
private kubeconfig outside `~/.kube`. Thus write the cluster credentials to a new
private directory, with the context `kind-shardshop-test-eks`. The kind contexts
stay unchanged. Use the same shell until the destroy, because `$run` keeps the
credentials of the run:

```bash
run=$(mktemp -d)
export KUBECONFIG="$run/kubeconfig"
aws eks update-kubeconfig --region eu-west-2 --name shardshop --alias kind-shardshop-test-eks --kubeconfig "$KUBECONFIG"
chmod 600 "$KUBECONFIG"
kubectl --context kind-shardshop-test-eks get nodes -L topology.kubernetes.io/zone -L kubernetes.io/arch
kubectl --context kind-shardshop-test-eks -n kube-system get pods
```

There must be three `Ready` arm64 nodes, one in each zone of the
`availability_zones` output, and all the `kube-system` pods must be `Running`.

### Deliver the database layer with Argo CD

Argo CD applies the database layer from OCI charts in ECR, with a digest pin for
each chart. The delivery chart [`infra/helm/delivery`](../helm/delivery) makes one
child Application for each layer, in this sync-wave order:

| Wave | Application | Contents |
|---:|---|---|
| 0 | `cnpg` | The vendored CloudNativePG chart and its values |
| 1 | `barman-cloud` | The vendored Barman Cloud Plugin chart and its values |
| 2 | `shardshop-namespace` | The namespace with the restricted Pod Security Standard |
| 3 | `shardshop-infrastructure` | Databases, backups, broker and runtime secrets. The ObjectStore comes before the clusters |
| 4 | `shardshop-migrations` | The Flyway Jobs as PostSync hooks |
| 5 | `shardshop-routing` | The routing ConfigMap, only after successful migrations |

The root Application `shardshop` applies a wave only after the Applications of the
earlier waves have synced successfully and are healthy. Each Application has a digest
pin, except `shardshop-migrations`: Argo CD 3.5 puts the first seven characters of
the revision into the name of each generated hook Job, and a digest starts with
`sha256:`. Thus the migrations use the chart version, and ECR keeps it immutable. Each sync creates new
migration Jobs with `generateName`. Argo CD deletes only a successful Job. A failed
or interrupted Job stays until its TTL of 24 hours ends. No Application prunes
resources or deletes them with the Application.

1. Copy the reviewed Flyway build to ECR. Set `config` to the
   `linux_arm64_config_digest` of `flyway_cli` in
   [versions.lock.yaml](../../versions.lock.yaml). If Docker does not have this
   image, run `scripts/up.sh` on the kind lab first.

   ```bash
   config=sha256:<linux_arm64_config_digest>
   registry=$(terraform -chdir=microservices/shardshop/infra/terraform/aws output -json ecr_repository_urls | jq -r '."shardshop-flyway"')
   aws ecr get-login-password --region eu-west-2 | docker login --username AWS --password-stdin "${registry%%/*}"
   docker tag "$config" "$registry:${config#sha256:}"
   docker push "$registry:${config#sha256:}"
   ```

2. Make sure that the ECR manifest refers to the reviewed image configuration. ECR
   gives a different manifest digest from the kind pin, because Docker compresses
   the layers for the push. Then write the values that contain the account ID: the
   ECR image and the backup bucket:

   ```bash
   aws ecr batch-get-image --region eu-west-2 --repository-name shardshop-flyway --image-ids "imageTag=${config#sha256:}" --query 'images[0].imageManifest' --output text | jq -e --arg config "$config" '.config.digest == $config'
   digest=$(aws ecr describe-images --region eu-west-2 --repository-name shardshop-flyway --image-ids "imageTag=${config#sha256:}" --query 'imageDetails[0].imageDigest' --output text)
   bucket=$(terraform -chdir=microservices/shardshop/infra/terraform/aws output -raw backup_bucket)
   printf 'migration:\n  image: %s@%s\nbackup:\n  destinationPath: s3://%s/\n' "$registry" "$digest" "$bucket" >"$run/aws-values.yaml"
   ```

3. Install Argo CD from its vendored chart:

   ```bash
   helm upgrade --install argocd microservices/shardshop/infra/helm/argocd/argo-cd-10.11.6.tgz --kube-context kind-shardshop-test-eks --namespace argocd --create-namespace --values microservices/shardshop/infra/helm/argocd/values.yaml --wait --timeout 10m
   ```

4. Push the charts to ECR. The shared chart contains the migrations and the
   inventory, and the delivery chart contains the values of the vendored charts. A
   new version for each push keeps the tags immutable. Each push prints the digest:

   ```bash
   charts="${registry%%/*}/shardshop-charts"
   aws ecr get-login-password --region eu-west-2 | helm registry login --username AWS --password-stdin "${registry%%/*}"
   push() { helm push "$1" "oci://$charts" 2>&1 | awk '/^Digest:/ {print $2}'; }
   version="0.3.0-run$(date -u +%Y%m%d%H%M%S)"
   chart=microservices/shardshop/infra/helm/shardshop
   cp -R "$chart" "$run/shardshop" && cp -R microservices/shardshop/database "$run/shardshop/migrations" && cp microservices/shardshop/infra/shards.yaml "$run/shardshop/"
   cp -R microservices/shardshop/infra/helm/delivery "$run/delivery" && mkdir "$run/delivery/files"
   cp microservices/shardshop/infra/helm/cnpg/values.yaml "$run/delivery/files/cnpg-values.yaml"
   cp microservices/shardshop/infra/helm/barman-cloud/values.yaml "$run/delivery/files/barman-cloud-values.yaml"
   mkdir "$run/packages"
   helm package "$run/shardshop" --version "$version" --destination "$run/packages"
   helm package "$run/delivery" --version "0.1.0-run${version#0.3.0-run}" --destination "$run/packages"
   cnpg_digest=$(push microservices/shardshop/infra/helm/cnpg/cloudnative-pg-0.29.1.tgz)
   barman_digest=$(push microservices/shardshop/infra/helm/barman-cloud/plugin-barman-cloud-0.8.1.tgz)
   shardshop_digest=$(push "$run/packages/shardshop-$version.tgz")
   delivery_digest=$(push "$run/packages/shardshop-delivery-0.1.0-run${version#0.3.0-run}.tgz")
   ```

5. Give Argo CD the ECR login for the charts, as an OCI credential and as a Helm OCI
   repository for the migrations. The token is valid for 12 hours:

   ```bash
   aws ecr get-login-password --region eu-west-2 >"$run/ecr-token"
   kubectl --context kind-shardshop-test-eks -n argocd create secret generic shardshop-charts --from-literal=type=oci --from-literal="url=oci://$charts" --from-literal=username=AWS --from-file="password=$run/ecr-token"
   kubectl --context kind-shardshop-test-eks -n argocd label secret shardshop-charts argocd.argoproj.io/secret-type=repo-creds
   kubectl --context kind-shardshop-test-eks -n argocd create secret generic shardshop-helm-charts --from-literal=type=helm --from-literal=enableOCI=true --from-literal="url=$charts" --from-literal=username=AWS --from-file="password=$run/ecr-token"
   kubectl --context kind-shardshop-test-eks -n argocd label secret shardshop-helm-charts argocd.argoproj.io/secret-type=repository
   ```

6. Write the delivery values, apply the root Application, and wait until all layers
   are synced and healthy. The sync of the root Application succeeds only after its
   last wave. A wait for the health of all Applications alone is not sufficient,
   because it ignores the child Applications that do not exist yet:

   ```bash
   { printf 'registry: %s\ncharts:\n  delivery: %s\n  cnpg: %s\n  barmanCloud: %s\n  shardshop: %s\n  shardshopVersion: %s\naccount:\n' "${registry%%/*}" "$delivery_digest" "$cnpg_digest" "$barman_digest" "$shardshop_digest" "$version"; sed 's/^/  /' "$run/aws-values.yaml"; } >"$run/delivery-values.yaml"
   helm template shardshop microservices/shardshop/infra/helm/delivery --values "$run/delivery-values.yaml" --set-string mode=root | kubectl --context kind-shardshop-test-eks apply -f -
   kubectl --context kind-shardshop-test-eks -n argocd wait --for=jsonpath='{.status.operationState.phase}'=Succeeded application/shardshop --timeout=30m
   kubectl --context kind-shardshop-test-eks -n argocd wait --for=jsonpath='{.status.health.status}'=Healthy application --all --timeout=5m
   kubectl --context kind-shardshop-test-eks -n argocd get applications
   ```

### Run the drills

Prepare the snapshot of the `kind` profile and build the observers. The profile
requires `kind.json`, but these scenarios do not read it:

```bash
mkdir "$run/snapshot"
helm template shardshop "$chart" --namespace shardshop --values microservices/shardshop/infra/shards.yaml --values "$chart/eks-values.yaml" --values "$run/aws-values.yaml" --set-string render=inventory | sed '/^# Source:/d; /^---$/d' >"$run/snapshot/inventory.json"
helm template shardshop "$chart" --namespace shardshop --values microservices/shardshop/infra/shards.yaml --values "$chart/eks-values.yaml" --values "$run/aws-values.yaml" --set-string render=infrastructure >"$run/snapshot/infrastructure.yaml"
printf '{}\n' >"$run/snapshot/kind.json"
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Dshardshop.infrastructureOnly=true install -DinstallAtEnd=true
```

Run the [topology check](../../shardshop-testing/RUNBOOK.md#topology-check-and-database-drill),
the delivery check and the EKS durability scenarios of
[`EksDurability.feature`](../../shardshop-testing/src/test/resources/kind-features/EksDurability.feature):

- `eks-delivery`: each layer comes from a digest-pinned OCI chart, is synced and
  healthy, and cannot prune or delete its resources.
- `eks-runtime-secrets`: each database and broker Secret comes from Secrets Manager,
  and CNPG applied each login role.
- `eks-backup`: each cluster archives its WAL and has a completed base backup.
- `eks-broker-quorum`: each quorum queue has a member on each of the three brokers.
  The scenario stops the broker that leads `ledger.record-order` and waits for a new
  leader of each queue and for the return of the broker.
- `eks-pitr-drill`: for each cluster, a probe row before and after a target time,
  then a restore of that time into the new cluster `<cluster>-pitr`. Only the row
  before the target time must be there. The drill deletes the new cluster and its
  volume. `-Dshardshop.kind.drillCluster=<cluster>` restores only one cluster.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT -Dshardshop.kind.snapshot="$run/snapshot" -Dshardshop.kind.cluster=shardshop-test-eks -Dshardshop.kind.kubeconfig="$KUBECONFIG" -Dshardshop.kind.tags='kind-topology | eks-delivery | eks-runtime-secrets | eks-backup | eks-broker-quorum'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT -Dshardshop.kind.snapshot="$run/snapshot" -Dshardshop.kind.cluster=shardshop-test-eks -Dshardshop.kind.kubeconfig="$KUBECONFIG" -Dshardshop.kind.tags=eks-pitr-drill
```

The reversible database drill of each shard disrupts the reads and writes of its
shard:

```bash
for shard in shard-a shard-b shard-c; do
    mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT -Dshardshop.kind.snapshot="$run/snapshot" -Dshardshop.kind.cluster=shardshop-test-eks -Dshardshop.kind.kubeconfig="$KUBECONFIG" -Dshardshop.kind.tags=kind-database-drill -Dshardshop.kind.drillShard="$shard" || break
done
```

The delivery drill uses two drill charts. The changed chart adds a catalog migration
that sleeps 12 seconds. The failure chart also adds a migration that always fails.
The drill does these steps:

1. It syncs again without changes. Then it checks for new migration Jobs and an
   unchanged schema history.
2. It applies the changed chart and interrupts the migration during its sleep. It
   checks that the interrupted Job and the routing stay, then it tries again.
3. It applies the failure chart and checks that the routing stays at the earlier
   revision.
4. It recovers with the changed chart, then it returns to the chart of the run.

```bash
drill=microservices/shardshop/shardshop-testing/src/test/resources/eks-delivery
cp -R "$run/shardshop" "$run/changed" && cp -R "$drill/changed/shard" "$run/changed/migrations/"
cp -R "$run/changed" "$run/failing" && cp -R "$drill/failing/shard" "$run/failing/migrations/"
helm package "$run/changed" --version "0.3.0-changed${version#0.3.0-run}" --destination "$run/packages"
helm package "$run/failing" --version "0.3.0-failing${version#0.3.0-run}" --destination "$run/packages"
changed_digest=$(push "$run/packages/shardshop-0.3.0-changed${version#0.3.0-run}.tgz")
failing_digest=$(push "$run/packages/shardshop-0.3.0-failing${version#0.3.0-run}.tgz")
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT -Dshardshop.kind.snapshot="$run/snapshot" -Dshardshop.kind.cluster=shardshop-test-eks -Dshardshop.kind.kubeconfig="$KUBECONFIG" -Dshardshop.kind.tags=eks-delivery-drill -Dshardshop.eks.changedChart="$changed_digest" -Dshardshop.eks.changedVersion="0.3.0-changed${version#0.3.0-run}" -Dshardshop.eks.failingChart="$failing_digest" -Dshardshop.eks.failingVersion="0.3.0-failing${version#0.3.0-run}"
```

The drill migrations stay in the databases. Thus a second drill on the same
databases needs drill charts that keep `V9001` and add copies with higher versions,
for example `V9003` and `V9004`. Give their versions with
`-Dshardshop.eks.changedMigration=9003 -Dshardshop.eks.failingMigration=9004`.
Without `V9001`, Flyway stops, because an applied migration is not in the chart.

After a failed migration, change the root Application to the corrected chart. If the
corrected chart was applied before, its ConfigMaps exist, and Argo CD does not repeat
the failed sync. Then sync the migrations again:

```bash
kubectl --context kind-shardshop-test-eks -n argocd patch application shardshop-migrations --type merge -p "{\"operation\":{\"initiatedBy\":{\"username\":\"runbook\"},\"sync\":{\"revision\":\"$version\"}}}"
```

If the drill stops before its end, the root Application can stay on a drill chart.
Set `charts.shardshop` and `charts.shardshopVersion` of the root Application back to
`$shardshop_digest` and `$version`.

### Remove the database tier

CAUTION: Make a copy of the data that you want to keep before you do this step.
This step deletes all the databases, the broker data and their EBS volumes.

First stop the delivery: delete the root Application before its children, and
uninstall Argo CD. The Applications have no resources finalizer, thus the resources
stay. The namespace deletion then removes the clusters, and the EBS CSI driver
deletes each volume. There must be no persistent volume after the wait. The backups
stay in the bucket until the destroy:

```bash
kubectl --context kind-shardshop-test-eks -n argocd delete application shardshop
kubectl --context kind-shardshop-test-eks -n argocd delete application --all
helm uninstall argocd --kube-context kind-shardshop-test-eks --namespace argocd --wait
kubectl --context kind-shardshop-test-eks delete namespace shardshop --timeout=10m
kubectl --context kind-shardshop-test-eks wait --for=delete persistentvolume --all --timeout=5m
kubectl --context kind-shardshop-test-eks delete namespace cnpg-system --timeout=5m
helm registry logout "${registry%%/*}"
docker logout "${registry%%/*}"
```

### Destroy the foundation

CAUTION: Make a copy of the backups and the images that you want to keep before you
do this step. `terraform destroy` deletes all the backups in the backup bucket and
all the images in ECR.

[Remove the database tier](#remove-the-database-tier) first, if it is installed.
Then destroy the foundation, then the state bucket:

```bash
terraform -chdir=microservices/shardshop/infra/terraform/aws destroy
terraform -chdir=microservices/shardshop/infra/terraform/state destroy
rm -rf "$run"
```

Then make sure that no resource stays. Each command must show an empty list:

```bash
aws resourcegroupstaggingapi get-resources --region eu-west-2 --tag-filters Key=Project,Values=shardshop --query 'ResourceTagMappingList[].ResourceARN'
aws ec2 describe-volumes --region eu-west-2 --filters Name=tag:Project,Values=shardshop --query 'Volumes[].VolumeId'
aws secretsmanager list-secrets --region eu-west-2 --filters Key=name,Values=shardshop/ --query 'SecretList[].Name'
aws iam list-roles --query "Roles[?starts_with(RoleName, 'shardshop-')].RoleName"
aws logs describe-log-groups --region eu-west-2 --log-group-name-prefix /aws/eks/shardshop --query 'logGroups[].logGroupName'
```

If a volume of a claim stays, delete it with `aws ec2 delete-volume --region eu-west-2 --volume-id <volume-id>`.

The tag index can show a deleted resource for some time after the destroy. Query
each listed resource directly, for example with `aws ec2 describe-nat-gateways`. A
NAT gateway in the state `deleted` costs nothing.

The AWS service-linked roles of EKS and Auto Scaling stay in the account. AWS
creates them one time for each account, and they cost nothing.
