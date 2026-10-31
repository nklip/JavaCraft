resource "aws_ecr_repository" "images" {
  for_each = toset(local.images)

  name                 = each.key
  image_tag_mutability = "IMMUTABLE"
  # terraform destroy also deletes the pushed images.
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_s3_bucket" "backups" {
  bucket = "${local.name}-backups-${local.account_id}-${var.region}"
  # terraform destroy also deletes all backups.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "backups" {
  bucket = aws_s3_bucket.backups.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "backups_tls_only" {
  statement {
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.backups.arn, "${aws_s3_bucket.backups.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "backups" {
  bucket = aws_s3_bucket.backups.id
  policy = data.aws_iam_policy_document.backups_tls_only.json

  depends_on = [aws_s3_bucket_public_access_block.backups]
}
