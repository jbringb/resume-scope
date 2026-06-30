#!/usr/bin/env bash
#
# Tear down the ResumeScope AWS deployment to stop billing.
#
#   ./teardown.sh          delete the ECS Express service only — stops the two things that cost
#                          money continuously: the Fargate task and the Express ALB. Keeps RDS
#                          (free-tier), ECR, IAM roles and SSM params so a redeploy is one click.
#   ./teardown.sh --full   ALSO delete the CloudFormation stack (RDS incl. its data, ECR images,
#                          IAM roles) and the SSM parameters → account goes back to ~$0.
#   ./teardown.sh -y       skip the confirmation prompts (combine with --full if you like).
#
# Redeploy later: re-run the GitHub Actions "Deploy · AWS (ECS Express)" workflow. After --full you
# must first recreate the service-linked role + SSM params (see deploy/aws/README.md steps 1 & 3).
#
# Requires: an authenticated AWS CLI session with rights to delete the service / stack.
# Override targets via env: AWS_REGION, ECS_CLUSTER, ECS_SERVICE, STACK_NAME.
set -euo pipefail

REGION="${AWS_REGION:-us-east-1}"
CLUSTER="${ECS_CLUSTER:-default}"
SERVICE="${ECS_SERVICE:-resume-scope}"
STACK="${STACK_NAME:-resume-scope}"

FULL=0
ASSUME_YES=0
for arg in "$@"; do
  case "$arg" in
    --full)   FULL=1 ;;
    -y|--yes) ASSUME_YES=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

confirm() {
  [ "$ASSUME_YES" = 1 ] && return 0
  read -r -p "$1 [y/N] " ans
  [ "$ans" = y ] || [ "$ans" = Y ]
}

account="$(aws sts get-caller-identity --query Account --output text)"
service_arn="arn:aws:ecs:${REGION}:${account}:service/${CLUSTER}/${SERVICE}"
echo "Account ${account} · region ${REGION}"

echo
echo "Deleting the ECS Express service '${SERVICE}' removes its Fargate tasks AND the Express ALB"
echo "(both billed continuously). RDS/ECR/IAM/SSM are left in place for a quick redeploy."
if confirm "Delete the running service now?"; then
  aws ecs delete-express-gateway-service \
    --service-arn "${service_arn}" \
    --region "${REGION}" \
    --no-cli-pager \
    && echo "Service deletion requested (it drains, then infrastructure is removed)." \
    || echo "(service not found — already torn down?)"
fi

if [ "${FULL}" = 1 ]; then
  echo
  echo "FULL teardown — this PERMANENTLY deletes RDS (and its data), ECR images, and all IAM"
  echo "roles in CloudFormation stack '${STACK}', plus the /resume-scope/* SSM parameters."
  if confirm "Proceed with full teardown?"; then
    for p in openai_api_key api_key db_password; do
      if aws ssm delete-parameter --name "/resume-scope/${p}" --region "${REGION}" --no-cli-pager 2>/dev/null; then
        echo "deleted SSM /resume-scope/${p}"
      else
        echo "(SSM /resume-scope/${p} already gone)"
      fi
    done
    aws cloudformation delete-stack --stack-name "${STACK}" --region "${REGION}"
    echo "Stack deletion requested. Watch it with:"
    echo "  aws cloudformation describe-stacks --stack-name ${STACK} --region ${REGION} --query 'Stacks[0].StackStatus'"
  fi
fi

echo
echo "Done."
