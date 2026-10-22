import * as path from 'path';
import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as apigw from 'aws-cdk-lib/aws-apigatewayv2';
import { HttpUserPoolAuthorizer } from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import { HttpAlbIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cwActions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as scheduler from 'aws-cdk-lib/aws-scheduler';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { BUS_NAME, PROGRESSION_SCHEDULE, QUEUES, REGION, SERVICES, ServiceDef } from './config';

export interface LicsysStackProps extends StackProps {
  /** Container image tag deployed for every service (ECR repo `licsys/<service>`). */
  readonly imageTag?: string;
  /** Desired task count per service. */
  readonly desiredCount?: number;
}

interface DeployedService {
  readonly def: ServiceDef;
  readonly service: ecs.FargateService;
  readonly targetGroup: elbv2.ApplicationTargetGroup;
}

export class LicsysStack extends Stack {
  constructor(scope: Construct, id: string, props: LicsysStackProps = {}) {
    super(scope, id, props);
    const imageTag = props.imageTag ?? 'latest';
    const desiredCount = props.desiredCount ?? 2;

    // ---------- Network ----------
    const vpc = new ec2.Vpc(this, 'Vpc', {
      availabilityZones: [`${REGION}a`, `${REGION}b`],
      natGateways: 2,
      subnetConfiguration: [
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: 'app', subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS, cidrMask: 22 },
      ],
      gatewayEndpoints: { DynamoDb: { service: ec2.GatewayVpcEndpointAwsService.DYNAMODB } },
    });

    // ---------- Alarm topic ----------
    const alarmTopic = new sns.Topic(this, 'AlarmTopic', { topicName: 'licsys-alarms', enforceSSL: true });
    const alarmAction = new cwActions.SnsAction(alarmTopic);

    // ---------- Event bus, queues, rules ----------
    const bus = new events.EventBus(this, 'Bus', { eventBusName: BUS_NAME });
    bus.archive('BusArchive', {
      archiveName: 'licsys-bus-archive',
      eventPattern: { source: events.Match.prefix('licsys.') },
      retention: Duration.days(30),
    });

    const deliveryDlq = new sqs.Queue(this, 'BusDeliveryDlq', {
      queueName: 'licsys-bus-delivery-dlq',
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
    });

    const queues: Record<string, sqs.Queue> = {};
    const dlqs: Record<string, sqs.Queue> = {};
    for (const q of QUEUES) {
      const dlq = new sqs.Queue(this, `${q.name}-dlq`, {
        queueName: `${q.name}-dlq`,
        retentionPeriod: Duration.days(14),
        encryption: sqs.QueueEncryption.SQS_MANAGED,
        enforceSSL: true,
      });
      const queue = new sqs.Queue(this, q.name, {
        queueName: q.name,
        visibilityTimeout: Duration.seconds(60),
        retentionPeriod: Duration.days(4),
        encryption: sqs.QueueEncryption.SQS_MANAGED,
        enforceSSL: true,
        deadLetterQueue: { queue: dlq, maxReceiveCount: 5 },
      });
      const eventPattern: events.EventPattern = q.pattern.detailType
        ? { detailType: q.pattern.detailType }
        : { source: events.Match.prefix(q.pattern.sourcePrefix!) };
      new events.Rule(this, `to-${q.name}`, {
        ruleName: `to-${q.name}`,
        description: q.description,
        eventBus: bus,
        eventPattern,
        targets: [new targets.SqsQueue(queue, { deadLetterQueue: deliveryDlq, retryAttempts: 10, maxEventAge: Duration.hours(24) })],
      });
      queues[q.name] = queue;
      dlqs[q.name] = dlq;
    }

    // ---------- Tables (one per bounded context) ----------
    const tables: Record<string, dynamodb.Table> = {};
    for (const svc of SERVICES) {
      tables[svc.name] = new dynamodb.Table(this, `${svc.id}Table`, {
        tableName: svc.table.name,
        partitionKey: { name: svc.table.partitionKey, type: dynamodb.AttributeType.STRING },
        billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
        pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
        encryption: dynamodb.TableEncryption.AWS_MANAGED,
        removalPolicy: RemovalPolicy.RETAIN,
      });
    }

    // ---------- Internal ALB ----------
    const albSg = new ec2.SecurityGroup(this, 'AlbSg', { vpc, description: 'LICSYS internal ALB', allowAllOutbound: false });
    const alb = new elbv2.ApplicationLoadBalancer(this, 'Alb', {
      vpc,
      internetFacing: false,
      securityGroup: albSg,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      dropInvalidHeaderFields: true,
    });
    const listener = alb.addListener('Http', {
      port: 80,
      open: false,
      defaultAction: elbv2.ListenerAction.fixedResponse(404, { contentType: 'application/json', messageBody: '{"error":"not found"}' }),
    });

    // ---------- ECS ----------
    const cluster = new ecs.Cluster(this, 'Cluster', {
      vpc,
      clusterName: 'licsys',
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
    });

    const deployed: DeployedService[] = SERVICES.map((def) => {
      const repo = new ecr.Repository(this, `${def.id}Repo`, {
        repositoryName: `licsys/${def.name}`,
        imageScanOnPush: true,
        imageTagMutability: ecr.TagMutability.IMMUTABLE,
        removalPolicy: RemovalPolicy.RETAIN,
      });

      const taskRole = new iam.Role(this, `${def.id}TaskRole`, {
        assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
        description: `${def.name} task role (least privilege)`,
      });
      // Own table only.
      tables[def.name].grantReadWriteData(taskRole);
      // Publish to licsys-bus only with this service's own source.
      taskRole.addToPolicy(new iam.PolicyStatement({
        actions: ['events:PutEvents'],
        resources: [bus.eventBusArn],
        conditions: { StringEquals: { 'events:source': def.eventSource } },
      }));
      // Consume own inbox queues only.
      for (const qName of def.consumes) queues[qName].grantConsumeMessages(taskRole);

      const taskDef = new ecs.FargateTaskDefinition(this, `${def.id}TaskDef`, {
        family: def.name,
        cpu: 512,
        memoryLimitMiB: 1024,
        taskRole,
        runtimePlatform: { cpuArchitecture: ecs.CpuArchitecture.X86_64, operatingSystemFamily: ecs.OperatingSystemFamily.LINUX },
      });
      const environment: Record<string, string> = {
        SERVER_PORT: String(def.port),
        LICSYS_TABLE: def.table.name,
        LICSYS_EVENT_BUS: BUS_NAME,
        LICSYS_EVENT_SOURCE: def.eventSource,
        SPRING_PROFILES_ACTIVE: 'aws',
      };
      for (const qName of def.consumes) {
        environment[`LICSYS_QUEUE_${qName.replace(/-/g, '_').toUpperCase()}`] = queues[qName].queueUrl;
      }
      taskDef.addContainer('app', {
        containerName: def.name,
        image: ecs.ContainerImage.fromEcrRepository(repo, imageTag),
        portMappings: [{ containerPort: def.port }],
        environment,
        logging: ecs.LogDrivers.awsLogs({
          streamPrefix: def.name,
          logGroup: new logs.LogGroup(this, `${def.id}Logs`, {
            logGroupName: `/licsys/${def.name}`,
            retention: logs.RetentionDays.THREE_MONTHS,
            removalPolicy: RemovalPolicy.RETAIN,
          }),
        }),
        healthCheck: {
          command: ['CMD-SHELL', `curl -fs http://localhost:${def.port}/actuator/health || exit 1`],
          interval: Duration.seconds(30),
          startPeriod: Duration.seconds(60),
        },
      });

      const svcSg = new ec2.SecurityGroup(this, `${def.id}Sg`, { vpc, description: `${def.name} tasks` });
      const service = new ecs.FargateService(this, `${def.id}Service`, {
        cluster,
        serviceName: def.name,
        taskDefinition: taskDef,
        desiredCount,
        securityGroups: [svcSg],
        vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
        circuitBreaker: { rollback: true },
        minHealthyPercent: 100,
        maxHealthyPercent: 200,
      });
      const scaling = service.autoScaleTaskCount({ minCapacity: desiredCount, maxCapacity: 6 });
      scaling.scaleOnCpuUtilization('Cpu', { targetUtilizationPercent: 60 });

      let targetGroup: elbv2.ApplicationTargetGroup | undefined;
      def.paths.forEach((p, i) => {
        if (!targetGroup) {
          targetGroup = listener.addTargets(`${def.id}Targets`, {
            priority: p.priority,
            conditions: [elbv2.ListenerCondition.pathPatterns(p.patterns)],
            port: def.port,
            protocol: elbv2.ApplicationProtocol.HTTP,
            targets: [service],
            healthCheck: { path: '/actuator/health', healthyHttpCodes: '200' },
            deregistrationDelay: Duration.seconds(30),
          });
        } else {
          listener.addTargetGroups(`${def.id}Targets${i}`, {
            priority: p.priority,
            conditions: [elbv2.ListenerCondition.pathPatterns(p.patterns)],
            targetGroups: [targetGroup],
          });
        }
      });
      new CfnOutput(this, `${def.id}RepoUri`, { value: repo.repositoryUri });
      return { def, service, targetGroup: targetGroup! };
    });

    // ---------- Public edge: HTTP API + VPC link + Cognito ----------
    const userPool = new cognito.UserPool(this, 'UserPool', {
      userPoolName: 'licsys-staff',
      selfSignUpEnabled: false,
      signInAliases: { email: true },
      mfa: cognito.Mfa.REQUIRED,
      mfaSecondFactor: { otp: true, sms: false },
      passwordPolicy: { minLength: 12, requireDigits: true, requireLowercase: true, requireUppercase: true, requireSymbols: true },
      accountRecovery: cognito.AccountRecovery.EMAIL_ONLY,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const uiClient = userPool.addClient('UiClient', {
      userPoolClientName: 'licsys-next-ui',
      generateSecret: false,
      authFlows: { userSrp: true },
      oAuth: { flows: { authorizationCodeGrant: true }, scopes: [cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL] },
    });

    const vpcLinkSg = new ec2.SecurityGroup(this, 'VpcLinkSg', { vpc, description: 'API Gateway VPC link' });
    const vpcLink = new apigw.VpcLink(this, 'VpcLink', {
      vpc,
      vpcLinkName: 'licsys-vpc-link',
      subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [vpcLinkSg],
    });
    albSg.addIngressRule(vpcLinkSg, ec2.Port.tcp(80), 'API Gateway VPC link');

    const authorizer = new HttpUserPoolAuthorizer('CognitoAuthorizer', userPool, { userPoolClients: [uiClient] });
    const httpApi = new apigw.HttpApi(this, 'HttpApi', {
      apiName: 'licsys-next',
      corsPreflight: {
        allowOrigins: ['https://licsys-next.example.invalid'],
        allowMethods: [apigw.CorsHttpMethod.ANY],
        allowHeaders: ['authorization', 'content-type'],
      },
    });
    const albIntegration = new HttpAlbIntegration('AlbIntegration', listener, { vpcLink });
    // POST /api/admin/reset is a demo-only endpoint and is deliberately not routed.
    for (const routePath of ['/api/drivers', '/api/drivers/{proxy+}', '/api/progression/{proxy+}', '/api/offences', '/api/offences/{proxy+}',
      '/api/renewals', '/api/renewals/{proxy+}', '/api/notices', '/api/notices/{proxy+}', '/api/activity', '/api/activity/{proxy+}']) {
      httpApi.addRoutes({ path: routePath, methods: [apigw.HttpMethod.GET, apigw.HttpMethod.POST], integration: albIntegration, authorizer });
    }

    // ---------- Nightly progression (replaces LICNIGHT STEP020 / LICUPG) ----------
    const triggerSg = new ec2.SecurityGroup(this, 'ProgressionTriggerSg', { vpc, description: 'progression trigger lambda' });
    albSg.addIngressRule(triggerSg, ec2.Port.tcp(80), 'nightly progression trigger');
    for (const d of deployed) {
      d.service.connections.allowFrom(albSg, ec2.Port.tcp(d.def.port), 'ALB to service');
    }
    // ALB SG has allowAllOutbound=false, so allow egress to each service explicitly.
    for (const d of deployed) {
      albSg.connections.allowTo(d.service.connections, ec2.Port.tcp(d.def.port), `ALB to ${d.def.name}`);
    }

    const triggerFn = new lambda.Function(this, 'ProgressionTrigger', {
      functionName: 'licsys-progression-trigger',
      description: 'Calls licence-service POST /api/progression/run (replaces LICNIGHT STEP020 LICUPG)',
      runtime: lambda.Runtime.NODEJS_20_X,
      handler: 'index.handler',
      code: lambda.Code.fromAsset(path.join(__dirname, '..', 'lambda', 'progression-trigger')),
      timeout: Duration.minutes(5),
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [triggerSg],
      environment: {
        BASE_URL: `http://${alb.loadBalancerDnsName}`,
        RUN_PATH: PROGRESSION_SCHEDULE.path,
        RUN_TIMEZONE: PROGRESSION_SCHEDULE.timezone,
      },
      logGroup: new logs.LogGroup(this, 'ProgressionTriggerLogs', {
        logGroupName: '/licsys/progression-trigger',
        retention: logs.RetentionDays.ONE_YEAR,
        removalPolicy: RemovalPolicy.RETAIN,
      }),
    });

    const scheduleDlq = new sqs.Queue(this, 'ScheduleDlq', {
      queueName: 'licsys-progression-schedule-dlq',
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
    });
    const schedulerRole = new iam.Role(this, 'SchedulerRole', {
      assumedBy: new iam.ServicePrincipal('scheduler.amazonaws.com', {
        conditions: { StringEquals: { 'aws:SourceAccount': this.account } },
      }),
      description: 'EventBridge Scheduler: invoke progression trigger only',
    });
    triggerFn.grantInvoke(schedulerRole);
    scheduleDlq.grantSendMessages(schedulerRole);

    new scheduler.CfnSchedule(this, 'NightlyProgression', {
      name: 'licsys-nightly-progression',
      description: 'Nightly licence progression sweep (was jcl/LICNIGHT.jcl STEP020 LICUPG)',
      scheduleExpression: PROGRESSION_SCHEDULE.expression,
      scheduleExpressionTimezone: PROGRESSION_SCHEDULE.timezone,
      flexibleTimeWindow: { mode: 'OFF' },
      target: {
        arn: triggerFn.functionArn,
        roleArn: schedulerRole.roleArn,
        input: JSON.stringify({ source: 'licsys-nightly-progression' }),
        retryPolicy: { maximumRetryAttempts: 3, maximumEventAgeInSeconds: 3600 },
        deadLetterConfig: { arn: scheduleDlq.queueArn },
      },
    });

    // ---------- Observability ----------
    const dashboard = new cloudwatch.Dashboard(this, 'Dashboard', { dashboardName: 'licsys-next' });
    dashboard.addWidgets(new cloudwatch.TextWidget({
      markdown: '# LICSYS Next\nServices, event flow and the nightly progression run (was LICNIGHT).',
      width: 24,
      height: 2,
    }));

    const alarm = (id: string, metric: cloudwatch.IMetric, threshold: number, description: string) => {
      const a = new cloudwatch.Alarm(this, `Alarm-${id}`, {
        alarmName: `licsys-${id}`,
        metric,
        threshold,
        evaluationPeriods: 1,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        alarmDescription: description,
      });
      a.addAlarmAction(alarmAction);
      return a;
    };

    for (const d of deployed) {
      const cpu = d.service.metricCpuUtilization({ period: Duration.minutes(5) });
      const mem = d.service.metricMemoryUtilization({ period: Duration.minutes(5) });
      const errors = d.targetGroup.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, { period: Duration.minutes(5), statistic: 'Sum' });
      const latency = d.targetGroup.metrics.targetResponseTime({ period: Duration.minutes(5), statistic: 'p95' });
      const unhealthy = d.targetGroup.metrics.unhealthyHostCount({ period: Duration.minutes(1), statistic: 'Maximum' });
      dashboard.addWidgets(
        new cloudwatch.GraphWidget({ title: `${d.def.name} CPU / memory`, left: [cpu, mem], width: 8 }),
        new cloudwatch.GraphWidget({ title: `${d.def.name} 5xx`, left: [errors], width: 8 }),
        new cloudwatch.GraphWidget({ title: `${d.def.name} p95 latency`, left: [latency], width: 8 }),
      );
      alarm(`${d.def.name}-5xx`, errors, 5, `${d.def.name} returned 5xx responses`);
      alarm(`${d.def.name}-cpu`, cpu, 85, `${d.def.name} CPU above 85%`);
      alarm(`${d.def.name}-unhealthy`, unhealthy, 1, `${d.def.name} has unhealthy targets`);
    }

    const queueWidgets: cloudwatch.IWidget[] = [];
    for (const q of QUEUES) {
      const depth = queues[q.name].metricApproximateNumberOfMessagesVisible({ period: Duration.minutes(5) });
      const age = queues[q.name].metricApproximateAgeOfOldestMessage({ period: Duration.minutes(5) });
      const dlqDepth = dlqs[q.name].metricApproximateNumberOfMessagesVisible({ period: Duration.minutes(5), statistic: 'Maximum' });
      queueWidgets.push(new cloudwatch.GraphWidget({ title: q.name, left: [depth, dlqDepth], right: [age], width: 6 }));
      alarm(`${q.name}-dlq`, dlqDepth, 1, `Messages in ${q.name}-dlq (poison events)`);
      alarm(`${q.name}-age`, age, 900, `${q.name} oldest message older than 15 minutes`);
    }
    dashboard.addWidgets(...queueWidgets);

    const deliveryDlqDepth = deliveryDlq.metricApproximateNumberOfMessagesVisible({ period: Duration.minutes(5), statistic: 'Maximum' });
    const scheduleDlqDepth = scheduleDlq.metricApproximateNumberOfMessagesVisible({ period: Duration.minutes(5), statistic: 'Maximum' });
    const triggerErrors = triggerFn.metricErrors({ period: Duration.hours(1), statistic: 'Sum' });
    const apiErrors = new cloudwatch.Metric({
      namespace: 'AWS/ApiGateway',
      metricName: '5xx',
      dimensionsMap: { ApiId: httpApi.apiId, Stage: '$default' },
      period: Duration.minutes(5),
      statistic: 'Sum',
    });
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({ title: 'Nightly progression trigger', left: [triggerErrors, triggerFn.metricInvocations({ period: Duration.hours(1) })], width: 8 }),
      new cloudwatch.GraphWidget({ title: 'Bus delivery + schedule DLQs', left: [deliveryDlqDepth, scheduleDlqDepth], width: 8 }),
      new cloudwatch.GraphWidget({ title: 'HTTP API 5xx', left: [apiErrors], width: 8 }),
    );
    alarm('progression-run-failed', triggerErrors, 1, 'Nightly progression run (was LICNIGHT STEP020) failed');
    alarm('progression-schedule-dlq', scheduleDlqDepth, 1, 'Scheduler could not invoke the nightly progression run');
    alarm('bus-delivery-dlq', deliveryDlqDepth, 1, 'EventBridge could not deliver events to an inbox queue');
    alarm('http-api-5xx', apiErrors, 10, 'HTTP API returning 5xx');

    new CfnOutput(this, 'ApiUrl', { value: httpApi.apiEndpoint });
    new CfnOutput(this, 'UserPoolId', { value: userPool.userPoolId });
    new CfnOutput(this, 'UserPoolClientId', { value: uiClient.userPoolClientId });
    new CfnOutput(this, 'EventBusName', { value: bus.eventBusName });
  }
}
