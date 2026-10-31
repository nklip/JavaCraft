provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "shardshop"
      Stack     = "aws"
      ManagedBy = "terraform"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

locals {
  name       = "shardshop"
  azs        = slice(sort(data.aws_availability_zones.available.names), 0, 3)
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  # Built from the cluster name, because the IAM roles of the add-ons must exist before the cluster.
  cluster_arn = "arn:${local.partition}:eks:${var.region}:${local.account_id}:cluster/${local.name}"

  # One ECR repository for each Skaffold image, and one backup role for each CNPG cluster
  # (the shards of infra/shards.yaml and the ledger).
  images            = [for artifact in yamldecode(file("${path.module}/../../../skaffold.yaml")).build.artifacts : basename(artifact.image)]
  database_clusters = concat([for shard in yamldecode(file("${path.module}/../../shards.yaml")).shards : shard.name], ["ledger-db"])
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "6.7.3"

  name = local.name
  cidr = var.vpc_cidr
  azs  = local.azs

  # In each zone: one /20 private subnet for the workers and one /24 public subnet for the NAT gateway.
  private_subnets = [for index, _ in local.azs : cidrsubnet(var.vpc_cidr, 4, index)]
  public_subnets  = [for index, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, 48 + index)]

  enable_nat_gateway     = true
  single_nat_gateway     = !var.nat_gateway_per_zone
  one_nat_gateway_per_az = var.nat_gateway_per_zone

  public_subnet_tags  = { "kubernetes.io/role/elb" = "1" }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = "1" }
}

# Backups and ECR image layers go to S3 through this free endpoint, not through the NAT gateway.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = module.vpc.private_route_table_ids
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "21.29.0"

  name               = local.name
  kubernetes_version = var.kubernetes_version
  # The cluster must not go into paid extended support.
  upgrade_policy = { support_type = "STANDARD" }

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  endpoint_public_access                   = true
  endpoint_public_access_cidrs             = var.api_allowed_cidrs
  authentication_mode                      = "API"
  enable_cluster_creator_admin_permissions = true

  # EKS encrypts the Kubernetes API data with an AWS owned key. A customer managed key
  # stays at least 7 days after terraform destroy, thus the stack creates none.
  create_kms_key    = false
  encryption_config = null
  # Workloads get AWS permissions through EKS Pod Identity, thus no IRSA OIDC provider.
  enable_irsa = false

  enabled_log_types                      = ["audit", "authenticator"]
  cloudwatch_log_group_retention_in_days = 7

  addons = {
    vpc-cni                = { before_compute = true }
    eks-pod-identity-agent = { before_compute = true }
    kube-proxy             = {}
    coredns                = {}
    aws-ebs-csi-driver = {
      pod_identity_association = [{
        role_arn        = aws_iam_role.ebs_csi.arn
        service_account = "ebs-csi-controller-sa"
      }]
    }
  }

  # One node group in each zone, because EBS volumes stay in their zone.
  eks_managed_node_groups = { for index, zone in local.azs : zone => {
    name                           = "${local.name}-${zone}"
    subnet_ids                     = [module.vpc.private_subnets[index]]
    ami_type                       = "AL2023_ARM_64_STANDARD"
    use_latest_ami_release_version = false
    instance_types                 = [var.node_instance_type]
    min_size                       = var.nodes_per_zone
    max_size                       = var.nodes_per_zone
    desired_size                   = var.nodes_per_zone
    create_iam_role                = false
    iam_role_arn                   = aws_iam_role.node.arn
  } }
}
