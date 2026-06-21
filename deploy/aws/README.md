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

Delete the stack to remove everything (it also empties and removes the ECR repo and the RDS instance):

```bash
aws cloudformation delete-stack --stack-name resume-scope --region <REGION>
```

To also remove the SSM parameters:

```bash
aws ssm delete-parameter --region <REGION> --name "/resume-scope/openai_api_key"
aws ssm delete-parameter --region <REGION> --name "/resume-scope/api_key"
aws ssm delete-parameter --region <REGION> --name "/resume-scope/db_password"
```
