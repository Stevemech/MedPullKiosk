#!/usr/bin/env bash
set -euo pipefail

#
# deploy.sh — Build, push, and deploy the MedPull server to AWS ECS.
#
# Prerequisites:
#   - AWS CLI configured with appropriate credentials
#   - Docker installed and running
#   - CloudFormation stack already created (see cloudformation.yaml)
#
# Environment variables (auto-detected if not set):
#   AWS_REGION       — AWS region (default: us-east-1)
#   AWS_ACCOUNT_ID   — AWS account ID (auto-detected from STS)
#   ECR_REPO_URI     — ECR repository URI (auto-detected from CloudFormation output)
#   STACK_NAME       — CloudFormation stack name (default: medpull)
#

STACK_NAME="${STACK_NAME:-medpull}"
AWS_REGION="${AWS_REGION:-us-east-1}"

if [ -z "${AWS_ACCOUNT_ID:-}" ]; then
    echo "Detecting AWS account ID..."
    AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
fi

echo "Account: ${AWS_ACCOUNT_ID}"
echo "Region:  ${AWS_REGION}"
echo "Stack:   ${STACK_NAME}"

if [ -z "${ECR_REPO_URI:-}" ]; then
    echo "Fetching ECR repo URI from CloudFormation..."
    ECR_REPO_URI=$(aws cloudformation describe-stacks \
        --stack-name "${STACK_NAME}" \
        --query "Stacks[0].Outputs[?OutputKey=='EcrRepoUri'].OutputValue" \
        --output text \
        --region "${AWS_REGION}")
fi

IMAGE_TAG="${IMAGE_TAG:-latest}"
FULL_IMAGE="${ECR_REPO_URI}:${IMAGE_TAG}"

echo "Image:   ${FULL_IMAGE}"
echo ""

# 1. Authenticate Docker to ECR
echo "==> Logging into ECR..."
aws ecr get-login-password --region "${AWS_REGION}" \
    | docker login --username AWS --password-stdin \
    "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

# 2. Build the Docker image
echo "==> Building Docker image..."
docker build -t medpull-server ../

# 3. Tag and push
echo "==> Tagging and pushing..."
docker tag medpull-server "${FULL_IMAGE}"
docker push "${FULL_IMAGE}"

# 4. Update ECS service to force new deployment
echo "==> Updating ECS service..."
aws ecs update-service \
    --cluster medpull \
    --service medpull-server \
    --force-new-deployment \
    --region "${AWS_REGION}" \
    > /dev/null

# 5. Wait for deployment to stabilize
echo "==> Waiting for deployment to stabilize (this may take a few minutes)..."
aws ecs wait services-stable \
    --cluster medpull \
    --services medpull-server \
    --region "${AWS_REGION}"

# 6. Print the ALB URL
ALB_DNS=$(aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" \
    --output text \
    --region "${AWS_REGION}")

echo ""
echo "Deployment complete!"
echo "API URL: https://${ALB_DNS}"
echo "Health:  https://${ALB_DNS}/health"
