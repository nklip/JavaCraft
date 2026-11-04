# The public webpages: the AWS Load Balancer Controller makes an internal NLB for the Envoy Service
# of the Gateway, and the Terraform module ../webpages connects CloudFront to that NLB.

# The controller gets this role through EKS Pod Identity. The inline policy is the policy of the
# controller release (docs/install/iam_policy.json of v3.6.2, checksum in versions.lock.yaml). The
# operator user can attach only AWS managed EKS policies, thus the policy is inline.
resource "aws_iam_role" "load_balancer_controller" {
  name               = "${local.name}-load-balancer-controller"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_trust.json
}

resource "aws_iam_role_policy" "load_balancer_controller" {
  name   = "aws-load-balancer-controller"
  role   = aws_iam_role.load_balancer_controller.id
  policy = file("${path.module}/load-balancer-controller-policy.json")
}

resource "aws_eks_pod_identity_association" "load_balancer_controller" {
  cluster_name    = module.eks.cluster_name
  namespace       = "kube-system"
  service_account = "aws-load-balancer-controller"
  role_arn        = aws_iam_role.load_balancer_controller.arn
}

# CloudFront reaches a VPC origin from the addresses of this managed prefix list.
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# The security group of the NLB. A VPC origin needs an NLB with a security group. Only CloudFront
# can open a connection to it, also from inside the VPC.
resource "aws_security_group" "webpages" {
  name        = "${local.name}-webpages"
  description = "ShardShop webpages NLB: HTTP from CloudFront only"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "webpages_cloudfront" {
  security_group_id = aws_security_group.webpages.id
  description       = "HTTP from the CloudFront origin-facing servers"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
}

# The NLB sends the traffic and the health checks to the Envoy pods in the VPC.
resource "aws_vpc_security_group_egress_rule" "webpages_targets" {
  security_group_id = aws_security_group.webpages.id
  description       = "Traffic and health checks to the Envoy pods"
  ip_protocol       = "tcp"
  from_port         = 0
  to_port           = 65535
  cidr_ipv4         = var.vpc_cidr
}
