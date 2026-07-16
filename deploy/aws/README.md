# Deploy on AWS (ECS Express Mode)

ECS Express Mode provisions a Fargate service + internet-facing ALB with HTTPS at `https://<service-name>.ecs.<region>.on.aws`. The [Dockerfile](../../Dockerfile) is self-building — no local build step.

All AWS infrastructure (OIDC deploy role, the two ECS roles, ECR, and RDS) is one CloudFormation stack: [`infra.yaml`](infra.yaml). Deploys push to ECR and roll the service via GitHub Actions ([`deploy-aws.yml`](../../.github/workflows/deploy-aws.yml)), triggered manually (`workflow_dispatch`).

---

## 1 · Create the ECS service-linked role

This is a one-time, account-level step. CloudFormation cannot create it, and the deploy will fail without it:

```bash
aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com
```

Skip this if your account has already used ECS.

## 2 · Create the infrastructure stack

Deploy `infra.yaml` once, in the region you want. Either via the console (**CloudFormation → Create stack → upload `infra.yaml`**) or the CLI:

```bash
aws cloudformation deploy \
  --stack-name resume-scope \
  --region <REGION> \
  --template-file deploy/aws/infra.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
    VpcId=<DEFAULT_VPC_ID> \
    SubnetIds=<SUBNET_A>,<SUBNET_B> \
    DbPassword='<STRONG_DB_PASSWORD>'
```

Parameters:
- **VpcId / SubnetIds** — your default VPC and two of its subnets (different AZs).
- **VpcCidr** — defaults to `172.31.0.0/16` (the default-VPC range); change only if yours differs.
- **DbPassword** — RDS master password. Must match the SSM parameter you create in the next step.
- **CreateOidcProvider** — defaults to `Yes`. Set to `No` if your account already has a GitHub OIDC provider (an account allows only one).

The stack creates `db.t4g.micro` Postgres (free-tier eligible), the IAM roles, and the ECR repo. RDS takes ~5 min.

## 3 · Create SSM SecureString parameters

Runtime secrets are stored as SSM SecureString parameters. CloudFormation cannot create SecureString parameters, so create them once via CLI:

```bash
aws ssm put-parameter --region <REGION> --name "/resume-scope/openai_api_key" --value "<OPENAI_API_KEY>" --type SecureString
aws ssm put-parameter --region <REGION> --name "/resume-scope/api_key"        --value "<LONG_RANDOM_SECRET>" --type SecureString
aws ssm put-parameter --region <REGION> --name "/resume-scope/db_password"    --value "<STRONG_DB_PASSWORD>" --type SecureString
```

`api_key` is the shared secret clients send as `X-API-Key` on `/api/**`. `db_password` must match the `DbPassword` used in step 2.

## 4 · Wire GitHub

Create a GitHub **Environment** named `aws` (Settings → Environments), then add the following from the stack **Outputs** and your own values:

| Name | Kind | Value |
|------|------|-------|
| `AWS_DEPLOY_ROLE_ARN` | secret | stack output `DeployRoleArn` |
| `ECS_EXECUTION_ROLE_ARN` | secret | stack output `ExecutionRoleArn` |
| `ECS_INFRASTRUCTURE_ROLE_ARN` | secret | stack output `InfrastructureRoleArn` |
| `AWS_REGION` | variable | the region you deployed into |
| `AWS_ACCOUNT_ID` | variable | your 12-digit AWS account ID |
| `RDS_ENDPOINT` | variable | stack output `RdsEndpoint` |

## 5 · Deploy

Actions → **Deploy · AWS (ECS Express)** → **Run workflow**. The first run creates the service + ALB and runs Flyway on startup; later runs just roll the image.

Public URL is `https://resume-scope.ecs.<REGION>.on.aws`:

```bash
curl https://resume-scope.ecs.<REGION>.on.aws/health
curl -H "X-API-Key: <API_KEY>" https://resume-scope.ecs.<REGION>.on.aws/api/job-roles
```

---

## Teardown

The Fargate task and the Express ALB are billed continuously while the service is up — but so is the
RDS instance, independent of whether the service exists. Deleting just the service (the plain form
below) stops the Fargate/ALB cost but **leaves RDS running and billing** (confirmed: ~$0.20/day for an
idle `db.t4g.micro` outside the free tier). Use `--full` for an actual "stop paying" teardown.
[`teardown.sh`](teardown.sh):

```bash
./deploy/aws/teardown.sh          # delete the ECS Express service (Fargate + ALB) — keeps RDS/ECR/IAM/SSM billing
./deploy/aws/teardown.sh --full   # also delete the CloudFormation stack (RDS + its data, ECR, IAM) and SSM params → ~$0
./deploy/aws/teardown.sh -y       # skip the confirmation prompts
```

`STACK_NAME` must match the `--stack-name` you actually used in step 2 above (the script's default is
`resume-scope`). A mismatched name makes `--full` silently delete nothing — `aws cloudformation
delete-stack` on a name that doesn't exist is not an error. The script now checks this upfront and
fails loudly instead: `STACK_NAME=<your-actual-name> ./deploy/aws/teardown.sh --full`.

Redeploy by re-running the **Deploy · AWS (ECS Express)** workflow. After `--full`, first recreate the service-linked role (step 1) and the SSM parameters (step 3).

The equivalent raw commands, if you'd rather run them by hand:

```bash
aws ecs delete-express-gateway-service \
  --service-arn arn:aws:ecs:<REGION>:<ACCOUNT_ID>:service/default/resume-scope --region <REGION>
# full teardown:
aws cloudformation delete-stack --stack-name resume-scope --region <REGION>
aws ssm delete-parameter --region <REGION> --name "/resume-scope/openai_api_key"
aws ssm delete-parameter --region <REGION> --name "/resume-scope/api_key"
aws ssm delete-parameter --region <REGION> --name "/resume-scope/db_password"
```

## Cost guardrail

Set a monthly AWS **cost budget** that emails you as spend approaches a cap. AWS budgets **alert**, they don't hard-stop services — there's no native switch that pauses billing at a number, so treat the alert as your cue to run `teardown.sh`. (A true auto-stop needs a Budget *Action* that applies a deny policy — more invasive, not set up here.)

```bash
aws budgets create-budget --account-id <ACCOUNT_ID> \
  --budget '{"BudgetName":"resume-scope-monthly-cap","BudgetLimit":{"Amount":"10","Unit":"USD"},"TimeUnit":"MONTHLY","BudgetType":"COST"}' \
  --notifications-with-subscribers '[{"Notification":{"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":100,"ThresholdType":"PERCENTAGE"},"Subscribers":[{"SubscriptionType":"EMAIL","Address":"you@example.com"}]}]'
```

> A ready-to-run helper lives at `deploy/aws/local/set-budget.sh` (gitignored — it's account-specific): `BUDGET_EMAIL=you@example.com ./deploy/aws/local/set-budget.sh`.
