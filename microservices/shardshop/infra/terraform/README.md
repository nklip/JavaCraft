# AWS foundation

[ShardShop](../../README.md) › [Infrastructure](../README.md) › **AWS foundation** · [Plan milestone 7](../../PLAN.md#milestone-7-aws-deployment)

Terraform creates the AWS foundation of milestone 7: a VPC, an EKS
cluster, ECR repositories, a backup bucket, the runtime secrets and the IAM roles of
the workloads. The [runbook](#install-the-database-tier) then installs the database
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
| Images | One ECR repository for each image in [`skaffold.yaml`](../../skaffold.yaml), with immutable tags and a scan on push. |
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

### Install the database tier

These steps install the CloudNativePG operator, the backup plugin, the infrastructure
release of the shared chart and the schema migrations. The applications stay on kind.
The topology drills need the migrated `catalog` and `ordering` constraints and the
routing ConfigMap.

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

3. Install the operator with the same vendored chart and values as kind. Then
   install the vendored Barman Cloud Plugin in the same namespace. The plugin needs
   the `cert-manager` add-on of the foundation:

   ```bash
   helm upgrade --install cnpg microservices/shardshop/infra/helm/cnpg/cloudnative-pg-0.29.1.tgz --kube-context kind-shardshop-test-eks --namespace cnpg-system --create-namespace --values microservices/shardshop/infra/helm/cnpg/values.yaml --server-side=true --wait --timeout 10m
   helm upgrade --install barman-cloud microservices/shardshop/infra/helm/barman-cloud/plugin-barman-cloud-0.8.1.tgz --kube-context kind-shardshop-test-eks --namespace cnpg-system --values microservices/shardshop/infra/helm/barman-cloud/values.yaml --wait --timeout 10m
   ```

4. Create the namespace. The infrastructure release syncs the credentials from
   AWS Secrets Manager, thus there is no credential step:

   ```bash
   chart=microservices/shardshop/infra/helm/shardshop
   helm template shardshop "$chart" --namespace shardshop --values microservices/shardshop/infra/shards.yaml --values "$chart/eks-values.yaml" --values "$run/aws-values.yaml" --set-string render=namespace | kubectl --context kind-shardshop-test-eks apply -f -
   ```

5. Install the infrastructure release. Then restart the operator, and wait for the
   four database clusters. The plugin 0.15.1 can stop the first reconciliation of a
   cluster that arrives together with its ObjectStore, and CNPG 1.30.1 does not
   repeat it. The restart reconciles each cluster again:

   ```bash
   helm upgrade --install shardshop-infrastructure "$chart" --kube-context kind-shardshop-test-eks --namespace shardshop --values microservices/shardshop/infra/shards.yaml --values "$chart/eks-values.yaml" --values "$run/aws-values.yaml" --set-string render=infrastructure --server-side=false --wait --timeout 15m
   kubectl --context kind-shardshop-test-eks -n cnpg-system rollout restart deployment/cnpg-cloudnative-pg
   kubectl --context kind-shardshop-test-eks -n cnpg-system rollout status deployment/cnpg-cloudnative-pg --timeout=5m
   kubectl --context kind-shardshop-test-eks -n shardshop wait --for=condition=Ready cluster --all --timeout=15m
   ```

6. Run the migrations, then publish the routing ConfigMap. On kind, the
   application release publishes this ConfigMap:

   ```bash
   cp -R "$chart" "$run/chart"
   cp -R microservices/shardshop/database "$run/chart/migrations"
   helm upgrade --install shardshop-migrations "$run/chart" --kube-context kind-shardshop-test-eks --namespace shardshop --values microservices/shardshop/infra/shards.yaml --values "$chart/eks-values.yaml" --values "$run/aws-values.yaml" --set-string render=migrations --server-side=false --wait --timeout 20m
   helm template shardshop "$chart" --namespace shardshop --values microservices/shardshop/infra/shards.yaml --values "$chart/eks-values.yaml" --values "$run/aws-values.yaml" --set-string render=routing | kubectl --context kind-shardshop-test-eks apply -f -
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
then the reversible database drill on each shard. The drill disrupts the reads and
writes of its shard:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT -Dshardshop.kind.snapshot="$run/snapshot" -Dshardshop.kind.cluster=shardshop-test-eks -Dshardshop.kind.kubeconfig="$KUBECONFIG" -Dshardshop.kind.tags=kind-topology
for shard in shard-a shard-b shard-c; do
    mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT -Dshardshop.kind.snapshot="$run/snapshot" -Dshardshop.kind.cluster=shardshop-test-eks -Dshardshop.kind.kubeconfig="$KUBECONFIG" -Dshardshop.kind.tags=kind-database-drill -Dshardshop.kind.drillShard="$shard" || break
done
```

Run the EKS durability scenarios of
[`EksDurability.feature`](../../shardshop-testing/src/test/resources/kind-features/EksDurability.feature):

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
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT -Dshardshop.kind.snapshot="$run/snapshot" -Dshardshop.kind.cluster=shardshop-test-eks -Dshardshop.kind.kubeconfig="$KUBECONFIG" -Dshardshop.kind.tags='eks-runtime-secrets | eks-backup | eks-broker-quorum'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT -Dshardshop.kind.snapshot="$run/snapshot" -Dshardshop.kind.cluster=shardshop-test-eks -Dshardshop.kind.kubeconfig="$KUBECONFIG" -Dshardshop.kind.tags=eks-pitr-drill
```

### Remove the database tier

CAUTION: Make a copy of the data that you want to keep before you do this step.
This step deletes all the databases, the broker data and their EBS volumes.

The clusters have the Helm keep policy, thus the namespace deletion removes them.
The EBS CSI driver then deletes each volume. There must be no persistent volume
after the wait. The backups stay in the bucket until the destroy. A new installation
in the same foundation needs empty prefixes, because the plugin refuses to archive
into the WAL archive of an earlier cluster with the same name. Empty the bucket with
`aws s3 rm "s3://$bucket/" --recursive` before you install the tier again:

```bash
helm uninstall shardshop-migrations shardshop-infrastructure --kube-context kind-shardshop-test-eks --namespace shardshop --wait
kubectl --context kind-shardshop-test-eks delete namespace shardshop --timeout=10m
kubectl --context kind-shardshop-test-eks wait --for=delete persistentvolume --all --timeout=5m
helm uninstall barman-cloud cnpg --kube-context kind-shardshop-test-eks --namespace cnpg-system --wait
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
