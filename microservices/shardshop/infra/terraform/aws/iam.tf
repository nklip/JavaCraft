# Workers: only the node, CNI and ECR pull policies. The launch template keeps the
# IMDS hop limit at 1, thus pods without host networking cannot use this role.
data "aws_iam_policy_document" "node_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name               = "${local.name}-node"
  assume_role_policy = data.aws_iam_policy_document.node_trust.json
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset(["AmazonEKSWorkerNodePolicy", "AmazonEKS_CNI_Policy", "AmazonEC2ContainerRegistryPullOnly"])

  role       = aws_iam_role.node.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/${each.value}"
}

# EKS Pod Identity: only this cluster can give the roles below to its service accounts.
data "aws_iam_policy_document" "pod_identity_trust" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [local.cluster_arn]
    }
  }
}

resource "aws_iam_role" "ebs_csi" {
  name               = "${local.name}-ebs-csi"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_trust.json
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.ebs_csi.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

# One backup role for each CNPG cluster. CNPG gives the Cluster name to the service
# account of its instance pods. Each role can write only below its own prefix.
resource "aws_iam_role" "backup" {
  for_each = toset(local.database_clusters)

  name               = "${local.name}-backup-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_trust.json
}

data "aws_iam_policy_document" "backup" {
  for_each = toset(local.database_clusters)

  # Barman Cloud checks the bucket with HeadBucket, which needs s3:ListBucket without a prefix.
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.backups.arn]
  }

  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.backups.arn}/${each.key}/*"]
  }
}

resource "aws_iam_role_policy" "backup" {
  for_each = toset(local.database_clusters)

  name   = "backup-prefix"
  role   = aws_iam_role.backup[each.key].id
  policy = data.aws_iam_policy_document.backup[each.key].json
}

resource "aws_eks_pod_identity_association" "backup" {
  for_each = toset(local.database_clusters)

  cluster_name    = module.eks.cluster_name
  namespace       = "shardshop"
  service_account = each.key
  role_arn        = aws_iam_role.backup[each.key].arn
}

# A point-in-time restore creates the new cluster <cluster>-pitr. Its pods can only read the
# backups of the source cluster.
resource "aws_iam_role" "restore" {
  for_each = toset(local.database_clusters)

  name               = "${local.name}-restore-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_trust.json
}

data "aws_iam_policy_document" "restore" {
  for_each = toset(local.database_clusters)

  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.backups.arn]
  }

  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.backups.arn}/${each.key}/*"]
  }
}

resource "aws_iam_role_policy" "restore" {
  for_each = toset(local.database_clusters)

  name   = "restore-prefix"
  role   = aws_iam_role.restore[each.key].id
  policy = data.aws_iam_policy_document.restore[each.key].json
}

resource "aws_eks_pod_identity_association" "restore" {
  for_each = toset(local.database_clusters)

  cluster_name    = module.eks.cluster_name
  namespace       = "shardshop"
  service_account = "${each.key}-pitr"
  role_arn        = aws_iam_role.restore[each.key].arn
}

# The holder pod of the shared chart mounts the runtime secrets. The Secrets Store CSI
# provider reads them with this role.
resource "aws_iam_role" "runtime_secrets" {
  name               = "${local.name}-runtime-secrets"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_trust.json
}

data "aws_iam_policy_document" "runtime_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = concat([for secret in aws_secretsmanager_secret.database : secret.arn], [aws_secretsmanager_secret.broker.arn])
  }
}

resource "aws_iam_role_policy" "runtime_secrets" {
  name   = "read-runtime-secrets"
  role   = aws_iam_role.runtime_secrets.id
  policy = data.aws_iam_policy_document.runtime_secrets.json
}

resource "aws_eks_pod_identity_association" "runtime_secrets" {
  cluster_name    = module.eks.cluster_name
  namespace       = "shardshop"
  service_account = "shardshop-runtime-secrets"
  role_arn        = aws_iam_role.runtime_secrets.arn
}
