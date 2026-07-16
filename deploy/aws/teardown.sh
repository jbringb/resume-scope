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

# `cloudformation delete-stack` on a name that doesn't exist succeeds silently and deletes
# nothing — a wrong STACK_NAME would otherwise look like a successful teardown while RDS keeps
# billing. Fail loud instead, before the (irreversible) delete-stack call below.
if [ "${FULL}" = 1 ]; then
  if ! aws cloudformation describe-stacks --stack-name "${STACK}" --region "${REGION}" \
      --query 'Stacks[0].StackStatus' --output text >/dev/null 2>&1; then
    echo "ERROR: no CloudFormation stack named '${STACK}' in region ${REGION} — nothing would be deleted." >&2
    echo "Stacks that do exist in this account/region:" >&2
    aws cloudformation describe-stacks --region "${REGION}" \
      --query 'Stacks[].StackName' --output text >&2 || true
    echo "Re-run with the correct name: STACK_NAME=<name> $0 --full" >&2
    exit 1
  fi
fi

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
      # Distinguish "genuinely already gone" (ParameterNotFound) from any other failure — a
      # swallowed real error (throttling, a stale session, ...) must not be reported as success.
      err="$(aws ssm delete-parameter --name "/resume-scope/${p}" --region "${REGION}" --no-cli-pager 2>&1 >/dev/null || true)"
      if [ -z "${err}" ]; then
        echo "deleted SSM /resume-scope/${p}"
      elif echo "${err}" | grep -q "ParameterNotFound"; then
        echo "(SSM /resume-scope/${p} already gone)"
      else
        echo "WARNING: failed to delete SSM /resume-scope/${p}: ${err}" >&2
      fi
    done
    aws cloudformation delete-stack --stack-name "${STACK}" --region "${REGION}"
    echo "Stack deletion requested. Watch it with:"
    echo "  aws cloudformation describe-stacks --stack-name ${STACK} --region ${REGION} --query 'Stacks[0].StackStatus'"
  fi
fi

echo
echo "Done."
