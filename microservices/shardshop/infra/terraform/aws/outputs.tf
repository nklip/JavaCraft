output "region" {
  value = var.region
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "availability_zones" {
  value = local.azs
}

output "ecr_repository_urls" {
  value = { for name, repository in aws_ecr_repository.images : name => repository.repository_url }
}

output "chart_repository_urls" {
  value = { for name, repository in aws_ecr_repository.charts : name => repository.repository_url }
}

output "backup_bucket" {
  value = aws_s3_bucket.backups.bucket
}

output "backup_role_arns" {
  value = { for name, role in aws_iam_role.backup : name => role.arn }
}
