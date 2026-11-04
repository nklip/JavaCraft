variable "region" {
  description = "AWS region of the foundation in ../aws. It must be the region of the S3 backend in versions.tf."
  type        = string
  default     = "eu-west-2"
}
