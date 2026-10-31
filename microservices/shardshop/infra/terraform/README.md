# AWS foundation

[ShardShop](../../README.md) › [Infrastructure](../README.md) › **AWS foundation** · [Plan step 7.1](../../PLAN.md#71-terraform-aws-foundation)

Terraform creates the AWS foundation of the optional milestone 7: a VPC, an EKS
cluster, ECR repositories, a backup bucket and the IAM roles of the workloads. The
local kind lab does not use these resources. The versions are in
[VERSIONS.md](../../VERSIONS.md#aws-foundation).

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
| Workers | One managed node group in each zone, because an EBS volume stays in its zone. Graviton `t4g.medium` with Amazon Linux 2023 arm64, because the images are linux/arm64. This size is enough for the system pods only, thus step 7.2 sizes the workers for the databases (`node_instance_type`). IMDSv2 with a hop limit of 1. |
| Add-ons | `vpc-cni`, `eks-pod-identity-agent`, `kube-proxy`, `coredns` and `aws-ebs-csi-driver`. Each one uses the newest add-on version for the cluster version at apply time. |
| Images | One ECR repository for each image in [`skaffold.yaml`](../../skaffold.yaml), with immutable tags and a scan on push. |
| Backups | Bucket `shardshop-backups-<account>-<region>` with SSE-S3 encryption, blocked public access and a TLS-only policy. |
| IAM | The worker role has only the node, CNI and ECR pull policies. EKS Pod Identity gives roles to the EBS CSI driver and to each CloudNativePG cluster of [`shards.yaml`](../shards.yaml) and `ledger-db`. Only this cluster can use these roles. A backup role can write only below its own prefix, for example `shard-a/`. |

`terraform destroy` removes everything that the modules create:

- ECR deletes the repositories with their images (`force_delete`).
- S3 deletes the buckets with their objects (`force_destroy`).
- The modules create no customer managed KMS key, because such a key stays for at
  least 7 days after its deletion. EKS encrypts the Kubernetes API data with an
  AWS owned key.

[`operator-policy.json`](operator-policy.json) gives the permissions for these
modules to the IAM user that runs Terraform:

- All names must start with `shardshop`, and the region must be `eu-west-2`.
- The user can attach only the AWS managed EKS, ECR pull and EBS CSI policies, and
  can pass the roles only to EKS, EC2 and EKS Pod Identity.
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
| Three `t4g.medium` workers | US$0.1128 for each hour (3 × US$0.0376) |
| Three 20 GB gp3 root volumes | US$0.0076 for each hour (US$0.0928 for each GB-month) |
| Total while the foundation runs | Approximately US$0.28 for each hour, or US$200 for each month |
| EKS audit and authenticator logs | US$0.5985 for each GB |

S3, ECR and the S3 gateway endpoint cost almost nothing without data. Destroy the
foundation after each use.

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

If kubectl fails with `TLS handshake timeout`, compare the current address with the
filter. A VPN or proxy can use a different exit address for each connection. Then
one `/32` block is not sufficient, and `api_allowed_cidrs` must contain all the exit
addresses.

### Check the foundation

Write the cluster credentials to a separate kubeconfig file, so that the kind
contexts stay unchanged:

```bash
aws eks update-kubeconfig --region eu-west-2 --name shardshop --alias shardshop-eks --kubeconfig ~/.kube/shardshop-eks
kubectl --kubeconfig ~/.kube/shardshop-eks --context shardshop-eks get nodes -L topology.kubernetes.io/zone -L kubernetes.io/arch
kubectl --kubeconfig ~/.kube/shardshop-eks --context shardshop-eks -n kube-system get pods
```

There must be three `Ready` arm64 nodes, one in each zone of the
`availability_zones` output, and all the `kube-system` pods must be `Running`.

### Destroy the foundation

CAUTION: Make a copy of the backups and the images that you want to keep before you
do this step. `terraform destroy` deletes all the backups in the backup bucket and
all the images in ECR.

Destroy the foundation first, then the state bucket:

```bash
terraform -chdir=microservices/shardshop/infra/terraform/aws destroy
terraform -chdir=microservices/shardshop/infra/terraform/state destroy
rm ~/.kube/shardshop-eks
```

Then make sure that no resource stays. Each command must show an empty list:

```bash
aws resourcegroupstaggingapi get-resources --region eu-west-2 --tag-filters Key=Project,Values=shardshop --query 'ResourceTagMappingList[].ResourceARN'
aws iam list-roles --query "Roles[?starts_with(RoleName, 'shardshop-')].RoleName"
aws logs describe-log-groups --region eu-west-2 --log-group-name-prefix /aws/eks/shardshop --query 'logGroups[].logGroupName'
```

The tag index can show a deleted resource for some time after the destroy. Query
each listed resource directly, for example with `aws ec2 describe-nat-gateways`. A
NAT gateway in the state `deleted` costs nothing.

The AWS service-linked roles of EKS and Auto Scaling stay in the account. AWS
creates them one time for each account, and they cost nothing.
